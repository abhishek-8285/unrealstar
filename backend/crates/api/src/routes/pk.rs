use axum::{
    extract::{Path, State},
    http::HeaderMap,
    Json,
};
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{
        PkInviteRequest, PkMatchRequest, PkRespondRequest, PkStatusResponse, WsServerMessage,
    },
    state::AppState,
};

/// Push the current PK score to every room hosted by either duelist.
/// No-op when neither host has a live room with a broadcast channel.
pub(crate) async fn broadcast_pk_state(state: &AppState, pk: &PkStatusResponse) {
    let room_ids: Vec<u64> = {
        let rooms = state.rooms.read().await;
        rooms
            .values()
            .filter(|r| r.host_id == pk.host_a_id || r.host_id == pk.host_b_id)
            .map(|r| r.room_id)
            .collect()
    };
    if room_ids.is_empty() {
        return;
    }
    let ws_rooms = state.ws_rooms.read().await;
    for rid in room_ids {
        if let Some(tx) = ws_rooms.get(&rid) {
            let _ = tx.send(WsServerMessage::PkStateChanged {
                pk_id: pk.pk_id.clone(),
                points_a: pk.host_a_points,
                points_b: pk.host_b_points,
            });
        }
    }
}

/// Best-effort nickname for the room badge; falls back to Host_<id>.
async fn nickname_of(state: &AppState, user_id: u64) -> String {
    let users = state.users.read().await;
    users
        .get(&user_id)
        .map(|u| u.nickname.clone())
        .unwrap_or_else(|| format!("Host_{user_id}"))
}

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

    let pk_snapshot = pk.clone();
    drop(pks);

    if pk_snapshot.status == "in_battle" {
        // Badge both duelists' rooms so the Live grid shows the battle, and
        // push the opening 0–0 score so connected viewers render the board
        // without polling get_status.
        let opp_a = nickname_of(&state, pk_snapshot.host_b_id).await;
        let opp_b = nickname_of(&state, pk_snapshot.host_a_id).await;
        {
            let mut rooms = state.rooms.write().await;
            for room in rooms.values_mut().filter(|r| {
                r.host_id == pk_snapshot.host_a_id || r.host_id == pk_snapshot.host_b_id
            }) {
                room.is_pk = true;
                room.pk_opponent = Some(if room.host_id == pk_snapshot.host_a_id {
                    opp_a.clone()
                } else {
                    opp_b.clone()
                });
            }
        }
        broadcast_pk_state(&state, &pk_snapshot).await;
    }
    // Declines need no broadcast: the responder learns from this response
    // and the inviter polls get_status. A PkStateChanged with 0–0 would
    // masquerade as a battle start.

    Ok(Json(pk_snapshot))
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
