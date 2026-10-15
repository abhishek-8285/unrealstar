use axum::{extract::State, http::HeaderMap, Json};
use serde::{Deserialize, Serialize};

use crate::{error::ApiError, state::AppState};

#[derive(Debug, Deserialize)]
pub struct PushRegisterRequest {
    pub fcm_token: String,
    pub device_uid: String,
}

#[derive(Debug, Serialize)]
pub struct PushRegisterResponse {
    pub registered: bool,
    pub device_count: usize,
}

/// Register an FCM device token for the caller. Tokens are capped at 10 per
/// user (oldest evicted first is overkill; extras are rejected). Actual
/// delivery via FCM HTTP v1 is the follow-up once a service-account key is
/// provisioned — see `notify_gift` for the fan-out point.
pub async fn register_push(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<PushRegisterRequest>,
) -> Result<Json<PushRegisterResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let token = req.fcm_token.trim();
    if token.len() < 16 || token.len() > 4096 {
        return Err(ApiError::BadRequest("Invalid FCM token".into()));
    }
    if req.device_uid.trim().is_empty() {
        return Err(ApiError::BadRequest("device_uid is required".into()));
    }

    let mut tokens = state.push_tokens.write().await;
    let entry = tokens.entry(user_id).or_default();
    entry.insert(token.to_string());
    // Cap: drop an arbitrary token when over budget so one account cannot
    // accumulate unbounded device rows.
    while entry.len() > 10 {
        if let Some(first) = entry.iter().next().cloned() {
            entry.remove(&first);
        } else {
            break;
        }
    }
    let device_count = entry.len();

    Ok(Json(PushRegisterResponse {
        registered: true,
        device_count,
    }))
}

/// Fan-out point for gift notifications. Returns how many device tokens the
/// recipient holds; the caller logs it. Replace the body with an FCM HTTP v1
/// send (service account + `CAMSTAR_FCM_KEY`) to deliver for real.
pub async fn recipient_device_count(state: &AppState, user_id: u64) -> usize {
    state
        .push_tokens
        .read()
        .await
        .get(&user_id)
        .map(|s| s.len())
        .unwrap_or(0)
}
