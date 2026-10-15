//! Relay tests for the WS socket loop (`handle_socket`) using a real
//! WebSocket client: chat relay + mute enforcement, signal relay +
//! validation, cross-room Join validation, and admission rejection for
//! banned / closed / nonexistent rooms (complements the HTTP-level
//! rooms_tests.rs coverage).

use axum::{body::Body, http::Request};
use camstar_api::{create_router, AppState};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::WebSocketStream;

type WsTx = futures_util::stream::SplitSink<
    WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
    WsMessage,
>;
type WsRx = futures_util::stream::SplitStream<
    WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
>;

/// Boot the router on a random loopback port; returns the port.
async fn spawn(state: AppState) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = create_router(state.clone());
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    port
}

async fn ws_connect(port: u16, room_id: u64, token: &str) -> (WsTx, WsRx) {
    let (ws, _) = tokio_tungstenite::connect_async(format!(
        "ws://127.0.0.1:{port}/ws/rooms/{room_id}?token={token}"
    ))
    .await
    .expect("ws connect");
    ws.split()
}

async fn send_json(tx: &mut WsTx, v: Value) {
    tx.send(WsMessage::Text(v.to_string().into())).await.unwrap();
}

enum Read {
    Msg(Value),
    Timeout,
    StreamEnded,
}

async fn read_msg(rx: &mut WsRx, millis: u64) -> Read {
    match tokio::time::timeout(Duration::from_millis(millis), rx.next()).await {
        Err(_) => Read::Timeout,
        Ok(None) => Read::StreamEnded,
        Ok(Some(Err(_))) => Read::StreamEnded,
        Ok(Some(Ok(m))) => match m {
            WsMessage::Text(t) => match serde_json::from_str(&t) {
                Ok(v) => Read::Msg(v),
                Err(_) => Read::StreamEnded, // malformed text: treat as dead
            },
            _ => Read::Msg(json!({"type": "_non_text"})),
        },
    }
}

fn msg_type(v: &Value) -> Option<&str> {
    v.get("type").and_then(|t| t.as_str())
}

/// Wait until a message of the given type arrives (skipping others, e.g.
/// presence updates interleaved with snapshots). Panics on timeout.
async fn wait_for(rx: &mut WsRx, expected: &str, millis: u64) -> Value {
    let deadline = Instant::now() + Duration::from_millis(millis);
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            panic!("did not receive message of type {expected}");
        }
        match read_msg(rx, remaining.as_millis() as u64).await {
            Read::Msg(v) if msg_type(&v) == Some(expected) => return v,
            Read::Msg(_) => continue, // tolerated interleaved type
            Read::Timeout => panic!("timed out waiting for {expected}"),
            Read::StreamEnded => panic!("stream ended while waiting for {expected}"),
        }
    }
}

/// Assert no message of the given type arrives within the window. Other
/// types are drained and tolerated.
async fn wait_absent(rx: &mut WsRx, absent: &str, millis: u64) {
    let deadline = Instant::now() + Duration::from_millis(millis);
    while let Some(remaining) = deadline.checked_duration_since(Instant::now()) {
        match read_msg(rx, remaining.as_millis() as u64).await {
            Read::Msg(v) => assert_ne!(
                msg_type(&v),
                Some(absent),
                "message of type {absent} must not arrive: {v}"
            ),
            _ => return,
        }
    }
}

async fn http_post(
    app: &axum::Router,
    path: String,
    token: &str,
    body: Value,
) -> (axum::http::StatusCode, Value) {
    let req = Request::post(path)
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::from(body.to_string()))
        .unwrap();
    let res = tower::ServiceExt::oneshot(app.clone(), req).await.unwrap();
    let status = res.status();
    let bytes = http_body_util::BodyExt::collect(res.into_body())
        .await
        .unwrap()
        .to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

