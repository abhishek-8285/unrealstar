use axum::{
    extract::{Path, State},
    http::HeaderMap,
    Json,
};
use camstar_ledger::{Direction, EntryKind, Line, Money};
use chrono::{Duration, Utc};
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{
        ActiveRedPacketItem, FuncTagRequest, FuncTagResponse, GiftItem, MagicLampWishRequest,
        MagicLampWishResponse, RedPacketClaimResponse, RedPacketDropRequest, RedPacketDropResponse,
        SendGiftRequest, SendGiftResponse, ShovelDigRequest, ShovelDigResponse, SpinWheelRequest,
        SpinWheelResponse, WsServerMessage,
    },
    state::{user_coins_account, user_diamonds_account, AppState},
};

pub async fn list_gifts(State(state): State<AppState>) -> Json<Vec<GiftItem>> {
    Json((*state.gifts).clone())
}

pub async fn send_gift(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<SendGiftRequest>,
) -> Result<Json<SendGiftResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let sender_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    // Verify room and roster rules
    {
        let rooms = state.rooms.read().await;
        let room = rooms
            .get(&req.room_id)
            .ok_or_else(|| ApiError::NotFound("Room not found".into()))?;

        if !room.is_active {
            return Err(ApiError::BadRequest("Room is not active".into()));
        }

        // Anti-laundering invariant: A host cannot gift into their own room!
        room.roster
            .can_send_gift(sender_id)
            .map_err(ApiError::RoomDenial)?;
    }

    // Server-authoritative pricing
    let gift = state
        .gifts
        .iter()
        .find(|g| g.gift_id == req.gift_id)
        .ok_or_else(|| ApiError::NotFound("Gift SKU not found".into()))?;

    let total_coins = gift.price_coins * (req.count as i64);
    let amount = Money::from_minor(total_coins)?;

    let sender_coins_acct = user_coins_account(sender_id);
    let recipient_diamonds_acct = user_diamonds_account(req.recipient_id);

    // Atomically execute double-entry transfer in Ledger
    state
        .ledger
        .post(
            EntryKind::GiftSpend,
            &req.idempotency_key,
            vec![
                Line {
                    account: sender_coins_acct,
                    direction: Direction::Debit,
                    amount,
                },
                Line {
                    account: recipient_diamonds_acct,
                    direction: Direction::Credit,
                    amount,
                },
            ],
        )
        .await?;

    // Update room statistics
    {
        let mut rooms = state.rooms.write().await;
        if let Some(room) = rooms.get_mut(&req.room_id) {
            room.diamonds_earned += total_coins;
        }
    }

    // Broadcast gift event via WebSocket
    let ws_rooms = state.ws_rooms.read().await;
    if let Some(tx) = ws_rooms.get(&req.room_id) {
        let sender_nickname = {
            let users = state.users.read().await;
            users
                .get(&sender_id)
                .map(|u| u.nickname.clone())
                .unwrap_or_else(|| format!("User_{sender_id}"))
        };

        let _ = tx.send(WsServerMessage::GiftBroadcast {
            sender_id,
            sender_nickname: sender_nickname.clone(),
            gift_id: req.gift_id.clone(),
            count: req.count,
        });

        if gift.is_broadcast {
            let _ = tx.send(WsServerMessage::MarqueeBroadcast {
                message: format!(
                    "✨ {sender_nickname} gifted {} x{} in Room #{}!",
                    gift.name, req.count, req.room_id
                ),
            });
        }
    }

    // Push fan-out point: count recipient devices for FCM delivery once a
    // service-account key is provisioned (see routes::push).
    let push_devices = crate::routes::push::recipient_device_count(&state, req.recipient_id).await;
    if push_devices > 0 {
        eprintln!(
            "push: gift {} x{} to user {} ({} device(s) registered; FCM send needs CAMSTAR_FCM_KEY)",
            req.gift_id, req.count, req.recipient_id, push_devices
        );
    }

    let remaining_coins = state.ledger.balance(sender_coins_acct).await?.to_minor();
    let recipient_diamonds = state
        .ledger
        .balance(recipient_diamonds_acct)
        .await?
        .to_minor();

    Ok(Json(SendGiftResponse {
        success: true,
        remaining_coins,
        recipient_diamonds,
        broadcast_triggered: gift.is_broadcast,
    }))
}

