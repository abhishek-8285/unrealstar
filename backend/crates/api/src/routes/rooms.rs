use axum::{
    extract::{
        ws::{Message, WebSocket},
        Path, Query, State, WebSocketUpgrade,
    },
    http::HeaderMap,
    response::IntoResponse,
    Json,
};
use camstar_roles::{RoomRoster, SeatRole};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::collections::HashSet;
use tokio::sync::broadcast;

use crate::{
    error::ApiError,
    models::{
        CloseRoomResponse, CreateRoomRequest, CreateRoomResponse, JoinRoomResponse,
        ModerateRequest, SeatActionRequest, SeatInfo, SeatRequest, WsClientMessage,
        WsServerMessage,
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
            return Err(ApiError::Forbidden(
                "Account is not eligible to publish".into(),
            ));
        }
    }

    let mut rooms = state.rooms.write().await;
    // Monotonic id: max + 1, never collides after deletes/restarts-in-session.
    let room_id = rooms.keys().max().map(|m| m + 1).unwrap_or(1001);

    let roster = RoomRoster::new(room_id, user_id);

    // Cover URLs are echoed into every room card: cap length and require an
    // http(s) URL so a malicious string cannot smuggle javascript: payloads
    // or megabyte blobs into the feed.
    let cover_image_url = match req.cover_image_url {
        None => format!("{}/assets/covers/1.jpg", crate::public_origin()),
        Some(url) => {
            let valid =
                url.len() <= 512 && (url.starts_with("https://") || url.starts_with("http://"));
            if !valid {
                return Err(ApiError::BadRequest(
                    "cover_image_url must be an http(s) URL under 512 chars".into(),
                ));
            }
            url
        }
    };

    let session = RoomSession {
        room_id,
        title: req.title.clone(),
        host_id: user_id,
        category: req.category_id,
        cover_image_url,
        roster,
        is_active: true,
        peak_viewers: 1,
        diamonds_earned: 0,
        is_pk: false,
        pk_opponent: None,
        rank_tag: None,
        host_badge: None,
        present: HashSet::new(),
        viewers_base: 0,
        started_at: chrono::Utc::now(),
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

    {
        let bans = state.room_bans.read().await;
        if bans
            .get(&room_id)
            .map(|s| s.contains(&user_id))
            .unwrap_or(false)
        {
            return Err(ApiError::Forbidden("You are banned from this room".into()));
        }
    }

    let mut rooms = state.rooms.write().await;
    let room = rooms
        .get_mut(&room_id)
        .ok_or_else(|| ApiError::NotFound("Room not found".into()))?;

    if !room.is_active {
        return Err(ApiError::BadRequest("Room is already closed".into()));
    }

    // Audience cap: the host is never counted and always admits; everyone
    // else is rejected once tracked presence hits the ceiling so one viral
    // room cannot grow presence and broadcast fan-out without bound.
    if user_id != room.host_id && room.at_viewer_capacity() {
        return Err(ApiError::Forbidden("Room is full".into()));
    }

    // Presence-tracked counting: a user who joined and left repeatedly cannot
    // inflate the count, and a user who never joined cannot decrement it.
    let joined = room.mark_present(user_id);

    let users = state.users.read().await;
    let host_profile =
        users
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

    let active_seats: Vec<SeatInfo> = (0u8..15)
        .filter_map(|slot| {
            room.roster.seat(slot).map(|seat| SeatInfo {
                slot: seat.slot + 1,
                occupant_id: seat.occupant,
                nickname: format!("Seat_{}", seat.occupant),
                avatar_url: format!(
                    "{}/assets/avatars/a{}.jpg",
                    crate::public_origin(),
                    (seat.occupant % 10) + 1
                ),
                is_muted: seat.role == camstar_roles::SeatRole::Moderator,
            })
        })
        .collect();

    let mut response = JoinRoomResponse {
        room_id,
        title: room.title.clone(),
        host_id: room.host_id,
        host_nickname: host_profile.nickname,
        rtc_token: format!("rtc_tok_{room_id}_{user_id}"),
        viewers_count: room.roster.viewer_count(),
        active_seats,
        ws_url: format!("wss://api.camstar.live/ws/rooms/{room_id}"),
        is_following: false,
        is_pk: room.is_pk,
    };
    drop(users);
    drop(rooms);

    // Follow state lives behind a different lock; read it after releasing the
    // room so the two maps are never acquired in opposite orders.
    response.is_following = {
        let follows = state.follows.read().await;
        follows
            .get(&user_id)
            .map(|set| set.contains(&response.host_id))
            .unwrap_or(false)
    };

    // Broadcast only after the room lock is released: the helper takes a read
    // lock on the same map.
    if joined {
        broadcast_viewer_count(&state, room_id).await;
    }

    Ok(Json(response))
}

