use axum::{
    extract::{
        ws::{Message, WebSocket},
        Path, State, WebSocketUpgrade,
    },
    http::HeaderMap,
    response::IntoResponse,
    Json,
};
use camstar_roles::{RoomRoster, SeatRole};
use futures_util::{SinkExt, StreamExt};
use tokio::sync::broadcast;

use crate::{
    error::ApiError,
    models::{
        CloseRoomResponse, CreateRoomRequest, CreateRoomResponse, JoinRoomResponse,
        ModerateRequest, SeatActionRequest, SeatInfo, SeatRequest, WsClientMessage, WsServerMessage,
    },
    state::{AppState, RoomSession},
};

pub async fn create_room(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<CreateRoomRequest>,
) -> Result<Json<CreateRoomResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    // Check account eligibility to publish
    {
        let users = state.users.read().await;
        let profile = users
            .get(&user_id)
            .ok_or_else(|| ApiError::NotFound("User not found".into()))?;

        if !profile.can_publish {
            return Err(ApiError::Forbidden("Account is not eligible to publish".into()));
        }
    }

    let mut rooms = state.rooms.write().await;
    let room_id = (rooms.len() as u64) + 1001;

    let roster = RoomRoster::new(room_id, user_id);

    let session = RoomSession {
        room_id,
        title: req.title.clone(),
        host_id: user_id,
        category: req.category_id,
        cover_image_url: req
            .cover_image_url
            .unwrap_or_else(|| "https://cdn.camstar.live/covers/default.png".into()),
        roster,
        is_active: true,
        peak_viewers: 1,
        diamonds_earned: 0,
        is_pk: false,
        pk_opponent: None,
        rank_tag: None,
        host_badge: None,
    };

    rooms.insert(room_id, session);

    // Update user state to live
    {
        let mut users = state.users.write().await;
        if let Some(user) = users.get_mut(&user_id) {
            user.is_live = true;
            user.live_room_id = Some(room_id);
        }
    }

    // Ensure broadcast channel exists
    {
        let mut ws_rooms = state.ws_rooms.write().await;
        ws_rooms.entry(room_id).or_insert_with(|| {
            let (tx, _rx) = broadcast::channel(100);
            tx
        });
    }

    Ok(Json(CreateRoomResponse {
        room_id,
        title: req.title,
        rtc_token: format!("rtc_tok_{room_id}_{user_id}"),
        rtc_channel_id: format!("chan_{room_id}"),
        ws_url: format!("wss://api.camstar.live/ws/rooms/{room_id}"),
    }))
}