pub async fn drop_red_packet(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<RedPacketDropRequest>,
) -> Result<Json<RedPacketDropResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    if req.total_coins <= 0 || req.split_count == 0 {
        return Err(ApiError::BadRequest(
            "total_coins and split_count must be positive".into(),
        ));
    }

    // Escrow: debit dropper's coins into clearing. The coins sit here until
    // claimed; claims only redistribute this escrow, never mint.
    let amount = Money::from_minor(req.total_coins)?;
    state
        .ledger
        .post(
            EntryKind::GiftSpend,
            &req.idempotency_key,
            vec![
                Line {
                    account: user_coins_account(user_id),
                    direction: Direction::Debit,
                    amount,
                },
                Line {
                    account: crate::state::system_clearing_account(),
                    direction: Direction::Credit,
                    amount,
                },
            ],
        )
        .await?;

    let packet_id = format!("packet_{user_id}_{}", Uuid::new_v4());
    let expires_at = Utc::now() + Duration::minutes(5);

    state.red_packets.write().await.insert(
        packet_id.clone(),
        crate::state::RedPacketDrop {
            packet_id: packet_id.clone(),
            room_id: req.room_id,
            dropper_user_id: user_id,
            total_coins: req.total_coins,
            split_count: req.split_count,
            claimants: std::collections::HashSet::new(),
            expires_at,
        },
    );

    if req.room_id > 0 {
        let ws_rooms = state.ws_rooms.read().await;
        if let Some(tx) = ws_rooms.get(&req.room_id) {
            let dropper_nickname = {
                let users = state.users.read().await;
                users
                    .get(&user_id)
                    .map(|u| u.nickname.clone())
                    .unwrap_or_else(|| format!("User_{user_id}"))
            };
            let _ = tx.send(crate::models::WsServerMessage::MarqueeBroadcast {
                message: format!(
                    "🧧 {dropper_nickname} dropped a Red Packet of {} coins ({} splits)!",
                    req.total_coins, req.split_count
                ),
            });
        }
    }

    Ok(Json(RedPacketDropResponse {
        packet_id,
        expires_at,
    }))
}

pub async fn list_active_red_packets(
    Path(room_id): Path<u64>,
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<Vec<ActiveRedPacketItem>>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let current_user_id = state.authenticate_header(auth_header).await.unwrap_or(0);

    let packets = state.red_packets.read().await;
    let users = state.users.read().await;
    let now = Utc::now();

    let active: Vec<ActiveRedPacketItem> = packets
        .values()
        .filter(|p| (p.room_id == room_id || p.room_id == 0) && p.expires_at > now && (p.claimants.len() as u32) < p.split_count)
        .map(|p| {
            let dropper_nickname = users
                .get(&p.dropper_user_id)
                .map(|u| u.nickname.clone())
                .unwrap_or_else(|| format!("User_{}", p.dropper_user_id));
            let remaining_splits = p.split_count - p.claimants.len() as u32;
            ActiveRedPacketItem {
                packet_id: p.packet_id.clone(),
                room_id: p.room_id,
                dropper_user_id: p.dropper_user_id,
                dropper_nickname,
                total_coins: p.total_coins,
                split_count: p.split_count,
                remaining_splits,
                expires_at: p.expires_at,
                already_claimed: p.claimants.contains(&current_user_id),
            }
        })
        .collect();

    Ok(Json(active))
}

