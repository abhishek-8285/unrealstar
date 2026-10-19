use axum::{
    extract::{Path, State},
    http::HeaderMap,
    Json,
};
use camstar_ledger::{Direction, EntryKind, Line, Money};
use std::collections::HashMap;
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{DailyTaskItem, LeaderboardItem, VipTierItem},
    state::{system_clearing_account, user_coins_account, AppState, UserCosmetics},
};

pub async fn get_leaderboards(State(state): State<AppState>) -> Json<Vec<LeaderboardItem>> {
    let users = state.users.read().await;

    let mut rankings: Vec<LeaderboardItem> = users
        .values()
        .take(10)
        .enumerate()
        .map(|(idx, u)| LeaderboardItem {
            rank: (idx + 1) as u32,
            user_id: u.user_id,
            nickname: u.nickname.clone(),
            avatar_url: u.avatar_url.clone(),
            score: 5000 - (idx as i64 * 300),
        })
        .collect();

    if rankings.is_empty() {
        rankings.push(LeaderboardItem {
            rank: 1,
            user_id: 1,
            nickname: "TopStreamer".into(),
            avatar_url: format!("{}/assets/avatars/a1.jpg", crate::public_origin()),
            score: 10000,
        });
    }

    Json(rankings)
}

pub async fn get_daily_tasks(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<Vec<DailyTaskItem>>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut tasks_map = state.daily_tasks.write().await;
    let tasks = tasks_map.entry(user_id).or_insert_with(|| {
        vec![
            DailyTaskItem {
                task_id: "task_checkin".into(),
                title: "Daily Check-in".into(),
                progress: 1,
                target: 1,
                reward_coins: 10,
                is_claimed: false,
            },
            DailyTaskItem {
                task_id: "task_watch_10m".into(),
                title: "Watch streams for 10 minutes".into(),
                progress: 5,
                target: 10,
                reward_coins: 20,
                is_claimed: false,
            },
            DailyTaskItem {
                task_id: "task_send_gift".into(),
                title: "Send 1 gift in any room".into(),
                progress: 0,
                target: 1,
                reward_coins: 30,
                is_claimed: false,
            },
        ]
    });

    Ok(Json(tasks.clone()))
}