pub async fn join_room(
    headers: HeaderMap,
    Path(room_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<JoinRoomResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut rooms = state.rooms.write().await;
    let room = rooms
        .get_mut(&room_id)
        .ok_or_else(|| ApiError::NotFound("Room not found".into()))?;

    if !room.is_active {
        return Err(ApiError::BadRequest("Room is already closed".into()));
    }

    let viewers = room.roster.viewer_count();
    room.roster.set_viewer_count(viewers + 1);
    if room.roster.viewer_count() > room.peak_viewers {
        room.peak_viewers = room.roster.viewer_count();
    }

    let users = state.users.read().await;
    let host_profile = users
        .get(&room.host_id)
        .cloned()
        .unwrap_or_else(|| crate::models::UserProfile {
            user_id: room.host_id,
            phone: "".into(),
            nickname: format!("Host_{}", room.host_id),
            avatar_url: "".into(),
            bio: "".into(),
            gender: "".into(),
            city: "".into(),
            age: 20,
            can_publish: true,
            creator_status: camstar_roles::CreatorStatus::Viewer,
            wealth_tier: camstar_roles::Tier::Newcomer,
            vip_level: camstar_roles::VipLevel::None,
            followers_count: 0,
            following_count: 0,
            is_live: true,
            live_room_id: Some(room_id),
            likes_received: 0,
        });

    let active_seats: Vec<SeatInfo> = (1..=15)
        .filter_map(|slot| {
            room.roster.seat(slot).map(|seat| SeatInfo {
                slot: seat.slot,
                occupant_id: seat.occupant,
                nickname: format!("Seat_{}", seat.occupant),
                avatar_url: format!("https://cdn.camstar.live/avatars/{}.png", seat.occupant),
                is_muted: false,
            })
        })
        .collect();

    Ok(Json(JoinRoomResponse {
        room_id,
        title: room.title.clone(),
        host_id: room.host_id,
        host_nickname: host_profile.nickname,
        rtc_token: format!("rtc_tok_{room_id}_{user_id}"),
        viewers_count: room.roster.viewer_count(),
        active_seats,
        ws_url: format!("wss://api.camstar.live/ws/rooms/{room_id}"),
    }))
}

pub async fn leave_room(
    headers: HeaderMap,
    Path(room_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut rooms = state.rooms.write().await;
    if let Some(room) = rooms.get_mut(&room_id) {
        let viewers = room.roster.viewer_count();
        if viewers > 0 {
            room.roster.set_viewer_count(viewers - 1);
        }
        let _ = room.roster.leave(user_id);
    }

    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn close_room(
    headers: HeaderMap,
    Path(room_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<CloseRoomResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut rooms = state.rooms.write().await;
    let room = rooms
        .get_mut(&room_id)
        .ok_or_else(|| ApiError::NotFound("Room not found".into()))?;

    if room.host_id != user_id {
        return Err(ApiError::Forbidden("Only the host can close the room".into()));
    }

    room.is_active = false;

    // Update user state
    {
        let mut users = state.users.write().await;
        if let Some(user) = users.get_mut(&user_id) {
            user.is_live = false;
            user.live_room_id = None;
        }
    }

    Ok(Json(CloseRoomResponse {
        duration_secs: 1800,
        peak_viewers: room.peak_viewers,
        diamonds_earned: room.diamonds_earned,
    }))
}

pub async fn request_seat(
    headers: HeaderMap,
    Path(room_id): Path<u64>,
    State(state): State<AppState>,
    Json(req): Json<SeatRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut rooms = state.rooms.write().await;
    let room = rooms
        .get_mut(&room_id)
        .ok_or_else(|| ApiError::NotFound("Room not found".into()))?;

    let slot_idx = req.slot.saturating_sub(1);
    room.roster
        .take_seat(user_id, slot_idx, SeatRole::Guest)
        .map_err(ApiError::RoomDenial)?;

    // Broadcast seat updated
    let ws_rooms = state.ws_rooms.read().await;
    if let Some(tx) = ws_rooms.get(&room_id) {
        let _ = tx.send(WsServerMessage::SeatUpdated {
            slot: req.slot,
            occupant_id: user_id,
            is_muted: false,
        });
    }

    Ok(Json(serde_json::json!({
        "success": true,
        "slot": req.slot,
        "status": "seated"
    })))
}

pub async fn seat_action(
    headers: HeaderMap,
    Path(room_id): Path<u64>,
    State(state): State<AppState>,
    Json(req): Json<SeatActionRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut rooms = state.rooms.write().await;
    let room = rooms
        .get_mut(&room_id)
        .ok_or_else(|| ApiError::NotFound("Room not found".into()))?;

    if room.host_id != user_id {
        return Err(ApiError::Forbidden("Only host can manage seats".into()));
    }

    if req.action == "kick" {
        room.roster
            .leave(req.target_user_id)
            .map_err(ApiError::RoomDenial)?;
    }

    Ok(Json(serde_json::json!({
        "success": true,
        "target_user_id": req.target_user_id,
        "action": req.action
    })))
}

pub async fn moderate(
    headers: HeaderMap,
    Path(room_id): Path<u64>,
    State(state): State<AppState>,
    Json(req): Json<ModerateRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let rooms = state.rooms.read().await;
    let room = rooms
        .get(&room_id)
        .ok_or_else(|| ApiError::NotFound("Room not found".into()))?;

    if room.host_id != user_id {
        return Err(ApiError::Forbidden("Only host can perform moderation".into()));
    }

    Ok(Json(serde_json::json!({
        "success": true,
        "target_user_id": req.target_user_id,
        "action": req.action
    })))
}

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    Path(room_id): Path<u64>,
    State(state): State<AppState>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, room_id, state))
}

async fn handle_socket(socket: WebSocket, room_id: u64, state: AppState) {
    let (mut sender, mut receiver) = socket.split();

    // Subscribe to room broadcast channel
    let mut rx = {
        let ws_rooms = state.ws_rooms.read().await;
        if let Some(tx) = ws_rooms.get(&room_id) {
            tx.subscribe()
        } else {
            return;
        }
    };

    // Forward broadcast messages to this client
    let mut send_task = tokio::spawn(async move {
        while let Ok(msg) = rx.recv().await {
            if let Ok(json_str) = serde_json::to_string(&msg) {
                if sender.send(Message::Text(json_str.into())).await.is_err() {
                    break;
                }
            }
        }
    });

    // Receive messages from this client and broadcast
    let state_clone = state.clone();
    let mut recv_task = tokio::spawn(async move {
        while let Some(Ok(Message::Text(text))) = receiver.next().await {
            if let Ok(client_msg) = serde_json::from_str::<WsClientMessage>(&text) {
                let ws_rooms = state_clone.ws_rooms.read().await;
                if let Some(tx) = ws_rooms.get(&room_id) {
                    match client_msg {
                        WsClientMessage::Chat { text } => {
                            let _ = tx.send(WsServerMessage::ChatBroadcast {
                                user_id: 1,
                                nickname: "Viewer".into(),
                                text,
                            });
                        }
                        WsClientMessage::Ping => {
                            let _ = tx.send(WsServerMessage::Pong);
                        }
                        _ => {}
                    }
                }
            }
        }
    });

    tokio::select! {
        _ = (&mut send_task) => recv_task.abort(),
        _ = (&mut recv_task) => send_task.abort(),
    };
}