pub async fn claim_red_packet(
    Path(packet_id): Path<String>,
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<RedPacketClaimResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    // Claim under write lock so check-and-mark is atomic.
    let claimed_amount = {
        let mut packets = state.red_packets.write().await;
        let packet = packets
            .get_mut(&packet_id)
            .ok_or_else(|| ApiError::NotFound("Red packet not found".into()))?;

        if Utc::now() > packet.expires_at {
            packets.remove(&packet_id);
            return Err(ApiError::BadRequest("Red packet expired".into()));
        }

        if packet.claimants.contains(&user_id) {
            return Err(ApiError::Conflict("Already claimed this red packet".into()));
        }

        if packet.claimants.len() as u32 >= packet.split_count {
            packets.remove(&packet_id);
            return Err(ApiError::BadRequest("Red packet fully claimed".into()));
        }

        // Split: each claimer gets total_coins / split_count; the last
        // claimer absorbs the remainder so the full escrow is distributed.
        let already_claimed: i64 =
            packet.claimants.len() as i64 * (packet.total_coins / packet.split_count as i64);
        let remaining_splits = packet.split_count as i64 - packet.claimants.len() as i64;
        let amount = if remaining_splits == 1 {
            packet.total_coins - already_claimed
        } else {
            packet.total_coins / packet.split_count as i64
        };

        packet.claimants.insert(user_id);

        // Remove packet once fully claimed.
        if packet.claimants.len() as u32 >= packet.split_count {
            packets.remove(&packet_id);
        }

        amount
    };

    // Transfer escrowed coins: clearing → claimer. No mint — just redistribute
    // the amount the dropper already paid in.
    let amount = Money::from_minor(claimed_amount)?;
    state
        .ledger
        .post(
            EntryKind::Adjustment,
            &format!("claim_{user_id}_{packet_id}"),
            vec![
                Line {
                    account: crate::state::system_clearing_account(),
                    direction: Direction::Debit,
                    amount,
                },
                Line {
                    account: user_coins_account(user_id),
                    direction: Direction::Credit,
                    amount,
                },
            ],
        )
        .await?;

    // Report remaining splits (best-effort; packet may already be removed).
    let remaining = state
        .red_packets
        .read()
        .await
        .get(&packet_id)
        .map(|p| p.split_count - p.claimants.len() as u32)
        .unwrap_or(0);

    Ok(Json(RedPacketClaimResponse {
        coins_claimed: claimed_amount,
        remaining_splits: remaining,
    }))
}

