//! End-to-end HTTP tests for the rooms layer: presence-tracked viewer
//! counting, leave-abuse protection, ban/kick/mute flows, closed-room
//! rejection, and the broadcast channel messages clients rely on.
//!
//! The WS socket loop itself is not exercised here (a real upgrade needs a
//! WS client); the admission checks and room state it reads are verified
//! through the state API and the HTTP equivalents.

use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use camstar_api::{create_router, AppState};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;

async fn json_response(res: axum::response::Response) -> (StatusCode, Value) {
    let status = res.status();
    let body_bytes = res.into_body().collect().await.unwrap().to_bytes();
    let val: Value = serde_json::from_slice(&body_bytes).unwrap_or(json!({}));
    (status, val)
}

async fn post_json(
    app: &axum::Router,
    path: &str,
    token: &str,
    body: Value,
) -> (StatusCode, Value) {
    let req = Request::post(path)
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::from(body.to_string()))
        .unwrap();
    json_response(app.clone().oneshot(req).await.unwrap()).await
}

/// Drop every pending broadcast currently buffered in the room channel.
fn drain(
    rx: &mut tokio::sync::broadcast::Receiver<camstar_api::models::WsServerMessage>,
) -> Vec<camstar_api::models::WsServerMessage> {
    let mut got = Vec::new();
    while let Ok(msg) = rx.try_recv() {
        got.push(msg);
    }
    got
}