/// Round-trip deserialization of the client envelope: distinguishes a
/// wire-format bug from a transport bug in the relay tests.
#[test]
fn client_message_wire_format() {
    let input = json!({"type": "Chat", "payload": {"text": "hi"}});
    let msg: Result<camstar_api::models::WsClientMessage, _> =
        serde_json::from_str(&input.to_string());
    match msg {
        Ok(camstar_api::models::WsClientMessage::Chat { text }) => assert_eq!(text, "hi"),
        Ok(other) => panic!("parsed wrong variant: {other:?}"),
        Err(err) => panic!("envelope did not parse: {err} — json={input}"),
    }
}

#[tokio::test]
async fn relay_chat_mutes_and_signals() {
    let state = AppState::new();
    let app = create_router(state.clone());
    let port = spawn(state.clone()).await;

    let host = state.create_user("+918100000001", 0).await;
    state.grant_test_token(&format!("tok_{host}_t"), host).await;
    let viewer = state.create_user("+918100000002", 0).await;
    state.grant_test_token(&format!("tok_{viewer}_t"), viewer).await;
    let host_token = format!("tok_{host}_t");
    let viewer_token = format!("tok_{viewer}_t");

    let (status, body) = http_post(
        &app,
        "/api/v1/rooms/create".into(),
        &host_token,
        json!({"title": "WS Room", "category_id": "hot"}),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK, "create failed: {body}");
    let room_id = body["room_id"].as_u64().unwrap();

    // Host connects + joins → snapshot; viewer connects + joins → snapshot.
    let (mut host_tx, mut host_rx) = ws_connect(port, room_id, &host_token).await;
    send_json(
        &mut host_tx,
        json!({"type": "Join", "payload": {"room_id": room_id, "user_id": host}}),
    )
    .await;
    wait_for(&mut host_rx, "RoomState", 2000).await;

    let (mut viewer_tx, mut viewer_rx) = ws_connect(port, room_id, &viewer_token).await;
    send_json(
        &mut viewer_tx,
        json!({"type": "Join", "payload": {"room_id": room_id, "user_id": viewer}}),
    )
    .await;
    wait_for(&mut viewer_rx, "RoomState", 2000).await;

    // The viewer's socket-open presence reaches the host as ViewerCount.
    let presence = wait_for(&mut host_rx, "ViewerCount", 2000).await;
    assert_eq!(presence["payload"]["viewers_count"], 1);

    // Chat flows.
    send_json(
        &mut viewer_tx,
        json!({"type": "Chat", "payload": {"text": "hello room"}}),
    )
    .await;
    let chat = wait_for(&mut host_rx, "ChatBroadcast", 2000).await;
    assert_eq!(chat["payload"]["text"], "hello room");
    assert_eq!(chat["payload"]["user_id"], viewer);

    // Host mutes the viewer at HTTP level; the relay drops their chat.
    let (status, _) = http_post(
        &app,
        format!("/api/v1/rooms/{room_id}/moderate"),
        &host_token,
        json!({"target_user_id": viewer, "action": "mute_chat"}),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK);

    send_json(
        &mut viewer_tx,
        json!({"type": "Chat", "payload": {"text": "should not relay"}}),
    )
    .await;
    wait_absent(&mut host_rx, "ChatBroadcast", 700).await;

    // Liveness probe: neither socket lost the relay mid-mute.
    send_json(&mut viewer_tx, json!({"type": "Ping"})).await;
    wait_for(&mut host_rx, "Pong", 2000).await;

    // Unmute restores chat.
    let (status, _) = http_post(
        &app,
        format!("/api/v1/rooms/{room_id}/moderate"),
        &host_token,
        json!({"target_user_id": viewer, "action": "unmute_chat"}),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK);

    send_json(
        &mut viewer_tx,
        json!({"type": "Chat", "payload": {"text": "back again"}}),
    )
    .await;
    let chat = wait_for(&mut host_rx, "ChatBroadcast", 2000).await;
    assert_eq!(chat["payload"]["text"], "back again");

    // Signal relay: host offer targeted at the viewer arrives.
    send_json(
        &mut host_tx,
        json!({
            "type": "RtcSignal",
            "payload": {"target_user_id": viewer, "kind": "offer", "sdp": "OFFER-SDP"}
        }),
    )
    .await;
    let sig = wait_for(&mut viewer_rx, "RtcSignalRelay", 2000).await;
    assert_eq!(sig["payload"]["sdp"], "OFFER-SDP");
    assert_eq!(sig["payload"]["from_user_id"], host);

    // Unknown kinds and empty sdp are dropped at the relay.
    send_json(
        &mut viewer_tx,
        json!({"type": "RtcSignal", "payload": {"target_user_id": host, "kind": "weird_kind", "sdp": "X"}}),
    )
    .await;
    wait_absent(&mut host_rx, "RtcSignalRelay", 500).await;

    send_json(
        &mut viewer_tx,
        json!({"type": "RtcSignal", "payload": {"target_user_id": host, "kind": "answer", "sdp": ""}}),
    )
    .await;
    wait_absent(&mut host_rx, "RtcSignalRelay", 500).await;

    // Cross-room Join: mismatched payload room_id produces no snapshot.
    send_json(
        &mut viewer_tx,
        json!({"type": "Join", "payload": {"room_id": 424242, "user_id": viewer}}),
    )
    .await;
    wait_absent(&mut viewer_rx, "RoomState", 500).await;
}