pub async fn spin_turntable(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<SpinWheelRequest>,
) -> Result<Json<SpinWheelResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    if req.coins_bet <= 0 {
        return Err(ApiError::BadRequest(
            "Bet coins must be greater than 0".into(),
        ));
    }

    // WheelPan Sectors (8 sectors, as decompiled from PrivateWheelView)
    // Multipliers: [0x, 1x, 2x, 3x, 5x, 10x, 20x, 50x]
    let sectors: [(u32, &'static str); 8] = [
        (0, "No Luck"),
        (1, "Lucky Return"),
        (2, "Double Paisa"),
        (3, "Triple Spark"),
        (5, "Mega Fortune"),
        (10, "Super Jackpot"),
        (20, "Taj Mahal Gold"),
        (50, "Grand Crown"),
    ];

    // Server-authoritative CSPRNG outcome. EV ≈ 0.84 (~16% house edge).
    // Sectors 6 (20x) and 7 (50x) are unreachable display slots.
    let seed = u32::from_be_bytes(Uuid::new_v4().into_bytes()[..4].try_into().unwrap()) % 100;
    let target_sector: usize = match seed {
        0..=54 => 0,  // 55% chance: 0x
        55..=79 => 1, // 25% chance: 1x
        80..=91 => 2, // 12% chance: 2x
        92..=96 => 3, // 5% chance: 3x
        97..=98 => 4, // 2% chance: 5x
        99 => 5,      // 1% chance: 10x
        _ => 0,
    };

    let (multiplier, tier) = sectors[target_sector];
    let reward_amount = req.coins_bet * (multiplier as i64);

    // Exact rotation angle formula decompiled from PrivateWheelView.d:
    // angle = 4 full rotations (1440 deg) minus sector arc offset
    let target_angle_deg = 1440.0f32 - (360.0f32 / 8.0f32) * (target_sector as f32);

    let bet = Money::from_minor(req.coins_bet)?;
    let reward = Money::from_minor(reward_amount)?;
    let idem_key = req
        .idempotency_key
        .clone()
        .unwrap_or_else(|| format!("spin_{user_id}_{}", Uuid::new_v4()));

    // Execute double-entry bet deduction
    state
        .ledger
        .post(
            EntryKind::GiftSpend,
            &format!("{idem_key}_bet"),
            vec![
                Line {
                    account: user_coins_account(user_id),
                    direction: Direction::Debit,
                    amount: bet,
                },
                Line {
                    account: crate::state::system_clearing_account(),
                    direction: Direction::Credit,
                    amount: bet,
                },
            ],
        )
        .await?;

    // If won, credit user from clearing account
    if reward_amount > 0 {
        state
            .ledger
            .post(
                EntryKind::Adjustment,
                &format!("{idem_key}_win"),
                vec![
                    Line {
                        account: crate::state::system_clearing_account(),
                        direction: Direction::Debit,
                        amount: reward,
                    },
                    Line {
                        account: user_coins_account(user_id),
                        direction: Direction::Credit,
                        amount: reward,
                    },
                ],
            )
            .await?;
    }

    // Host commission (5% rake of bet awarded as diamonds)
    let actor_diamonds = if req.room_id > 0 {
        let host_id = {
            let rooms = state.rooms.read().await;
            rooms.get(&req.room_id).map(|r| r.host_id)
        };
        if let Some(host_id) = host_id {
            let host_rake = (req.coins_bet * 5 / 100).max(1);
            if let Ok(rake_amount) = Money::from_minor(host_rake) {
                let _ = state
                    .ledger
                    .post(
                        EntryKind::Adjustment,
                        &format!("{idem_key}_rake"),
                        vec![
                            Line {
                                account: crate::state::system_clearing_account(),
                                direction: Direction::Debit,
                                amount: rake_amount,
                            },
                            Line {
                                account: user_diamonds_account(host_id),
                                direction: Direction::Credit,
                                amount: rake_amount,
                            },
                        ],
                    )
                    .await;
            }
            host_rake
        } else {
            0
        }
    } else {
        0
    };

    // WebSocket Room Broadcast
    let user_nickname = {
        let users = state.users.read().await;
        users
            .get(&user_id)
            .map(|u| u.nickname.clone())
            .unwrap_or_else(|| format!("User_{user_id}"))
    };

    if req.room_id > 0 {
        let ws_rooms = state.ws_rooms.read().await;
        if let Some(tx) = ws_rooms.get(&req.room_id) {
            let _ = tx.send(WsServerMessage::WheelSpinBroadcast {
                user_id,
                nickname: user_nickname.clone(),
                sector: target_sector as u32,
                multiplier,
                prize: reward_amount,
            });

            if multiplier >= 10 {
                let _ = tx.send(WsServerMessage::MarqueeBroadcast {
                    message: format!(
                        "🎰 {user_nickname} just hit {multiplier}x on Lucky Wheel in Room #{}! Prize: {reward_amount} coins!",
                        req.room_id
                    ),
                });
            }
        }
    }

    let remaining_coins = state
        .ledger
        .balance(user_coins_account(user_id))
        .await?
        .to_minor();

    Ok(Json(SpinWheelResponse {
        target_sector: target_sector as u32,
        target_angle_deg,
        multiplier,
        reward_coins: reward_amount,
        actor_diamonds,
        remaining_coins,
        tier: tier.to_string(),
    }))
}