/// Push the zero-delta `ViewerCount` message to every socket in `room_id`.
async fn broadcast_viewer_count(state: &AppState, room_id: u64) {
    let viewers = {
        let rooms = state.rooms.read().await;
        rooms.get(&room_id).map(|r| r.viewers_count())
    };
    let Some(viewers) = viewers else { return };
    let ws_rooms = state.ws_rooms.read().await;
    if let Some(tx) = ws_rooms.get(&room_id) {
        let _ = tx.send(WsServerMessage::ViewerCount {
            viewers_count: viewers,
        });
    }
}

/// Tell the room a user left at host level (kick or ban). Used by seat_action
/// and moderate so clients can drop their UI state for that user.
async fn broadcast_kicked(state: &AppState, room_id: u64, user_id: u64, banned: bool) {
    let ws_rooms = state.ws_rooms.read().await;
    if let Some(tx) = ws_rooms.get(&room_id) {
        let _ = tx.send(WsServerMessage::KickedFromRoom { user_id, banned });
    }
    broadcast_viewer_count(state, room_id).await;
}

/// Fire a broadcast into a room if the channel exists; no-op otherwise
/// (rooms closed before the WS layer create no channel).
async fn broadcast_to_room(state: &AppState, room_id: u64, msg: WsServerMessage) {
    let ws_rooms = state.ws_rooms.read().await;
    if let Some(tx) = ws_rooms.get(&room_id) {
        let _ = tx.send(msg);
    }
}