#[tokio::test]
async fn admission_rejection_and_room_closed() {
    let state = AppState::new();
    let app = create_router(state.clone());
    let port = spawn(state.clone()).await;

    let host = state.create_user("+918200000001", 0).await;
    state.grant_test_token(&format!("tok_{host}_t"), host).await;
    let viewer = state.create_user("+918200000002", 0).await;
    state.grant_test_token(&format!("tok_{viewer}_t"), viewer).await;
    let host_token = format!("tok_{host}_t");
    let viewer_token = format!("tok_{viewer}_t");

    // Nonexistent room upgrade must fail.
    let r = tokio_tungstenite::connect_async(format!(
        "ws://127.0.0.1:{port}/ws/rooms/123456?token={viewer_token}"
    ))
    .await;
    assert!(r.is_err(), "nonexistent room must refuse upgrades");

    let (status, body) = http_post(
        &app,
        "/api/v1/rooms/create".into(),
        &host_token,
        json!({"title": "C", "category_id": "hot"}),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK, "create failed: {body}");
    let room_id = body["room_id"].as_u64().unwrap();

    let (mut viewer_tx, mut viewer_rx) = ws_connect(port, room_id, &viewer_token).await;
    send_json(
        &mut viewer_tx,
        json!({"type": "Join", "payload": {"room_id": room_id, "user_id": viewer}}),
    )
    .await;
    wait_for(&mut viewer_rx, "RoomState", 2000).await;

    // Ban via HTTP; the connected socket learns from the broadcast.
    let (status, _) = http_post(
        &app,
        format!("/api/v1/rooms/{room_id}/moderate"),
        &host_token,
        json!({"target_user_id": viewer, "action": "ban_room"}),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK);

    let kicked = wait_for(&mut viewer_rx, "KickedFromRoom", 2000).await;
    assert_eq!(kicked["payload"]["user_id"], viewer);
    assert_eq!(kicked["payload"]["banned"], true);

    // Banned user's fresh upgrade is refused at the door.
    let r = tokio_tungstenite::connect_async(format!(
        "ws://127.0.0.1:{port}/ws/rooms/{room_id}?token={viewer_token}"
    ))
    .await;
    assert!(r.is_err(), "banned user upgrade must be refused");

    // Close the room; the connected socket receives RoomClosed.
    let (status, _) = http_post(
        &app,
        format!("/api/v1/rooms/{room_id}/close"),
        &host_token,
        json!({}),
    )
    .await;
    assert_eq!(status, axum::http::StatusCode::OK);

    wait_for(&mut viewer_rx, "RoomClosed", 2000).await;

    // Upgrades to the closed room are refused.
    let r = tokio_tungstenite::connect_async(format!(
        "ws://127.0.0.1:{port}/ws/rooms/{room_id}?token={host_token}"
    ))
    .await;
    assert!(r.is_err(), "closed room upgrade must be refused");
}