pub async fn dig_shovel(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<ShovelDigRequest>,
) -> Result<Json<ShovelDigResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    // mult_factor intentionally unused: it previously scaled the jackpot to 25x+,
    // pushing EV far above 1. Cost tiers stay cosmetic (visual + stake size only).
    let (cost, _mult_factor) = match req.shovel_type.to_lowercase().as_str() {
        "bronze" => (10i64, 1u32),
        "silver" => (50i64, 5u32),
        "gold" => (200i64, 20u32),
        _ => {
            return Err(ApiError::BadRequest(
                "Invalid shovel type (must be bronze, silver, gold)".into(),
            ))
        }
    };

    // Server-authoritative CSPRNG. EV ≈ 0.78 (~22% house edge).
    let bet = Money::from_minor(cost)?;
    let seed = u32::from_be_bytes(Uuid::new_v4().into_bytes()[..4].try_into().unwrap()) % 100;
    let (prize_name, coins_won, diamonds_won, is_jackpot) = match seed {
        0..=59 => ("Better luck next time!", 0, 0, false), // 60% miss
        60..=79 => ("Gold Bean Nugget", cost, 0, false),   // 20%: 1x
        80..=91 => ("Diwali Sparkler Gem", cost * 2, cost / 10, false), // 12%: 2x
        92..=97 => ("Royal Silver Ingot", cost * 3, cost / 5, false), // 6%: 3x
        _ => ("💎 Kohinoor Jackpot Chest", cost * 8, cost / 2, true), // 2%: 8x
    };

    let reward = Money::from_minor(coins_won)?;
    state
        .ledger
        .post(
            EntryKind::GiftSpend,
            &format!("{}_bet", req.idempotency_key),
            vec![
                Line {
                    account: user_coins_account(user_id),
                    direction: Direction::Debit,
                    amount: bet,
                },
                Line {
                    account: crate::state::system_clearing_account(),
                    direction: Direction::Credit,
                    amount: bet,
                },
            ],
        )
        .await?;

    if coins_won > 0 {
        state
            .ledger
            .post(
                EntryKind::Adjustment,
                &format!("{}_win", req.idempotency_key),
                vec![
                    Line {
                        account: crate::state::system_clearing_account(),
                        direction: Direction::Debit,
                        amount: reward,
                    },
                    Line {
                        account: user_coins_account(user_id),
                        direction: Direction::Credit,
                        amount: reward,
                    },
                ],
            )
            .await?;
    }

    if is_jackpot && req.room_id > 0 {
        let ws_rooms = state.ws_rooms.read().await;
        if let Some(tx) = ws_rooms.get(&req.room_id) {
            let nickname = {
                let users = state.users.read().await;
                users
                    .get(&user_id)
                    .map(|u| u.nickname.clone())
                    .unwrap_or_else(|| format!("User_{user_id}"))
            };
            let _ = tx.send(WsServerMessage::MiniGameJackpotBroadcast {
                game_name: "Lucky Shovel".into(),
                winner_id: user_id,
                winner_nickname: nickname.clone(),
                prize_description: format!("{prize_name} ({coins_won} Coins!)"),
            });
        }
    }

    let remaining_coins = state
        .ledger
        .balance(user_coins_account(user_id))
        .await?
        .to_minor();

    Ok(Json(ShovelDigResponse {
        prize_name: prize_name.to_string(),
        coins_won,
        diamonds_won,
        is_jackpot,
        remaining_coins,
    }))
}