/// RoomState snapshot payload (viewers + seat map) for a room session; used
/// by Join handling and Lagged recovery in handle_socket.
fn room_state_snapshot(room: &RoomSession) -> WsServerMessage {
    let seats = (0u8..15)
        .filter_map(|slot| {
            room.roster.seat(slot).map(|s| SeatInfo {
                slot: s.slot + 1,
                occupant_id: s.occupant,
                nickname: format!("Seat_{}", s.occupant),
                avatar_url: format!(
                    "{}/assets/avatars/a{}.jpg",
                    crate::public_origin(),
                    (s.occupant % 10) + 1
                ),
                is_muted: s.role == camstar_roles::SeatRole::Moderator,
            })
        })
        .collect();
    WsServerMessage::RoomState {
        viewers_count: room.viewers_count(),
        seats,
    }
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

    // Only decrement when the user actually holds presence: without this a
    // user could POST leave N times and drive other viewers off the counter.
    let mut changed = false;
    {
        let mut rooms = state.rooms.write().await;
        if let Some(room) = rooms.get_mut(&room_id) {
            changed = room.mark_absent(user_id);
            let _ = room.roster.leave(user_id);
        }
    }

    if changed {
        broadcast_viewer_count(&state, room_id).await;
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
        return Err(ApiError::Forbidden(
            "Only the host can close the room".into(),
        ));
    }

    room.is_active = false;
    // Everyone present is gone at once: clear presence and let sockets learn
    // via the RoomClosed broadcast below instead of staying connected.
    room.clear_present();
    let room_id = room.room_id;
    let peak_viewers = room.peak_viewers;
    let diamonds_earned = room.diamonds_earned;
    // Real stream length, not a constant: the ended screen shows this.
    let duration_secs = chrono::Utc::now()
        .signed_duration_since(room.started_at)
        .num_seconds()
        .max(0) as u64;

    // Update user state
    {
        let mut users = state.users.write().await;
        if let Some(user) = users.get_mut(&user_id) {
            user.is_live = false;
            user.live_room_id = None;
        }
    }
    drop(rooms);

    // Mutes belong to the room; drop them with it.
    {
        let mut mutes = state.chat_mutes.write().await;
        mutes.remove(&room_id);
    }

    broadcast_to_room(&state, room_id, WsServerMessage::RoomClosed).await;

    Ok(Json(CloseRoomResponse {
        duration_secs,
        peak_viewers,
        diamonds_earned,
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

    if !(1..=15).contains(&req.slot) {
        return Err(ApiError::BadRequest("slot must be 1..15".into()));
    }
    let slot_idx = req.slot - 1;
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

    match req.action.as_str() {
        "kick" => {
            room.roster
                .leave(req.target_user_id)
                .map_err(ApiError::RoomDenial)?;
            // Presence-driven count, one source of truth (was: silent no-dec).
            room.mark_absent(req.target_user_id);
            drop(rooms);
            broadcast_kicked(&state, room_id, req.target_user_id, false).await;
            return Ok(Json(serde_json::json!({
                "success": true,
                "target_user_id": req.target_user_id,
                "action": req.action
            })));
        }
        "mute_mic" => {
            // Muted = Moderator role (cannot gift, listed as muted).
            room.roster
                .set_role(req.target_user_id, camstar_roles::SeatRole::Moderator)
                .map_err(ApiError::RoomDenial)?;
        }
        "unmute_mic" => {
            room.roster
                .set_role(req.target_user_id, camstar_roles::SeatRole::Guest)
                .map_err(ApiError::RoomDenial)?;
        }
        "approve" => {
            // Approve a pending request: seat them as CoHost if not seated,
            // otherwise promote the existing seat.
            if room.roster.seat_of(req.target_user_id).is_none() {
                // Host must supply slot via request_seat in this build; without
                // a slot we cannot place them — report clearly.
                return Err(ApiError::BadRequest(
                    "approve requires the guest to hold a seat request; use seats/request first"
                        .into(),
                ));
            }
            room.roster
                .set_role(req.target_user_id, camstar_roles::SeatRole::CoHost)
                .map_err(ApiError::RoomDenial)?;
        }
        "reject" => {
            // Reject = ensure they hold no seat.
            let _ = room.roster.leave(req.target_user_id);
        }
        _ => {
            return Err(ApiError::BadRequest(
                "Unknown seat action (approve/reject/kick/mute_mic/unmute_mic)".into(),
            ))
        }
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

    let mut rooms = state.rooms.write().await;
    let room = rooms
        .get_mut(&room_id)
        .ok_or_else(|| ApiError::NotFound("Room not found".into()))?;

    if room.host_id != user_id {
        return Err(ApiError::Forbidden(
            "Only host can perform moderation".into(),
        ));
    }

    match req.action.as_str() {
        "kick_room" => {
            let _ = room.roster.leave(req.target_user_id);
            room.mark_absent(req.target_user_id);
            drop(rooms);
            broadcast_kicked(&state, room_id, req.target_user_id, false).await;
        }
        "ban_room" => {
            let _ = room.roster.leave(req.target_user_id);
            room.mark_absent(req.target_user_id);
            state
                .room_bans
                .write()
                .await
                .entry(room_id)
                .or_default()
                .insert(req.target_user_id);
            drop(rooms);
            // Also lift the WS connection: the banned client learns from the
            // broadcast and closes its own socket.
            broadcast_kicked(&state, room_id, req.target_user_id, true).await;
        }
        "mute_chat" => {
            // Durable mute list enforced by the WS chat relay (see
            // handle_socket); the Moderator seat role stays decorative.
            // Drop the room lock first: the WS path reads chat_mutes and then
            // rooms in that order, so the reverse order here can deadlock.
            drop(rooms);
            state
                .chat_mutes
                .write()
                .await
                .entry(room_id)
                .or_default()
                .insert(req.target_user_id);
        }
        "unmute_chat" => {
            // Same lock-order note as mute_chat above.
            drop(rooms);
            state
                .chat_mutes
                .write()
                .await
                .entry(room_id)
                .or_default()
                .remove(&req.target_user_id);
        }
        _ => {
            return Err(ApiError::BadRequest(
                "Unknown moderation action (mute_chat/unmute_chat/kick_room/ban_room)".into(),
            ))
        }
    }

    Ok(Json(serde_json::json!({
        "success": true,
        "target_user_id": req.target_user_id,
        "action": req.action
    })))
}

/// Query-string carrier for the access token on the WS upgrade request
/// (browsers cannot set an Authorization header on a WebSocket handshake).
#[derive(Debug, Deserialize)]
pub struct WsAuthQuery {
    pub token: Option<String>,
}

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    Path(room_id): Path<u64>,
    State(state): State<AppState>,
    Query(query): Query<WsAuthQuery>,
) -> impl IntoResponse {
    // Browsers cannot set Authorization headers on WS upgrades; accept the
    // access token via `?token=` (same scheme, different carrier).
    let Some(token) = query.token else {
        return ApiError::Unauthorized("Missing or invalid token".to_string()).into_response();
    };
    let auth_header = format!("Bearer {token}");
    let Some(user_id) = state.authenticate_header(Some(&auth_header)).await else {
        return ApiError::Unauthorized("Missing or invalid token".to_string()).into_response();
    };

    // Room-level admission checks: a token alone used to be enough to chat in
    // room the user was banned from, or in a room the host already closed.
    {
        let bans = state.room_bans.read().await;
        if bans
            .get(&room_id)
            .map(|s| s.contains(&user_id))
            .unwrap_or(false)
        {
            return ApiError::Forbidden("You are banned from this room".to_string())
                .into_response();
        }
    }
    {
        let rooms = state.rooms.read().await;
        match rooms.get(&room_id) {
            Some(room) if room.is_active => {
                // Same ceiling as HTTP join: a full room rejects the socket
                // upgrade instead of admitting a viewer the HTTP path refused.
                if user_id != room.host_id && room.at_viewer_capacity() {
                    return ApiError::Forbidden("Room is full".to_string()).into_response();
                }
            }
            Some(_) => {
                return ApiError::BadRequest("Room is already closed".to_string()).into_response()
            }
            None => return ApiError::NotFound("Room not found".to_string()).into_response(),
        }
    }

    ws.on_upgrade(move |socket| handle_socket(socket, room_id, state, user_id))
        .into_response()
}

async fn handle_socket(socket: WebSocket, room_id: u64, state: AppState, user_id: u64) {
    let (mut sender, mut receiver) = socket.split();

    // Subscribe to the room broadcast channel, creating it lazily: seed rooms
    // and rooms created before the WS layer existed have no channel yet, and
    // without this every socket to them would close immediately.
    let mut rx = {
        let mut ws_rooms = state.ws_rooms.write().await;
        ws_rooms
            .entry(room_id)
            .or_insert_with(|| {
                let (tx, _rx) = broadcast::channel(100);
                tx
            })
            .subscribe()
    };

    // Presence counting for WS-only audiences: a socket open is presence.
    // The host is excluded inside mark_present, and the socket close below
    // undoes it, so a socket that half-dies mid-negotiation cannot leak a
    // permanent viewer. The cap is re-checked under the write lock: two
    // sockets can pass upgrade admission together, but only one wins here —
    // the loser is closed with a room-full policy code. The host and users
    // already counted in presence always admit (a second socket from the
    // same user adds no count under set semantics).
    enum Admission {
        Ok(bool),
        Gone,
        Full,
    }
    let admission = {
        let mut rooms = state.rooms.write().await;
        match rooms.get_mut(&room_id) {
            None => Admission::Gone,
            Some(r) if user_id == r.host_id || r.present.contains(&user_id) => {
                Admission::Ok(r.mark_present(user_id))
            }
            Some(r) if r.at_viewer_capacity() => Admission::Full,
            Some(r) => Admission::Ok(r.mark_present(user_id)),
        }
    };
    match admission {
        Admission::Ok(changed) => {
            if changed {
                broadcast_viewer_count(&state, room_id).await;
            }
        }
        Admission::Gone => {
            // No room (and only the lazy broadcast channel): plain close.
            let _ = sender.send(Message::Close(None)).await;
            return;
        }
        Admission::Full => {
            use axum::extract::ws::{close_code, CloseFrame};
            let _ = sender
                .send(Message::Close(Some(CloseFrame {
                    code: close_code::POLICY,
                    reason: "Room is full".into(),
                })))
                .await;
            return;
        }
    }

    // Forward broadcast messages to this client. The socket's own signals
    // are suppressed here: without this the relay echoes every RtcSignal
    // back to its sender (the sender is also a subscriber), and clients
    // relied on their own filtering to survive their own offer.
    let state_for_send = state.clone();
    let mut send_task = tokio::spawn(async move {
        loop {
            match rx.recv().await {
                Ok(WsServerMessage::RtcSignalRelay {
                    ref from_user_id, ..
                }) if *from_user_id == user_id => continue,
                Ok(msg) => {
                    if let Ok(json_str) = serde_json::to_string(&msg) {
                        if sender.send(Message::Text(json_str.into())).await.is_err() {
                            break;
                        }
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    // The client fell >capacity messages behind. Replay of the
                    // dropped window is impossible; heal instead by sending a
                    // fresh RoomState snapshot and keep the connection (the
                    // old code silently closed the socket on the first Lagged).
                    let snapshot = {
                        let rooms = state_for_send.rooms.read().await;
                        rooms.get(&room_id).map(room_state_snapshot)
                    };
                    if let Some(snapshot) = snapshot {
                        if let Ok(json_str) = serde_json::to_string(&snapshot) {
                            if sender.send(Message::Text(json_str.into())).await.is_err() {
                                break;
                            }
                        }
                    }
                }
                Err(broadcast::error::RecvError::Closed) => break,
            }
        }
    });

    // Receive messages from this client and broadcast
    let state_clone = state.clone();
    let mut recv_task = tokio::spawn(async move {
        while let Some(msg) = receiver.next().await {
            let text = match msg {
                Ok(Message::Text(text)) => text,
                // Control frames and binary blobs must not kill the socket.
                Ok(Message::Close(_)) => break,
                Ok(_) => continue,
                Err(_) => break,
            };
            if let Ok(client_msg) = serde_json::from_str::<WsClientMessage>(&text) {
                match client_msg {
                    WsClientMessage::Chat { text } => {
                        // Mute enforcement lives at the relay: chat_mutes is
                        // the durable mute list, and a Moderator-role seat is
                        // also chat-silent.
                        let mut muted = {
                            let mutes = state_clone.chat_mutes.read().await;
                            mutes
                                .get(&room_id)
                                .map(|m| m.contains(&user_id))
                                .unwrap_or(false)
                        };
                        if !muted {
                            let rooms = state_clone.rooms.read().await;
                            muted = rooms
                                .get(&room_id)
                                .and_then(|r| r.roster.seat_of(user_id))
                                .map(|s| s.role == camstar_roles::SeatRole::Moderator)
                                .unwrap_or(false);
                        }
                        if muted {
                            continue;
                        }
                        let nickname = {
                            let users = state_clone.users.read().await;
                            users
                                .get(&user_id)
                                .map(|u| u.nickname.clone())
                                .unwrap_or_else(|| format!("User_{user_id}"))
                        };
                        let ws_rooms = state_clone.ws_rooms.read().await;
                        if let Some(tx) = ws_rooms.get(&room_id) {
                            let _ = tx.send(WsServerMessage::ChatBroadcast {
                                user_id,
                                nickname,
                                text,
                            });
                        }
                    }
                    WsClientMessage::Join {
                        room_id: join_room, ..
                    } => {
                        // The URL path is the authenticated room; a mismatched
                        // payload room_id is ignored outright. Trusting it let
                        // a client pull another room's snapshot (viewers,
                        // seats) into this channel.
                        if join_room != room_id {
                            continue;
                        }
                        let snapshot = {
                            let rooms = state_clone.rooms.read().await;
                            rooms.get(&room_id).map(room_state_snapshot)
                        };
                        if let Some(snapshot) = snapshot {
                            let ws_rooms = state_clone.ws_rooms.read().await;
                            if let Some(tx) = ws_rooms.get(&room_id) {
                                let _ = tx.send(snapshot);
                            }
                        }
                    }
                    WsClientMessage::SeatSignal { is_muted } => {
                        let slot = {
                            let rooms = state_clone.rooms.read().await;
                            rooms
                                .get(&room_id)
                                .and_then(|r| r.roster.seat_of(user_id))
                                .map(|s| s.slot + 1)
                                .unwrap_or(0)
                        };
                        let ws_rooms = state_clone.ws_rooms.read().await;
                        if let Some(tx) = ws_rooms.get(&room_id) {
                            let _ = tx.send(WsServerMessage::SeatUpdated {
                                slot,
                                occupant_id: user_id,
                                is_muted,
                            });
                        }
                    }
                    WsClientMessage::Ping => {
                        let ws_rooms = state_clone.ws_rooms.read().await;
                        if let Some(tx) = ws_rooms.get(&room_id) {
                            let _ = tx.send(WsServerMessage::Pong);
                        }
                    }
                    WsClientMessage::RtcSignal {
                        target_user_id,
                        kind,
                        sdp,
                    } => {
                        // Relay-only: validate the envelope, never the SDP.
                        // 16 KiB cap keeps one client from bloating the room
                        // broadcast channel.
                        if !matches!(kind.as_str(), "offer" | "answer" | "ice" | "request") {
                            continue;
                        }
                        if sdp.is_empty() || sdp.len() > 16 * 1024 {
                            continue;
                        }
                        let ws_rooms = state_clone.ws_rooms.read().await;
                        if let Some(tx) = ws_rooms.get(&room_id) {
                            let _ = tx.send(WsServerMessage::RtcSignalRelay {
                                from_user_id: user_id,
                                target_user_id,
                                kind,
                                sdp,
                            });
                        }
                    }
                }
            }
        }
    });

    tokio::select! {
        _ = (&mut send_task) => recv_task.abort(),
        _ = (&mut recv_task) => send_task.abort(),
    }

    // Socket is done: drop presence so the reported count reflects reality,
    // mirroring the mark_present at open.
    {
        let mut rooms = state.rooms.write().await;
        if let Some(room) = rooms.get_mut(&room_id) {
            room.mark_absent(user_id);
        }
    }
    broadcast_viewer_count(&state, room_id).await;
}
