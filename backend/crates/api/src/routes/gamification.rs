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
            avatar_url: "https://cdn.camstar.live/avatars/1.png".into(),
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
    let mut ledger = state.ledger.write().await;
    let _ = ledger.post(
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
    );

    Ok(Json(serde_json::json!({
        "success": true,
        "task_id": task_id,
        "reward_coins": reward_amount
    })))
}

pub async fn get_vip_tiers(State(state): State<AppState>) -> Json<Vec<VipTierItem>> {
    Json((*state.vip_tiers).clone())
}
