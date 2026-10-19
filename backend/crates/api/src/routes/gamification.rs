use axum::{
    extract::{Path, State},
    http::HeaderMap,
    Json,
};
use camstar_ledger::{Direction, EntryKind, Line, Money};
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{DailyTaskItem, LeaderboardItem, VipTierItem},
    state::{system_clearing_account, user_coins_account, AppState},
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

    let items = vec![
        crate::models::CosmeticItem {
            item_id: "frame_golden_aura".into(),
            name: "Golden Aura Frame".into(),
            category: "avatar_frame".into(),
            icon_url: format!("{origin}/assets/cosmetics/frame_gold.png"),
            preview_asset: "bg_live_avatar".into(),
            description: "Glowing 24K radiant gold border with subtle shimmer".into(),
            is_equipped: true,
            is_owned: true,
            price_coins: 500,
            expires_in_days: Some(30),
        },
        crate::models::CosmeticItem {
            item_id: "frame_cyber_neon".into(),
            name: "Cyber Neon Frame".into(),
            category: "avatar_frame".into(),
            icon_url: format!("{origin}/assets/cosmetics/frame_neon.png"),
            preview_asset: "bg_live_avatar".into(),
            description: "Futuristic pulsating cyan & magenta neon frame".into(),
            is_equipped: false,
            is_owned: true,
            price_coins: 800,
            expires_in_days: Some(15),
        },
        crate::models::CosmeticItem {
            item_id: "frame_royal_vip".into(),
            name: "Royal VIP Crown".into(),
            category: "avatar_frame".into(),
            icon_url: format!("{origin}/assets/cosmetics/frame_royal.png"),
            preview_asset: "bg_live_avatar".into(),
            description: "Monarch crown ornament for elite patrons".into(),
            is_equipped: false,
            is_owned: false,
            price_coins: 2000,
            expires_in_days: Some(60),
        },
        crate::models::CosmeticItem {
            item_id: "frame_rose_bloom".into(),
            name: "Rose Blossom Frame".into(),
            category: "avatar_frame".into(),
            icon_url: format!("{origin}/assets/cosmetics/frame_rose.png"),
            preview_asset: "bg_live_avatar".into(),
            description: "Romantic blossoming pink petals with floral aura".into(),
            is_equipped: false,
            is_owned: true,
            price_coins: 600,
            expires_in_days: Some(7),
        },
        crate::models::CosmeticItem {
            item_id: "ride_supercar_gt".into(),
            name: "Supercar GT".into(),
            category: "entry_ride".into(),
            icon_url: format!("{origin}/assets/cosmetics/ride_car.png"),
            preview_asset: "ride_car".into(),
            description: "Thunderous engine rumble and screeching entrance effect".into(),
            is_equipped: true,
            is_owned: true,
            price_coins: 5000,
            expires_in_days: Some(30),
        },
        crate::models::CosmeticItem {
            item_id: "ride_golden_dragon".into(),
            name: "Golden Dragon Mount".into(),
            category: "entry_ride".into(),
            icon_url: format!("{origin}/assets/cosmetics/ride_dragon.png"),
            preview_asset: "ride_dragon".into(),
            description: "Majestic flame-breathing celestial dragon arrival".into(),
            is_equipped: false,
            is_owned: false,
            price_coins: 15000,
            expires_in_days: Some(90),
        },
        crate::models::CosmeticItem {
            item_id: "bubble_neon".into(),
            name: "Neon Glow Bubble".into(),
            category: "chat_bubble".into(),
            icon_url: format!("{origin}/assets/cosmetics/bubble_neon.png"),
            preview_asset: "bubble_neon".into(),
            description: "Cyan-purple glowing broadcast chat bubble".into(),
            is_equipped: true,
            is_owned: true,
            price_coins: 300,
            expires_in_days: None,
        },
        crate::models::CosmeticItem {
            item_id: "medal_mushaira_poet".into(),
            name: "AI Mushaira Poet".into(),
            category: "medal".into(),
            icon_url: format!("{origin}/assets/cosmetics/medal_poet.png"),
            preview_asset: "medal_poet".into(),
            description: "Master of verse, ghazal, and shayari creation".into(),
            is_equipped: true,
            is_owned: true,
            price_coins: 0,
            expires_in_days: None,
        },
    ];

    Ok(Json(crate::models::UserBagResponse {
        user_id,
        equipped_frame: Some("frame_golden_aura".into()),
        equipped_ride: Some("ride_supercar_gt".into()),
        equipped_bubble: Some("bubble_neon".into()),
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

    Ok(Json(serde_json::json!({
        "success": true,
        "user_id": user_id,
        "item_id": payload.item_id,
        "category": payload.category,
        "is_equipped": payload.equip,
        "message": if payload.equip { "Equipped successfully" } else { "Unequipped" }
    })))
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