#[tokio::test]
async fn presence_counting_and_mod_enforcement() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let host = state.create_user("+918000000001", 0).await;
    let host_token = format!("tok_{host}_test");
    state.grant_test_token(&host_token, host).await;

    let viewer = state.create_user("+918000000002", 0).await;
    let viewer_token = format!("tok_{viewer}_test");
    state.grant_test_token(&viewer_token, viewer).await;

    let other = state.create_user("+918000000003", 0).await;
    let other_token = format!("tok_{other}_test");
    state.grant_test_token(&other_token, other).await;

    // Create room
    let (status, body) =
        post_json(&app, "/api/v1/rooms/create", &host_token, json!({
            "title": "Test Room",
            "category_id": "hot"
        }))
        .await;
    assert_eq!(status, StatusCode::OK, "create failed: {body}");
    let room_id = body["room_id"].as_u64().unwrap();
    let room_path = format!("/api/v1/rooms/{room_id}");

    let mut rx = {
        let ws_rooms = state.ws_rooms.read().await;
        ws_rooms
            .get(&room_id)
            .expect("broadcast channel exists after create")
            .subscribe()
    };
    let _ = drain(&mut rx);

    // Join: 1 viewer. Duplicate join does not double count (presence is a set).
    let (status, body) = post_json(&app, &format!("{room_path}/join"), &viewer_token, json!({})).await;
    assert_eq!(status, StatusCode::OK, "join failed: {body}");
    assert_eq!(body["viewers_count"], 1);

    let (_, body) = post_json(&app, &format!("{room_path}/join"), &viewer_token, json!({})).await;
    assert_eq!(body["viewers_count"], 1, "duplicate join must not inflate count");

    // Second viewer
    let (_, body) = post_json(&app, &format!("{room_path}/join"), &other_token, json!({})).await;
    assert_eq!(body["viewers_count"], 2);

    // Leave decrements; repeated leaves are floored at real presence.
    let (status, _) = post_json(&app, &format!("{room_path}/leave"), &viewer_token, json!({})).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) = post_json(&app, &format!("{room_path}/leave"), &viewer_token, json!({})).await;
    assert_eq!(status, StatusCode::OK);

    {
        let rooms = state.rooms.read().await;
        let room = rooms.get(&room_id).unwrap();
        assert_eq!(room.viewers_count(), 1, "phantom leave must not undercount");
        assert!(!room.present.contains(&viewer), "left user must lose presence");
    }
    let _ = drain(&mut rx);

    // Kick clears presence too (was: counter math only).
    let (status, body) =
        post_json(&app, &format!("{room_path}/moderate"), &host_token, json!({
            "target_user_id": other,
            "action": "kick_room"
        }))
        .await;
    assert_eq!(status, StatusCode::OK, "kick failed: {body}");
    {
        let rooms = state.rooms.read().await;
        assert_eq!(rooms.get(&room_id).unwrap().viewers_count(), 0);
    }

    // Kick produced a KickedFromRoom broadcast (client drop contract).
    let saw_kick = drain(&mut rx).iter().any(|m| {
        matches!(
            m,
            camstar_api::models::WsServerMessage::KickedFromRoom { user_id, .. }
                if *user_id == other
        )
    });
    assert!(saw_kick, "kick_room must broadcast KickedFromRoom");

    // Ban: joins become 403, presence drops, broadcast is flagged banned.
    let (status, _) = post_json(&app, &format!("{room_path}/join"), &viewer_token, json!({})).await;
    assert_eq!(status, StatusCode::OK);
    let (status, _) =
        post_json(&app, &format!("{room_path}/moderate"), &host_token, json!({
            "target_user_id": viewer,
            "action": "ban_room"
        }))
        .await;
    assert_eq!(status, StatusCode::OK);

    {
        let bans = state.room_bans.read().await;
        assert!(bans.get(&room_id).unwrap().contains(&viewer));
        let rooms = state.rooms.read().await;
        assert_eq!(rooms.get(&room_id).unwrap().viewers_count(), 0);
    }

    let (status, body) = post_json(&app, &format!("{room_path}/join"), &viewer_token, json!({})).await;
    assert_eq!(status, StatusCode::FORBIDDEN, "banned user must not rejoin: {body}");

    let saw_ban = drain(&mut rx).iter().any(|m| {
        matches!(
            m,
            camstar_api::models::WsServerMessage::KickedFromRoom { user_id, banned }
                if *user_id == viewer && *banned
        )
    });
    assert!(saw_ban, "ban_room must broadcast KickedFromRoom(banned=true)");

    // Non-hosts cannot moderate.
    let (status, _) =
        post_json(&app, &format!("{room_path}/moderate"), &other_token, json!({
            "target_user_id": viewer,
            "action": "kick_room"
        }))
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    // Mute registers and unregisters in the enforced chat mute list.
    let (status, _) =
        post_json(&app, &format!("{room_path}/moderate"), &host_token, json!({
            "target_user_id": other,
            "action": "mute_chat"
        }))
        .await;
    assert_eq!(status, StatusCode::OK);
    {
        let mutes = state.chat_mutes.read().await;
        assert!(mutes.get(&room_id).unwrap().contains(&other));
    }

    let (status, _) =
        post_json(&app, &format!("{room_path}/moderate"), &host_token, json!({
            "target_user_id": other,
            "action": "unmute_chat"
        }))
        .await;
    assert_eq!(status, StatusCode::OK);
    {
        let mutes = state.chat_mutes.read().await;
        assert!(!mutes.get(&room_id).is_none_or(|m| m.contains(&other)));
    }

    // Close: viewers are present no more, RoomClosed is broadcast and the
    // room refuses further joins.
    let (status, body) = post_json(&app, &format!("{room_path}/close"), &host_token, json!({})).await;
    assert_eq!(status, StatusCode::OK, "close failed: {body}");

    {
        let rooms = state.rooms.read().await;
        let room = rooms.get(&room_id).unwrap();
        assert!(!room.is_active);
        assert!(room.present.is_empty(), "close must clear presence");
    }

    let closed = drain(&mut rx)
        .iter()
        .any(|m| matches!(m, camstar_api::models::WsServerMessage::RoomClosed));
    assert!(closed, "close must broadcast RoomClosed");

    let (status, _) = post_json(&app, &format!("{room_path}/join"), &other_token, json!({})).await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "closed room must refuse joins");
}
