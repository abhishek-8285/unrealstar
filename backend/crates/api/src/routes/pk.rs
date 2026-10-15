use axum::{
    extract::{Path, State},
    http::HeaderMap,
    Json,
};
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{PkInviteRequest, PkMatchRequest, PkRespondRequest, PkStatusResponse},
    state::AppState,
};

pub async fn request_match(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<PkMatchRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let _user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let ticket_id = format!("match_ticket_{}", Uuid::new_v4());

    Ok(Json(serde_json::json!({
        "match_ticket_id": ticket_id,
        "room_id": req.room_id,
        "status": "searching"
    })))
}

pub async fn send_invite(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<PkInviteRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let host_a = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let pk_id = format!("pk_{}", Uuid::new_v4());
    let pk_match = PkStatusResponse {
        pk_id: pk_id.clone(),
        host_a_id: host_a,
        host_b_id: req.to_host_id,
        host_a_points: 0,
        host_b_points: 0,
        time_remaining_secs: req.duration_secs,
        status: "invited".into(),
    };

    let mut pks = state.pks.write().await;
    pks.insert(pk_id.clone(), pk_match);

    Ok(Json(serde_json::json!({
        "invite_id": pk_id,
        "target_host_id": req.to_host_id,
        "status": "pending"
    })))
}

pub async fn respond_invite(
    headers: HeaderMap,
    Path(invite_id): Path<String>,
    State(state): State<AppState>,
    Json(req): Json<PkRespondRequest>,
) -> Result<Json<PkStatusResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let _user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut pks = state.pks.write().await;
    let pk = pks
        .get_mut(&invite_id)
        .ok_or_else(|| ApiError::NotFound("PK match not found".into()))?;

    if req.action == "accept" {
        pk.status = "in_battle".into();
    } else {
        pk.status = "declined".into();
    }

    Ok(Json(pk.clone()))
}

pub async fn get_status(
    headers: HeaderMap,
    Path(pk_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<PkStatusResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;
    let pks = state.pks.read().await;
    let pk = pks
        .get(&pk_id)
        .cloned()
        .ok_or_else(|| ApiError::NotFound("PK match not found".into()))?;

    Ok(Json(pk))
}