pub async fn wish_magic_lamp(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<MagicLampWishRequest>,
) -> Result<Json<MagicLampWishResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let wishes = req.wishes_count.clamp(1, 100);
    let cost = (wishes as i64) * 50;
    let bet = Money::from_minor(cost)?;

    // Server-authoritative CSPRNG. EV ≈ 0.75 (~25% house edge).
    let seed = u32::from_be_bytes(Uuid::new_v4().into_bytes()[..4].try_into().unwrap()) % 100;
    let (mult, is_super) = match seed {
        0..=59 => (0u32, false),  // 60%: 0x
        60..=82 => (1u32, false), // 23%: 1x
        83..=93 => (2u32, false), // 11%: 2x
        94..=98 => (4u32, false), // 5%: 4x
        _ => (10u32, true),       // 1%: 10x super wish
    };

    let total_won = cost * (mult as i64);
    let reward = Money::from_minor(total_won)?;

    state
        .ledger
        .post(
            EntryKind::GiftSpend,
            &format!("{}_lamp_bet", req.idempotency_key),
            vec![
                Line {
                    account: user_coins_account(user_id),
                    direction: Direction::Debit,
                    amount: bet,
                },
                Line {
                    account: crate::state::system_clearing_account(),
                    direction: Direction::Credit,
                    amount: bet,
                },
            ],
        )
        .await?;

    if total_won > 0 {
        state
            .ledger
            .post(
                EntryKind::Adjustment,
                &format!("{}_lamp_win", req.idempotency_key),
                vec![
                    Line {
                        account: crate::state::system_clearing_account(),
                        direction: Direction::Debit,
                        amount: reward,
                    },
                    Line {
                        account: user_coins_account(user_id),
                        direction: Direction::Credit,
                        amount: reward,
                    },
                ],
            )
            .await?;
    }

    let remaining_coins = state
        .ledger
        .balance(user_coins_account(user_id))
        .await?
        .to_minor();

    Ok(Json(MagicLampWishResponse {
        total_reward_coins: total_won,
        multiplier_hit: mult,
        is_super_wish: is_super,
        remaining_coins,
    }))
}

pub async fn handle_functag_rpc(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<FuncTagRequest>,
) -> Result<Json<FuncTagResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let _user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    match req.func_tag {
        // FuncTag: 51050703 -> Lucky Paisa / Treasure Reward Claim
        51050703 => {
            let reward_id = req.reward_id.unwrap_or(1);
            Ok(Json(FuncTagResponse {
                tag_code: "SUCCESS".into(),
                message: format!("Treasure reward #{reward_id} claimed successfully"),
                data: Some(serde_json::json!({
                    "rewardId": reward_id,
                    "coinsWon": 500,
                    "status": "CLAIMED"
                })),
            }))
        }
        // FuncTag: 10005010 -> VIP Membership Purchase
        10005010 => {
            let prop_id = req.prop_id.unwrap_or(1);
            let validity_days = req.period_of_validity.unwrap_or(30);
            Ok(Json(FuncTagResponse {
                tag_code: "SUCCESS".into(),
                message: format!("VIP Tier #{prop_id} activated for {validity_days} days"),
                data: Some(serde_json::json!({
                    "propId": prop_id,
                    "validUntilDays": validity_days,
                    "badge": "SVIP_GOLD"
                })),
            }))
        }
        // FuncTag: 20010105 -> Luxury Mount / Car Purchase
        20010105 => {
            let car_id = req.car_id.unwrap_or(101);
            let months = req.months.unwrap_or(1);
            Ok(Json(FuncTagResponse {
                tag_code: "SUCCESS".into(),
                message: format!("Mount #{car_id} acquired for {months} month(s)"),
                data: Some(serde_json::json!({
                    "carId": car_id,
                    "carName": "Taj Royal Carriage",
                    "months": months
                })),
            }))
        }
        // FuncTag: 50006104 -> System Notifications
        50006104 => Ok(Json(FuncTagResponse {
            tag_code: "SUCCESS".into(),
            message: "Notifications loaded".into(),
            data: Some(serde_json::json!({
                "total": 1,
                "items": [
                    {
                        "msgId": 1001,
                        "title": "Welcome to CamStar",
                        "body": "Enjoy live streaming, gifting, and lucky games!",
                        "timestamp": Utc::now().to_rfc3339()
                    }
                ]
            })),
        })),
        other => Ok(Json(FuncTagResponse {
            tag_code: "OK_GENERIC".into(),
            message: format!("Processed FuncTag {other}"),
            data: None,
        })),
    }
}