pub async fn claim_task(
    headers: HeaderMap,
    Path(task_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let reward_amount = {
        let mut tasks_map = state.daily_tasks.write().await;
        let tasks = tasks_map
            .get_mut(&user_id)
            .ok_or_else(|| ApiError::NotFound("Tasks not found".into()))?;

        let task = tasks
            .iter_mut()
            .find(|t| t.task_id == task_id)
            .ok_or_else(|| ApiError::NotFound("Task not found".into()))?;

        if task.is_claimed {
            return Err(ApiError::BadRequest("Task reward already claimed".into()));
        }
        if task.progress < task.target {
            return Err(ApiError::BadRequest("Task target not yet achieved".into()));
        }

        task.is_claimed = true;
        task.reward_coins
    };

    // Credit coins in Ledger
    let amount = Money::from_minor(reward_amount)?;
    let _ = state
        .ledger
        .post(
            EntryKind::Adjustment,
            &format!("task_claim_{user_id}_{task_id}_{}", Uuid::new_v4()),
            vec![
                Line {
                    account: system_clearing_account(),
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
        .await;

    Ok(Json(serde_json::json!({
        "success": true,
        "task_id": task_id,
        "reward_coins": reward_amount
    })))
}

pub async fn get_vip_tiers(State(state): State<AppState>) -> Json<Vec<VipTierItem>> {
    Json((*state.vip_tiers).clone())
}

// ---------------------------------------------------------------------------
// Backpack & Cosmetics (Trendo MyBagActivity & StoreBagActivity)
// ---------------------------------------------------------------------------

pub async fn get_my_bag(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<crate::models::UserBagResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let origin = crate::public_origin();
    let mut bags = state.cosmetics.write().await;
    let bag = bags.entry(user_id).or_insert_with(demo_cosmetics);

    let items = cosmetic_catalog(&origin)
        .into_iter()
        .map(|mut item| {
            item.is_owned = bag.owned.contains(&item.item_id);
            item.is_equipped = bag.equipped.get(&item.category) == Some(&item.item_id);
            item
        })
        .collect();

    Ok(Json(crate::models::UserBagResponse {
        user_id,
        equipped_frame: bag.equipped.get("avatar_frame").cloned(),
        equipped_ride: bag.equipped.get("entry_ride").cloned(),
        equipped_bubble: bag.equipped.get("chat_bubble").cloned(),
        items,
    }))
}

pub async fn equip_cosmetic(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(payload): Json<crate::models::EquipCosmeticRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let origin = crate::public_origin();
    let item = cosmetic_catalog(&origin)
        .into_iter()
        .find(|i| i.item_id == payload.item_id)
        .ok_or_else(|| ApiError::NotFound("Cosmetic item not found".into()))?;
    let category = item.category.clone();

    let already_owned = {
        let bags = state.cosmetics.read().await;
        bags.get(&user_id)
            .map(|b| b.owned.contains(&item.item_id))
            .unwrap_or(false)
    };

    // Unlocking is a purchase: debit coins once, then remember the item. The
    // ledger enforces the balance, so the client cannot equip for free.
    if payload.equip && !already_owned {
        let price = item.price_coins as i64;
        if price > 0 {
            let coins = state
                .ledger
                .balance(user_coins_account(user_id))
                .await?
                .to_minor();
            if coins < price {
                return Err(ApiError::BadRequest(format!(
                    "Not enough Coins: {price} needed, {coins} available"
                )));
            }
            let amount = Money::from_minor(price)?;
            state
                .ledger
                .post(
                    EntryKind::CosmeticPurchase,
                    &format!("cosmetic_purchase_{user_id}_{}", item.item_id),
                    vec![
                        Line {
                            account: user_coins_account(user_id),
                            direction: Direction::Debit,
                            amount,
                        },
                        Line {
                            account: system_clearing_account(),
                            direction: Direction::Credit,
                            amount,
                        },
                    ],
                )
                .await?;
        }
        let mut bags = state.cosmetics.write().await;
        bags.entry(user_id)
            .or_insert_with(demo_cosmetics)
            .owned
            .insert(item.item_id.clone());
    }

    let (is_equipped, coin_balance) = {
        let mut bags = state.cosmetics.write().await;
        let bag = bags.entry(user_id).or_insert_with(demo_cosmetics);
        if !bag.owned.contains(&item.item_id) {
            return Err(ApiError::BadRequest("Cosmetic item is not owned".into()));
        }
        let equipped = if payload.equip {
            bag.equipped.insert(category.clone(), item.item_id.clone());
            true
        } else {
            if bag.equipped.get(&category) == Some(&item.item_id) {
                bag.equipped.remove(&category);
            }
            false
        };
        drop(bags);
        let coins = state
            .ledger
            .balance(user_coins_account(user_id))
            .await?
            .to_minor();
        (equipped, coins)
    };

    Ok(Json(serde_json::json!({
        "success": true,
        "user_id": user_id,
        "item_id": item.item_id,
        "category": category,
        "is_equipped": is_equipped,
        "coin_balance": coin_balance,
        "message": if is_equipped { "Equipped successfully" } else { "Unequipped" }
    })))
}

/// Cosmetic catalog. Ownership and equipped flags are per caller and filled
/// in by `get_my_bag`; prices and copy are static demo content.
fn cosmetic_catalog(origin: &str) -> Vec<crate::models::CosmeticItem> {
    let item = |item_id: &str,
                name: &str,
                category: &str,
                asset: &str,
                preview_asset: &str,
                description: &str,
                price_coins: u64,
                expires_in_days: Option<u32>| crate::models::CosmeticItem {
        item_id: item_id.into(),
        name: name.into(),
        category: category.into(),
        icon_url: format!("{origin}/assets/cosmetics/{asset}"),
        preview_asset: preview_asset.into(),
        description: description.into(),
        is_equipped: false,
        is_owned: false,
        price_coins,
        expires_in_days,
    };

    vec![
        item(
            "frame_golden_aura",
            "Golden Aura Frame",
            "avatar_frame",
            "frame_gold.png",
            "bg_live_avatar",
            "Glowing 24K radiant gold border with subtle shimmer",
            500,
            Some(30),
        ),
        item(
            "frame_cyber_neon",
            "Cyber Neon Frame",
            "avatar_frame",
            "frame_neon.png",
            "bg_live_avatar",
            "Futuristic pulsating cyan & magenta neon frame",
            800,
            Some(15),
        ),
        item(
            "frame_royal_vip",
            "Royal VIP Crown",
            "avatar_frame",
            "frame_royal.png",
            "bg_live_avatar",
            "Monarch crown ornament for elite patrons",
            2000,
            Some(60),
        ),
        item(
            "frame_rose_bloom",
            "Rose Blossom Frame",
            "avatar_frame",
            "frame_rose.png",
            "bg_live_avatar",
            "Romantic blossoming pink petals with floral aura",
            600,
            Some(7),
        ),
        item(
            "ride_supercar_gt",
            "Supercar GT",
            "entry_ride",
            "ride_car.png",
            "ride_car",
            "Thunderous engine rumble and screeching entrance effect",
            5000,
            Some(30),
        ),
        item(
            "ride_golden_dragon",
            "Golden Dragon Mount",
            "entry_ride",
            "ride_dragon.png",
            "ride_dragon",
            "Majestic flame-breathing celestial dragon arrival",
            15000,
            Some(90),
        ),
        item(
            "bubble_neon",
            "Neon Glow Bubble",
            "chat_bubble",
            "bubble_neon.png",
            "bubble_neon",
            "Cyan-purple glowing broadcast chat bubble",
            300,
            None,
        ),
        item(
            "medal_mushaira_poet",
            "AI Mushaira Poet",
            "medal",
            "medal_poet.png",
            "medal_poet",
            "Master of verse, ghazal, and shayari creation",
            0,
            None,
        ),
    ]
}

/// Fresh accounts start with the demo closet the bag has always shown, so the
/// screen is populated the first time it opens. Purchases extend this set.
fn demo_cosmetics() -> UserCosmetics {
    UserCosmetics {
        owned: [
            "frame_golden_aura",
            "frame_cyber_neon",
            "frame_rose_bloom",
            "ride_supercar_gt",
            "bubble_neon",
            "medal_mushaira_poet",
        ]
        .iter()
        .map(|id| id.to_string())
        .collect(),
        equipped: HashMap::from([
            ("avatar_frame".to_string(), "frame_golden_aura".to_string()),
            ("entry_ride".to_string(), "ride_supercar_gt".to_string()),
            ("chat_bubble".to_string(), "bubble_neon".to_string()),
            ("medal".to_string(), "medal_mushaira_poet".to_string()),
        ]),
    }
}

// ---------------------------------------------------------------------------
// Level & Honor Wall (Trendo MyLevelActivity & BadgeWallActivity)
// ---------------------------------------------------------------------------

pub async fn get_user_level(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<crate::models::UserLevelResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let privileges = vec![
        crate::models::LevelPrivilegeItem {
            level: 1,
            title: "Standard Broadcast Seat".into(),
            icon: "ic_me_live".into(),
            description: "Access to join and view public broadcasts".into(),
            is_unlocked: true,
        },
        crate::models::LevelPrivilegeItem {
            level: 5,
            title: "Colorful Chat Messages".into(),
            icon: "ic_me_star".into(),
            description: "Send standout glowing comments in live streams".into(),
            is_unlocked: true,
        },
        crate::models::LevelPrivilegeItem {
            level: 10,
            title: "Special Room Entrance Effect".into(),
            icon: "ic_me_crown".into(),
            description: "Audible chime and banner notification when joining rooms".into(),
            is_unlocked: true,
        },
        crate::models::LevelPrivilegeItem {
            level: 25,
            title: "Exclusive Wealth Avatar Badge".into(),
            icon: "ic_live_trophy".into(),
            description: "Golden level crest displayed beside user nickname".into(),
            is_unlocked: true,
        },
        crate::models::LevelPrivilegeItem {
            level: 50,
            title: "Priority Guest Seat Privilege".into(),
            icon: "ic_me_svip".into(),
            description: "Instant access to microphone seating queue".into(),
            is_unlocked: false,
        },
        crate::models::LevelPrivilegeItem {
            level: 75,
            title: "Custom Room Entry Car Ride".into(),
            icon: "ic_me_store".into(),
            description: "Full-screen luxury animation upon room arrival".into(),
            is_unlocked: false,
        },
        crate::models::LevelPrivilegeItem {
            level: 100,
            title: "Emperor VIP Crown & Immunity".into(),
            icon: "ic_me_creator".into(),
            description: "Cannot be muted or kicked by room managers".into(),
            is_unlocked: false,
        },
    ];

    let badges = get_all_badges();

    Ok(Json(crate::models::UserLevelResponse {
        user_id,
        current_level: 48,
        current_exp: 72450,
        next_level_exp: 80000,
        progress_pct: 90,
        wealth_title: "Diamond Lord".into(),
        privileges,
        badges,
    }))
}

pub async fn get_user_badges(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<crate::models::UserBadgesResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let badges = get_all_badges();
    let unlocked_count = badges.iter().filter(|b| b.is_unlocked).count();
    let total_badges = badges.len();

    Ok(Json(crate::models::UserBadgesResponse {
        user_id,
        total_badges,
        unlocked_count,
        badges,
    }))
}

fn get_all_badges() -> Vec<crate::models::BadgeItem> {
    vec![
        crate::models::BadgeItem {
            badge_id: "badge_first_blood".into(),
            name: "First Blood".into(),
            icon: "ic_me_gift".into(),
            description: "Sent your first live gift".into(),
            is_unlocked: true,
            unlocked_at: Some("2026-10-01".into()),
        },
        crate::models::BadgeItem {
            badge_id: "badge_gifter_legend".into(),
            name: "Gifter Legend".into(),
            icon: "ic_me_crown".into(),
            description: "Sent over 100,000 coins in gifts".into(),
            is_unlocked: true,
            unlocked_at: Some("2026-10-10".into()),
        },
        crate::models::BadgeItem {
            badge_id: "badge_mushaira_poet".into(),
            name: "Mushaira Poet".into(),
            icon: "ic_me_creator".into(),
            description: "Published an AI Mushaira poem to feed".into(),
            is_unlocked: true,
            unlocked_at: Some("2026-10-16".into()),
        },
        crate::models::BadgeItem {
            badge_id: "badge_pk_warrior".into(),
            name: "PK Warrior".into(),
            icon: "ic_live_trophy".into(),
            description: "Participated in 10 PK Battles".into(),
            is_unlocked: true,
            unlocked_at: Some("2026-10-14".into()),
        },
        crate::models::BadgeItem {
            badge_id: "badge_top_talent".into(),
            name: "Top Talent".into(),
            icon: "ic_me_star".into(),
            description: "Achieved 1,000 live broadcast hours".into(),
            is_unlocked: false,
            unlocked_at: None,
        },
        crate::models::BadgeItem {
            badge_id: "badge_streak_master".into(),
            name: "Streak Master".into(),
            icon: "ic_me_check".into(),
            description: "Completed 30 consecutive daily check-ins".into(),
            is_unlocked: false,
            unlocked_at: None,
        },
    ]
}
