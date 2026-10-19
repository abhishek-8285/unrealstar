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

#[tokio::test]
async fn test_auth_and_user_lifecycle() {
    let state = AppState::new();
    let app = create_router(state.clone());

    // 1. Send OTP
    let req = Request::post("/api/v1/auth/otp/send")
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "phone_number": "+919876543210",
                "country_code": "+91"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let session_id = body["otp_session_id"].as_str().unwrap();
    let otp_code = body["debug_otp"].as_str().unwrap().to_string();

    // 2. Verify OTP
    let req = Request::post("/api/v1/auth/otp/verify")
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "otp_session_id": session_id,
                "otp_code": otp_code,
                "device_uid": "dev_test_123"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let token = body["access_token"].as_str().unwrap().to_string();
    let refresh_token = body["refresh_token"].as_str().unwrap().to_string();
    assert_eq!(body["is_new_user"], true);

    // 3. Get Me
    let req = Request::get("/api/v1/users/me")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["can_publish"], true);

    // 4. Update Profile
    let req = Request::put("/api/v1/users/me")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "nickname": "Priya_Live",
                "bio": "Bollywood dancer & singer"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["nickname"], "Priya_Live");

    // 5. Rotate Token
    let req = Request::post("/api/v1/auth/token/refresh")
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "refresh_token": refresh_token,
                "device_uid": "dev_test_123"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["access_token"].is_string());
}

/// GET /users/{id} must carry per-caller follow state without touching the
/// UserProfile contract: present for an authenticated viewer of someone else,
/// absent for anonymous and self lookups.
#[tokio::test]
async fn test_get_user_reports_per_caller_follow_state() {
    let state = AppState::new();
    let app = create_router(state.clone());

    async fn register(app: &axum::Router, phone: &str, device: &str) -> (String, u64) {
        let req = Request::post("/api/v1/auth/otp/send")
            .header("Content-Type", "application/json")
            .body(Body::from(
                json!({ "phone_number": phone, "country_code": "+91" }).to_string(),
            ))
            .unwrap();
        let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        let req = Request::post("/api/v1/auth/otp/verify")
            .header("Content-Type", "application/json")
            .body(Body::from(
                json!({
                    "otp_session_id": body["otp_session_id"].as_str().unwrap(),
                    "otp_code": body["debug_otp"].as_str().unwrap(),
                    "device_uid": device
                })
                .to_string(),
            ))
            .unwrap();
        let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        let token = body["access_token"].as_str().unwrap().to_string();

        let req = Request::get("/api/v1/users/me")
            .header("Authorization", format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let (status, me) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        assert_eq!(status, StatusCode::OK);
        (token, me["user_id"].as_u64().unwrap())
    }

    let (token_a, user_a) = register(&app, "+919876543211", "dev_follow_a").await;
    let (_token_b, user_b) = register(&app, "+919876543212", "dev_follow_b").await;
    assert_ne!(user_a, user_b);

    // A follows B
    let req = Request::post(format!("/api/v1/social/follow/{user_b}"))
        .header("Authorization", format!("Bearer {token_a}"))
        .header("Content-Type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["is_following"], true);

    // Viewer sees the follow state on B's profile
    let req = Request::get(format!("/api/v1/users/{user_b}"))
        .header("Authorization", format!("Bearer {token_a}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["is_following"], true);
    assert!(body["nickname"].is_string());

    // Anonymous lookup: no follow state at all
    let req = Request::get(format!("/api/v1/users/{user_b}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body.get("is_following").is_none());

    // Self lookup: the key is meaningless, so it stays absent
    let req = Request::get(format!("/api/v1/users/{user_a}"))
        .header("Authorization", format!("Bearer {token_a}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body.get("is_following").is_none());
}

#[tokio::test]
async fn test_wallet_and_payin_order() {
    let state = AppState::new();
    let app = create_router(state.clone());
    let user_id = state.create_user("+919876543211", 0).await;
    let token = format!("tok_{user_id}_test");
    state.grant_test_token(&token, user_id).await;

    // 1. List packages
    let req = Request::get("/api/v1/payments/packages")
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body.as_array().unwrap().len() >= 4);

    // 2. Initial balance is 0
    let req = Request::get("/api/v1/wallet/balance")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins"], 0);

    // 3. Create Top-up Order (buying 150 coins package).
    //    Coins are NOT credited yet — only registered as pending.
    let req = Request::post("/api/v1/payments/orders/create")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "package_id": "pkg_150",
                "provider": "razorpay",
                "idempotency_key": "pay_test_order_1"
            })
            .to_string(),
        ))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins_pending"], 165); // 150 + 15 bonus
    assert!(body["upi_intent_uri"]
        .as_str()
        .unwrap()
        .starts_with("upi://pay"));
    let order_id = body["order_id"].as_str().unwrap().to_string();

    // 3b. Balance must still be 0 — coins are not spendable until the
    //     payment provider webhook confirms the order.
    let req = Request::get("/api/v1/wallet/balance")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins"], 0);

    // 4. Simulate the provider webhook confirming payment — this credits coins.
    let req = Request::post("/api/v1/payments/webhook/test-provider")
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "order_id": order_id,
                "status": "success",
                "provider_reference": "pay_ref_123"
            })
            .to_string(),
        ))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["credited"], true);

    // 4b. A duplicate webhook for the same order must be idempotent.
    let req = Request::post("/api/v1/payments/webhook/test-provider")
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "order_id": order_id,
                "status": "success",
                "provider_reference": "pay_ref_123"
            })
            .to_string(),
        ))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["credited"], false); // already processed — no double credit

    // 5. Verify balance is now 165 coins in Ledger (credited exactly once)
    let req = Request::get("/api/v1/wallet/balance")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins"], 165);
}

#[tokio::test]
async fn test_red_packet_escrow_and_claim() {
    let state = AppState::new();
    let app = create_router(state.clone());

    // Two users: dropper (1002) and claimer (1003). Seed dropper with coins.
    let dropper = state.create_user("+919876543220", 0).await;
    let claimer = state.create_user("+919876543221", 0).await;
    let dropper_token = format!("tok_{dropper}_drop");
    let claimer_token = format!("tok_{claimer}_claim");
    state.grant_test_token(&dropper_token, dropper).await;
    state.grant_test_token(&claimer_token, claimer).await;

    {
        use camstar_ledger::{Direction, EntryKind, Line, Money};
        state
            .ledger
            .post(
                EntryKind::Adjustment,
                &format!("seed_{dropper}"),
                vec![
                    Line {
                        account: camstar_api::system_clearing_account(),
                        direction: Direction::Debit,
                        amount: Money::from_minor(100).unwrap(),
                    },
                    Line {
                        account: camstar_api::user_coins_account(dropper),
                        direction: Direction::Credit,
                        amount: Money::from_minor(100).unwrap(),
                    },
                ],
            )
            .await
            .unwrap();
    }

    // 1. Drop a 100-coin, 3-split red packet.
    let req = Request::post("/api/v1/gifts/red-packet/drop")
        .header("Authorization", format!("Bearer {dropper_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": 1,
                "total_coins": 100,
                "split_count": 3,
                "idempotency_key": "drop_test_1"
            })
            .to_string(),
        ))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let packet_id = body["packet_id"].as_str().unwrap().to_string();

    // Dropper's balance is now 0 — coins are escrowed in clearing.
    let req = Request::get("/api/v1/wallet/balance")
        .header("Authorization", format!("Bearer {dropper_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins"], 0);

    // 2. Claimer claims — gets an equal split (100/3 = 33).
    let req = Request::post(format!("/api/v1/gifts/red-packet/{packet_id}/claim"))
        .header("Authorization", format!("Bearer {claimer_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let first_claim = body["coins_claimed"].as_u64().unwrap();
    assert_eq!(first_claim, 33);
    let remaining = body["remaining_splits"].as_u64().unwrap();
    assert_eq!(remaining, 2);

    // Claimer balance reflects the claim.
    let req = Request::get("/api/v1/wallet/balance")
        .header("Authorization", format!("Bearer {claimer_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins"], first_claim);

    // 3. Same claimer tries again — must be rejected (one claim per user).
    let req = Request::post(format!("/api/v1/gifts/red-packet/{packet_id}/claim"))
        .header("Authorization", format!("Bearer {claimer_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, _body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::CONFLICT);

    // 4. Ledger remains zero-sum after escrow + claim.
    let total = state.ledger.total_of_all_accounts().await.unwrap();
    assert_eq!(total.to_minor(), 0);
}

#[tokio::test]
async fn test_room_lifecycle_and_seating() {
    let state = AppState::new();
    let app = create_router(state.clone());
    let host_id = state.create_user("+919876543212", 0).await;
    let host_token = format!("tok_{host_id}_test");
    state.grant_test_token(&host_token, host_id).await;

    let guest_id = state.create_user("+919876543213", 0).await;
    let guest_token = format!("tok_{guest_id}_test");
    state.grant_test_token(&guest_token, guest_id).await;

    // 1. Host creates room
    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "title": "Bollywood Singing Night",
                "category_id": "music"
            })
            .to_string(),
        ))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let room_id = body["room_id"].as_u64().unwrap();

    // 2. Guest joins room
    let req = Request::post(format!("/api/v1/rooms/{room_id}/join"))
        .header("Authorization", format!("Bearer {guest_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["viewers_count"], 1);

    // 3. Guest takes seat #1
    let req = Request::post(format!("/api/v1/rooms/{room_id}/seats/request"))
        .header("Authorization", format!("Bearer {guest_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "slot": 1
            })
            .to_string(),
        ))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "seated");

    // 4. Host closes room
    let req = Request::post(format!("/api/v1/rooms/{room_id}/close"))
        .header("Authorization", format!("Bearer {host_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, _body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn test_gifting_and_ledger_transfer() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let host_id = state.create_user("+919876543214", 0).await;
    let host_token = format!("tok_{host_id}_test");
    state.grant_test_token(&host_token, host_id).await;

    let sender_id = state.create_user("+919876543215", 500).await; // 500 coins initial
    let sender_token = format!("tok_{sender_id}_test");
    state.grant_test_token(&sender_token, sender_id).await;

    // Host opens room
    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "title": "Late Night Chat",
                "category_id": "chat"
            })
            .to_string(),
        ))
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    let room_id = body["room_id"].as_u64().unwrap();

    // Sender sends Diwali Sparkler gift (99 coins)
    let req = Request::post("/api/v1/gifts/send")
        .header("Authorization", format!("Bearer {sender_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": room_id,
                "recipient_id": host_id,
                "gift_id": "gift_fireworks",
                "count": 1,
                "combo_seq": 1,
                "idempotency_key": "gift_tx_test_1"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["remaining_coins"], 401); // 500 - 99
    assert_eq!(body["recipient_diamonds"], 99); // Host earned 99 diamonds!
}

#[tokio::test]
async fn test_otp_send_rejects_bad_phone() {
    let state = AppState::new();
    let app = create_router(state.clone());

    for bad in [
        "",
        "abc",
        "123",
        "+91 98765 43210",
        "+91987654321012345",
        "++919876543210",
    ] {
        let req = Request::post("/api/v1/auth/otp/send")
            .header("Content-Type", "application/json")
            .body(Body::from(
                json!({ "phone_number": bad, "country_code": "+91" }).to_string(),
            ))
            .unwrap();
        let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "phone {bad:?} must 400");
    }

    // Control: a valid E.164 number still passes.
    let req = Request::post("/api/v1/auth/otp/send")
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({ "phone_number": "+919876543299", "country_code": "+91" }).to_string(),
        ))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn test_room_viewer_cap() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let host_id = state.create_user("+919876543240", 0).await;
    let host_token = format!("tok_{host_id}_test");
    state.grant_test_token(&host_token, host_id).await;

    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({ "title": "Full House", "category_id": "chat" }).to_string(),
        ))
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    let room_id = body["room_id"].as_u64().unwrap();

    // Fill to the cap via HTTP join.
    for i in 0..camstar_api::state::MAX_ROOM_VIEWERS {
        let uid = state.create_user(&format!("+9100000{i:05}"), 0).await;
        let tok = format!("tok_{uid}_test");
        state.grant_test_token(&tok, uid).await;
        let req = Request::post(format!("/api/v1/rooms/{room_id}/join"))
            .header("Authorization", format!("Bearer {tok}"))
            .body(Body::empty())
            .unwrap();
        let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        assert_eq!(status, StatusCode::OK);
    }

    // One more viewer is refused…
    let extra = state.create_user("+919876543249", 0).await;
    let extra_tok = format!("tok_{extra}_test");
    state.grant_test_token(&extra_tok, extra).await;
    let req = Request::post(format!("/api/v1/rooms/{room_id}/join"))
        .header("Authorization", format!("Bearer {extra_tok}"))
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    // …but the host always admits (never counted as a viewer).
    let req = Request::post(format!("/api/v1/rooms/{room_id}/join"))
        .header("Authorization", format!("Bearer {host_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn test_close_room_reports_real_duration() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let host_id = state.create_user("+919876543250", 0).await;
    let host_token = format!("tok_{host_id}_test");
    state.grant_test_token(&host_token, host_id).await;

    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({ "title": "Quick Live", "category_id": "chat" }).to_string(),
        ))
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    let room_id = body["room_id"].as_u64().unwrap();

    let req = Request::post(format!("/api/v1/rooms/{room_id}/close"))
        .header("Authorization", format!("Bearer {host_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    // A just-opened room closes in seconds — never the old 1800 constant.
    assert!(body["duration_secs"].as_u64().unwrap() < 120);
    assert_eq!(body["diamonds_earned"], 0);
}

#[tokio::test]
async fn test_pk_accept_badges_rooms_and_scores_gifts() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let host_a = state.create_user("+919876543260", 0).await;
    let tok_a = format!("tok_{host_a}_test");
    state.grant_test_token(&tok_a, host_a).await;
    let host_b = state.create_user("+919876543261", 0).await;
    let tok_b = format!("tok_{host_b}_test");
    state.grant_test_token(&tok_b, host_b).await;
    let sender = state.create_user("+919876543262", 500).await;
    let tok_s = format!("tok_{sender}_test");
    state.grant_test_token(&tok_s, sender).await;

    let mut room_ids = Vec::new();
    for tok in [&tok_a, &tok_b] {
        let req = Request::post("/api/v1/rooms/create")
            .header("Authorization", format!("Bearer {tok}"))
            .header("Content-Type", "application/json")
            .body(Body::from(
                json!({ "title": "PK Room", "category_id": "chat" }).to_string(),
            ))
            .unwrap();
        let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        room_ids.push(body["room_id"].as_u64().unwrap());
    }

    // A invites B…
    let req = Request::post("/api/v1/pk/invite")
        .header("Authorization", format!("Bearer {tok_a}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "from_room_id": room_ids[0],
                "to_host_id": host_b,
                "duration_secs": 300
            })
            .to_string(),
        ))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let invite_id = body["invite_id"].as_str().unwrap().to_string();

    // …B accepts: battle starts, both rooms badged.
    let req = Request::post(format!("/api/v1/pk/invite/{invite_id}/respond"))
        .header("Authorization", format!("Bearer {tok_b}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({ "action": "accept" }).to_string()))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "in_battle");
    let pk_id = body["pk_id"].as_str().unwrap().to_string();
    {
        let rooms = state.rooms.read().await;
        for rid in &room_ids {
            let room = rooms.get(rid).unwrap();
            assert!(room.is_pk, "room {rid} must be badged");
            assert!(room.pk_opponent.is_some());
        }
    }

    // A 99-coin gift in A's room scores 99 for side A.
    let req = Request::post("/api/v1/gifts/send")
        .header("Authorization", format!("Bearer {tok_s}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": room_ids[0],
                "recipient_id": host_a,
                "gift_id": "gift_fireworks",
                "count": 1,
                "combo_seq": 1,
                "idempotency_key": "gift_tx_pk_1"
            })
            .to_string(),
        ))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);

    let req = Request::get(format!("/api/v1/pk/{pk_id}/status"))
        .header("Authorization", format!("Bearer {tok_a}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["host_a_points"], 99);
    assert_eq!(body["host_b_points"], 0);
}

/// Env-var tests mutate process-global state: serialize them, or the TURN
/// pair (set/remove) races the STUN-only test running beside it.
static ENV_LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();

fn env_lock() -> std::sync::MutexGuard<'static, ()> {
    ENV_LOCK
        .get_or_init(|| std::sync::Mutex::new(()))
        .lock()
        .unwrap()
}

// The ENV_LOCK guard is held across awaits on purpose: that is what
// serializes these process-global env mutations.
#[allow(clippy::await_holding_lock)]
#[tokio::test]
async fn test_ice_servers_stun_only_without_turn_config() {
    let _guard = env_lock();
    std::env::remove_var("CAMSTAR_TURN_URL");
    std::env::remove_var("CAMSTAR_TURN_SECRET");

    let state = AppState::new();
    let app = create_router(state.clone());
    let uid = state.create_user("+919876543270", 0).await;
    let tok = format!("tok_{uid}_test");
    state.grant_test_token(&tok, uid).await;

    // Unauthenticated callers get nothing (no free STUN oracle for scanners).
    let req = Request::get("/api/v1/rtc/ice-servers")
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    let req = Request::get("/api/v1/rtc/ice-servers")
        .header("Authorization", format!("Bearer {tok}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["servers"].as_array().unwrap().len(), 1);
    assert_eq!(
        body["servers"][0]["urls"][0],
        "stun:stun.l.google.com:19302"
    );
    assert!(
        body["servers"][0].get("username").is_none() || body["servers"][0]["username"].is_null()
    );
}

// The ENV_LOCK guard is held across awaits on purpose: that is what
// serializes these process-global env mutations.
#[allow(clippy::await_holding_lock)]
#[tokio::test]
async fn test_ice_servers_turn_credentials_with_config() {
    let _guard = env_lock();
    std::env::set_var("CAMSTAR_TURN_URL", "turn:turn.example.test:3478");
    std::env::set_var("CAMSTAR_TURN_SECRET", "test-static-secret");

    let state = AppState::new();
    let app = create_router(state.clone());
    let uid = state.create_user("+919876543271", 0).await;
    let tok = format!("tok_{uid}_test");
    state.grant_test_token(&tok, uid).await;

    let req = Request::get("/api/v1/rtc/ice-servers")
        .header("Authorization", format!("Bearer {tok}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["servers"].as_array().unwrap().len(), 2);
    let turn = &body["servers"][1];
    assert_eq!(turn["urls"][0], "turn:turn.example.test:3478");
    // coturn REST shape: "<expiry_epoch>:<user_id>", expiry ~24h out.
    let username = turn["username"].as_str().unwrap();
    let (expiry, who): (u64, u64) = {
        let mut parts = username.split(':');
        (
            parts.next().unwrap().parse().unwrap(),
            parts.next().unwrap().parse().unwrap(),
        )
    };
    assert_eq!(who, uid);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    assert!(expiry > now + 23 * 3600 && expiry <= now + 24 * 3600 + 60);
    assert!(!turn["credential"].as_str().unwrap().is_empty());

    std::env::remove_var("CAMSTAR_TURN_URL");
    std::env::remove_var("CAMSTAR_TURN_SECRET");
}

// The ENV_LOCK guard is held across awaits on purpose: that is what
// serializes these process-global env mutations.
#[allow(clippy::await_holding_lock)]
#[tokio::test]
async fn test_poster_upload_round_trip_and_guards() {
    let _guard = env_lock();
    // Isolate uploaded bytes from any real media root.
    let dir = std::env::temp_dir().join(format!("camstar_poster_test_{}", std::process::id()));
    std::env::set_var("MEDIA_ROOT", &dir);

    let state = AppState::new();
    let app = create_router(state.clone());
    let uid = state.create_user("+919876543272", 0).await;
    let tok = format!("tok_{uid}_test");
    state.grant_test_token(&tok, uid).await;

    // Minimal valid JPEG (magic + padding).
    let mut jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0];
    jpeg.extend(std::iter::repeat_n(0u8, 1024));

    // Unauthenticated upload is rejected before any byte is touched.
    let req = Request::post("/api/v1/uploads/poster")
        .header("Content-Type", "image/jpeg")
        .body(Body::from(jpeg.clone()))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    // Happy path: URL returned, file on disk with identical bytes.
    let req = Request::post("/api/v1/uploads/poster")
        .header("Authorization", format!("Bearer {tok}"))
        .header("Content-Type", "image/jpeg")
        .body(Body::from(jpeg.clone()))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let url = body["url"].as_str().unwrap().to_string();
    assert!(url.contains("/assets/posters/poster_") && url.ends_with(".jpg"));
    assert_eq!(body["bytes"], jpeg.len() as u64);
    let file_name = url.rsplit('/').next().unwrap();
    let on_disk = std::fs::read(dir.join("posters").join(file_name)).unwrap();
    assert_eq!(on_disk, jpeg);

    // The uploaded URL is accepted as a room cover…
    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {tok}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "title": "Cover Room",
                "category_id": "chat",
                "cover_image_url": url,
            })
            .to_string(),
        ))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);

    // …but javascript: URLs and blobs are not.
    let long_url = format!("https://x.test/{}", "a".repeat(600));
    for bad in ["javascript:alert(1)".to_string(), long_url] {
        let req = Request::post("/api/v1/rooms/create")
            .header("Authorization", format!("Bearer {tok}"))
            .header("Content-Type", "application/json")
            .body(Body::from(
                json!({
                    "title": "Cover Room",
                    "category_id": "chat",
                    "cover_image_url": bad,
                })
                .to_string(),
            ))
            .unwrap();
        let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
    }

    // Non-image bytes are rejected by the handler…
    let req = Request::post("/api/v1/uploads/poster")
        .header("Authorization", format!("Bearer {tok}"))
        .header("Content-Type", "image/jpeg")
        .body(Body::from(vec![0u8; 64]))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // …and oversized bodies never reach it: axum's default 2 MiB body cap
    // answers 413 first. Either way the bytes are refused.
    let req = Request::post("/api/v1/uploads/poster")
        .header("Authorization", format!("Bearer {tok}"))
        .header("Content-Type", "image/jpeg")
        .body(Body::from(vec![0xFFu8; 3 * 1024 * 1024 + 1]))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert!(
        status == StatusCode::PAYLOAD_TOO_LARGE || status == StatusCode::BAD_REQUEST,
        "oversize must be refused, got {status}"
    );

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_gift_quantity_guards() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let host_id = state.create_user("+919876543230", 0).await;
    let host_token = format!("tok_{host_id}_test");
    state.grant_test_token(&host_token, host_id).await;

    let sender_id = state.create_user("+919876543231", 500).await;
    let sender_token = format!("tok_{sender_id}_test");
    state.grant_test_token(&sender_token, sender_id).await;

    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({ "title": "Guards", "category_id": "chat" }).to_string(),
        ))
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    let room_id = body["room_id"].as_u64().unwrap();

    // count=0 must not mint a no-op ledger entry.
    let req = Request::post("/api/v1/gifts/send")
        .header("Authorization", format!("Bearer {sender_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": room_id,
                "recipient_id": host_id,
                "gift_id": "gift_fireworks",
                "count": 0,
                "combo_seq": 1,
                "idempotency_key": "gift_tx_zero"
            })
            .to_string(),
        ))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // combo_seq=0 must not corrupt combo accounting.
    let req = Request::post("/api/v1/gifts/send")
        .header("Authorization", format!("Bearer {sender_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": room_id,
                "recipient_id": host_id,
                "gift_id": "gift_fireworks",
                "count": 1,
                "combo_seq": 0,
                "idempotency_key": "gift_tx_zero_seq"
            })
            .to_string(),
        ))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn test_anti_fraud_host_cannot_gift_to_self() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let host_id = state.create_user("+919876543216", 500).await;
    let host_token = format!("tok_{host_id}_test");
    state.grant_test_token(&host_token, host_id).await;

    // Host opens room
    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "title": "Music Live",
                "category_id": "music"
            })
            .to_string(),
        ))
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    let room_id = body["room_id"].as_u64().unwrap();

    // Host attempts to send gift into their OWN room -> MUST BE FORBIDDEN
    let req = Request::post("/api/v1/gifts/send")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": room_id,
                "recipient_id": host_id,
                "gift_id": "gift_rose",
                "count": 1,
                "combo_seq": 1,
                "idempotency_key": "self_gift_attempt"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert!(body["message"]
        .as_str()
        .unwrap()
        .contains("HostCannotGiftToSelf"));
}

#[tokio::test]
async fn test_creator_kyc_and_payout_with_194q_tds() {
    let state = AppState::new();
    let app = create_router(state.clone());

    // Host with 10,000 diamonds earned
    let host_id = state.create_user("+919876543217", 0).await;
    let host_token = format!("tok_{host_id}_test");
    state.grant_test_token(&host_token, host_id).await;

    // Seed diamonds in host's diamond account
    {
        let amount = camstar_ledger::Money::from_minor(10_000).unwrap();
        let _ = state
            .ledger
            .post(
                camstar_ledger::EntryKind::TopUp,
                "seed_diamonds",
                vec![
                    camstar_ledger::Line {
                        account: camstar_api::state::system_clearing_account(),
                        direction: camstar_ledger::Direction::Debit,
                        amount,
                    },
                    camstar_ledger::Line {
                        account: camstar_api::state::user_diamonds_account(host_id),
                        direction: camstar_ledger::Direction::Credit,
                        amount,
                    },
                ],
            )
            .await;
    }

    // 1. Submit KYC — lands in pending, no payout capability yet
    let req = Request::post("/api/v1/kyc/submit")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "payer_name": "Aarav Sharma",
                "payer_address": "Bandra West, Mumbai",
                "pan_number": "ABCDE1234F",
                "aadhaar_last4": "5678",
                "bank_account_number": "123456789012",
                "ifsc_code": "HDFC0001234"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "pending");

    // 2. Dev-only KYC approval (CAMSTAR_DEV_KYC / debug builds)
    let req = Request::post(format!("/api/v1/kyc/approve/{host_id}"))
        .body(Body::empty())
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "approved");

    // 3. Preview Payout (10,000 diamonds -> ₹1,000 gross with 1% TDS = ₹10 TDS -> ₹990 net)
    let req = Request::get("/api/v1/payouts/preview?diamonds=10000")
        .header("Authorization", format!("Bearer {host_token}"))
        .body(Body::empty())
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["gross_inr"], 1000);
    assert_eq!(body["tds_194q_inr"], 10);
    assert_eq!(body["net_payout_inr"], 990);

    // 4. Request Payout
    let req = Request::post("/api/v1/payouts/request")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "diamonds_amount": 10000,
                "idempotency_key": "payout_req_1"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["net_inr"], 990);
    assert_eq!(body["status"], "processing");
}

#[tokio::test]
async fn test_beauty_assets_manifest_104_items() {
    let state = AppState::new();
    let app = create_router(state);

    let req = Request::get("/api/v1/config/beauty/assets")
        .body(Body::empty())
        .unwrap();

    let (status, body) = json_response(app.oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);

    let categories = body.as_array().unwrap();
    assert_eq!(categories.len(), 5); // Blush, Eyebrows, Eyelashes, Eyeshadow, Lipstick

    let total_items: usize = categories
        .iter()
        .map(|c| c["items"].as_array().unwrap().len())
        .sum();
    assert_eq!(total_items, 50); // 10 mattes each category
}

#[tokio::test]
async fn test_wheelpan_and_minigames_settlement() {
    use camstar_api::state::user_coins_account;
    use camstar_ledger::{Direction, EntryKind, Line, Money};

    let state = AppState::new();
    let app = create_router(state.clone());
    let user_id = state.create_user("+919876543299", 0).await;
    let token = format!("tok_{user_id}_wheel");
    state.grant_test_token(&token, user_id).await;

    // Fund user with 2,000 coins in ledger
    {
        state
            .ledger
            .post(
                EntryKind::TopUp,
                "fund_user_wheel_test",
                vec![
                    Line {
                        account: camstar_api::state::system_clearing_account(),
                        direction: Direction::Debit,
                        amount: Money::from_minor(2000).unwrap(),
                    },
                    Line {
                        account: user_coins_account(user_id),
                        direction: Direction::Credit,
                        amount: Money::from_minor(2000).unwrap(),
                    },
                ],
            )
            .await
            .unwrap();
    }

    // 1. Spin Lucky Wheel (WheelPan)
    let req = Request::post("/api/v1/games/turntable/spin")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": 1,
                "wheel_id": "wheel_lucky_paisa",
                "coins_bet": 100,
                "idempotency_key": "test_spin_1"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["target_sector"].as_u64().unwrap() <= 7);
    assert!(body["target_angle_deg"].as_f64().unwrap() > 0.0);
    assert!(body["remaining_coins"].as_i64().unwrap() > 0);

    // 2. Lucky Shovel (Digging game)
    let req = Request::post("/api/v1/games/shovel/dig")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": 1,
                "shovel_type": "silver",
                "slot_index": 4,
                "idempotency_key": "test_dig_1"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(!body["prize_name"].as_str().unwrap().is_empty());

    // 3. Magic Lamp Wish
    let req = Request::post("/api/v1/games/magiclamp/wish")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "room_id": 1,
                "wishes_count": 1,
                "idempotency_key": "test_lamp_1"
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    // EV-rebalanced lamp: multiplier ∈ {0,1,2,4,10}; reward must match cost × multiplier.
    let mult = body["multiplier_hit"].as_u64().unwrap();
    assert!([0, 1, 2, 4, 10].contains(&mult));
    assert_eq!(
        body["total_reward_coins"].as_i64().unwrap(),
        50 * (mult as i64)
    );
    if mult == 0 {
        assert!(!body["is_super_wish"].as_bool().unwrap());
    }

    // 4. FuncTag RPC Gateway (RangLive compatibility)
    let req = Request::post("/api/v1/gateway/rpc")
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::from(
            json!({
                "FuncTag": 51050703,
                "userId": user_id,
                "rewardId": 42
            })
            .to_string(),
        ))
        .unwrap();

    let (status, body) = json_response(app.oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["TagCode"], "SUCCESS");
    assert_eq!(body["data"]["rewardId"], 42);

    // 5. Total zero-sum double-entry ledger invariant verified
    let total = state.ledger.total_of_all_accounts().await.unwrap();
    assert_eq!(total.to_minor(), 0);
}

#[tokio::test]
async fn test_guest_tokens_rejected_by_default() {
    // Guest fallback must be closed unless explicitly enabled: a forged
    // Bearer guest_token_<id> authenticates nothing.
    std::env::remove_var("CAMSTAR_ALLOW_GUEST_TOKEN");
    assert!(!camstar_api::state::guest_tokens_enabled());
    let state = AppState::new();
    let app = create_router(state.clone());

    let req = Request::get("/api/v1/conversations/1002")
        .header("Authorization", "Bearer guest_token_9999")
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn test_conversation_requires_sign_in() {
    // No header and empty header both 401 — never attributed to user 1001.
    let state = AppState::new();
    let app = create_router(state.clone());

    let req = Request::get("/api/v1/conversations/1002")
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    let req = Request::post("/api/v1/conversations/1002/send")
        .header("Content-Type", "application/json")
        .header("Authorization", "")
        .body(Body::from(json!({ "text": "hi" }).to_string()))
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn test_conversation_list_requires_sign_in() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let req = Request::get("/api/v1/inbox/conversations")
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    let req = Request::post("/api/v1/conversations/1002/read")
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn test_message_inbox_opens_the_person_it_names() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let reader_id = state.create_user("+919876500001", 0).await;
    let token = format!("tok_{reader_id}_test");
    state.grant_test_token(&token, reader_id).await;

    // 1. The list seeds a conversation with the inbox cast (peer 1002), with
    //    its unread badge intact, and every row carries a real peer id.
    let req = Request::get("/api/v1/inbox/conversations")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let rows = body["conversations"].as_array().unwrap();
    assert!(!rows.is_empty());
    let satya = rows
        .iter()
        .find(|r| r["peer_id"].as_u64() == Some(1002))
        .expect("the seeded inbox contains peer 1002");
    assert_eq!(satya["unread_count"].as_u64(), Some(4));
    assert!(satya["peer_nickname"].as_str().unwrap().contains("SATYA"));

    // 2. Opening a thread (mark read) clears exactly that badge, and it stays
    //    cleared on the next fetch.
    let req = Request::post("/api/v1/conversations/1002/read")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let satya = body["conversations"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["peer_id"].as_u64() == Some(1002))
        .unwrap()
        .clone();
    assert_eq!(satya["unread_count"].as_u64(), Some(0));

    // 3. The thread itself shows the same person and the lines the preview
    //    promised — never a different fallback user.
    let req = Request::get("/api/v1/conversations/1002")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["recipient_id"].as_u64(), Some(1002));
    assert!(body["recipient_nickname"]
        .as_str()
        .unwrap()
        .contains("SATYA"));
    let messages = body["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 4);
    assert_eq!(
        messages.last().unwrap()["text"].as_str(),
        Some("good morning prisha")
    );

    // 4. Sending a line updates the conversation row: newest first, "You: …"
    //    preview, and the seeded history is still there underneath it.
    let req = Request::post("/api/v1/conversations/1002/send")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({ "text": "aaj milte hain" }).to_string()))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["is_self"].as_bool(), Some(true));

    let req = Request::get("/api/v1/inbox/conversations")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let rows = body["conversations"].as_array().unwrap();
    assert_eq!(rows.first().unwrap()["peer_id"].as_u64(), Some(1002));
    assert_eq!(
        rows.first().unwrap()["preview"].as_str(),
        Some("You: aaj milte hain")
    );

    let req = Request::get("/api/v1/conversations/1002")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let messages = body["messages"].as_array().unwrap();
    assert_eq!(messages.len(), 5);
    assert_eq!(
        messages.last().unwrap()["text"].as_str(),
        Some("aaj milte hain")
    );
}

#[tokio::test]
async fn test_notification_rows_carry_real_sender_ids() {
    // A notification row that says "Anjali Verma" must carry her peer id, so
    // tapping it can never open a different user's chat.
    let state = AppState::new();
    let app = create_router(state.clone());

    let req = Request::get("/api/v1/inbox").body(Body::empty()).unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let messages = body["messages"].as_array().unwrap();
    assert!(!messages.is_empty());
    for row in messages {
        let sender_id = row["sender_id"].as_u64().unwrap();
        assert!(
            sender_id >= 1002,
            "sender_id {sender_id} must be a real peer"
        );
        assert!(!row["sender_nickname"].as_str().unwrap().is_empty());
    }
}

#[tokio::test]
async fn test_official_feed_matches_the_reference_anatomy() {
    // The Official tab mirrors the reference notifications page: a follow
    // requests summary, "This week / This month / Earlier" buckets holding
    // system notices and activity rows with real actors, and a suggested
    // block. Every actor must be an inbox-cast peer so nothing opens a
    // different person than the row names.
    let state = AppState::new();
    let app = create_router(state.clone());

    let reader_id = state.create_user("+919876500101", 0).await;
    let token = format!("tok_{reader_id}_feed");
    state.grant_test_token(&token, reader_id).await;

    let req = Request::get("/api/v1/inbox/official")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);

    // Time buckets in the reference's order, none of them empty.
    let groups = body["groups"].as_array().unwrap();
    let labels: Vec<&str> = groups
        .iter()
        .map(|g| g["label"].as_str().unwrap())
        .collect();
    assert_eq!(labels, vec!["This week", "This month", "Earlier"]);
    let rows: Vec<&serde_json::Value> = groups
        .iter()
        .flat_map(|g| g["rows"].as_array().unwrap().iter())
        .collect();
    assert!(rows.len() >= 12, "expected a richly seeded feed");

    // At least one row of every trailing shape and both notice glyphs.
    let trailing = |kind: &str| {
        rows.iter()
            .any(|r| r["trailing"]["kind"].as_str() == Some(kind))
    };
    assert!(rows.iter().any(|r| r["kind"] == "notice"));
    assert!(rows.iter().any(|r| r["icon"] == "info"));
    assert!(trailing("thumbnail"));
    assert!(trailing("follow"));
    assert!(trailing("confirm_delete"));
    // Confirm/Delete rows must carry the requester's id: the client posts it
    // straight to /inbox/follow-requests/{id}/{action}.
    assert!(rows
        .iter()
        .filter(|r| r["trailing"]["kind"] == "confirm_delete")
        .all(|r| (1007..=1008).contains(&r["trailing"]["user_id"].as_u64().unwrap())));
    assert!(rows.iter().any(|r| r["body"]
        .as_str()
        .unwrap()
        .contains("requested to follow you")));

    // Every actor is a real peer with a non-empty display name.
    for row in &rows {
        assert!(!row["row_id"].as_str().unwrap().is_empty());
        for a in row["actors"].as_array().unwrap() {
            let id = a["user_id"].as_u64().unwrap();
            assert!((1002..=1011).contains(&id), "actor {id} must be a peer");
            assert!(!a["nickname"].as_str().unwrap().is_empty());
            assert!(a["avatar_url"]
                .as_str()
                .unwrap()
                .contains("/assets/avatars/"));
        }
    }

    // Follow requests summary: two pending on a fresh account.
    assert_eq!(body["follow_requests"]["title"], "Follow requests");
    let pending = body["follow_requests"]["pending"].as_array().unwrap();
    assert_eq!(pending.len(), 2);
    assert!(body["follow_requests"]["subtitle"]
        .as_str()
        .unwrap()
        .contains("+ 1 other"));
    assert_eq!(
        body["follow_requests"]["avatars"].as_array().unwrap().len(),
        2
    );

    // Suggested block, pointing "See all" at the search/explore sheet.
    assert_eq!(body["suggested"]["label"], "Suggested for you");
    assert_eq!(body["suggested"]["see_all_label"], "See all suggestions");
    assert_eq!(body["suggested"]["see_all_target"], "search");
    assert_eq!(body["suggested"]["users"].as_array().unwrap().len(), 3);

    // A sub-tab filter keeps the buckets but drops the summary/suggested
    // blocks and every row outside that section.
    let req = Request::get("/api/v1/inbox/official?section=event")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["groups"]
        .as_array()
        .unwrap()
        .iter()
        .flat_map(|g| g["rows"].as_array().unwrap().iter())
        .all(|r| r["section"] == "event"));
    assert!(body["follow_requests"]["pending"]
        .as_array()
        .unwrap()
        .is_empty());
    assert!(body["suggested"]["users"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn test_follow_request_confirm_and_delete_resolve_the_row() {
    // Confirm/Delete live server-side: the request row can never come back on
    // a refresh. Confirming turns the row into "started following you";
    // deleting removes it; both update the summary row.
    let state = AppState::new();
    let app = create_router(state.clone());

    let reader_id = state.create_user("+919876500102", 0).await;
    let token = format!("tok_{reader_id}_requests");
    state.grant_test_token(&token, reader_id).await;

    let req = Request::post("/api/v1/inbox/follow-requests/1007/confirm")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);

    let rows: Vec<serde_json::Value> = body["groups"]
        .as_array()
        .unwrap()
        .iter()
        .flat_map(|g| g["rows"].as_array().unwrap().clone())
        .collect();
    assert!(
        !rows
            .iter()
            .any(|r| r["trailing"]["user_id"].as_u64() == Some(1007)
                && r["trailing"]["kind"] == "confirm_delete"),
        "confirmed request must leave the confirm/delete state"
    );
    assert!(rows.iter().any(|r| {
        r["body"]
            .as_str()
            .unwrap()
            .contains("started following you")
            && r["trailing"]["user_id"].as_u64() == Some(1007)
    }));
    assert_eq!(
        body["follow_requests"]["pending"].as_array().unwrap().len(),
        1
    );
    assert_eq!(body["follow_requests"]["subtitle"], "Ranjeet Kumar");

    // Delete the second one: feed has no requests left and the summary hides.
    let req = Request::post("/api/v1/inbox/follow-requests/1008/delete")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["follow_requests"]["pending"]
        .as_array()
        .unwrap()
        .is_empty());
    assert_eq!(body["follow_requests"]["subtitle"], "");
    assert!(!body["groups"]
        .as_array()
        .unwrap()
        .iter()
        .flat_map(|g| g["rows"].as_array().unwrap().iter())
        .any(|r| r["trailing"]["kind"] == "confirm_delete"));

    // A peer that never asked cannot be confirmed or deleted.
    let req = Request::post("/api/v1/inbox/follow-requests/1002/confirm")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn test_official_follow_pills_read_the_readers_follows() {
    // Follow/Following on the feed is per reader and survives reloads: the
    // same endpoint that the profile sheet uses must flip the pill.
    let state = AppState::new();
    let app = create_router(state.clone());

    let reader_id = state.create_user("+919876500103", 0).await;
    let token = format!("tok_{reader_id}_pills");
    state.grant_test_token(&token, reader_id).await;

    let suggestion_following = |body: &serde_json::Value| -> bool {
        body["suggested"]["users"]
            .as_array()
            .unwrap()
            .iter()
            .find(|u| u["user_id"].as_u64() == Some(1003))
            .unwrap()["is_following"]
            .as_bool()
            .unwrap()
    };

    let follow_request = |method: &str| {
        Request::builder()
            .method(method)
            .uri("/api/v1/social/follow/1003")
            .header("Authorization", format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap()
    };

    let req = Request::get("/api/v1/inbox/official")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert!(!suggestion_following(&body));

    let (status, _) =
        json_response(app.clone().oneshot(follow_request("POST")).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);

    let req = Request::get("/api/v1/inbox/official")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert!(suggestion_following(&body));
    assert!(body["groups"]
        .as_array()
        .unwrap()
        .iter()
        .flat_map(|g| g["rows"].as_array().unwrap().iter())
        .any(|r| r["trailing"]["user_id"].as_u64() == Some(1003)
            && r["trailing"]["is_following"] == true));

    let unfollow = Request::builder()
        .method("POST")
        .uri("/api/v1/social/unfollow/1003")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, _) = json_response(app.clone().oneshot(unfollow).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);

    let req = Request::get("/api/v1/inbox/official")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert!(!suggestion_following(&body));
}

#[tokio::test]
#[allow(clippy::await_holding_lock)] // env serialization is the point here
async fn test_kyc_review_endpoint_gates_and_decides() {
    // This test flips CAMSTAR_ADMIN_TOKEN; serialize with the other
    // env-mutating tests (see env_lock at the ICE tests).
    let _guard = env_lock();
    std::env::remove_var("CAMSTAR_ADMIN_TOKEN");

    let state = AppState::new();
    let app = create_router(state.clone());
    let host_id = state.create_user("+919876543280", 0).await;
    let token = format!("tok_{host_id}_test");
    state.grant_test_token(&token, host_id).await;

    let submit = || {
        Request::post("/api/v1/kyc/submit")
            .header("Authorization", format!("Bearer {token}"))
            .header("Content-Type", "application/json")
            .body(Body::from(
                json!({
                    "payer_name": "Diya Verma",
                    "payer_address": "Indiranagar, Bengaluru",
                    "pan_number": "PQRSX6789K",
                    "aadhaar_last4": "4321",
                    "bank_account_number": "987654321012",
                    "ifsc_code": "ICIC0004321"
                })
                .to_string(),
            ))
            .unwrap()
    };
    let (status, _) = json_response(app.clone().oneshot(submit()).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);

    let review = |decision: &str, reason: Option<&str>, admin: Option<&str>| {
        let mut rb = Request::post(format!("/api/v1/kyc/review/{host_id}"))
            .header("Content-Type", "application/json");
        if let Some(a) = admin {
            rb = rb.header("X-Admin-Token", a);
        }
        let mut body = json!({ "decision": decision });
        if let Some(r) = reason {
            body["reason"] = json!(r);
        }
        rb.body(Body::from(body.to_string())).unwrap()
    };

    // Unconfigured: the route fails closed even with a guessed token.
    let (status, _) = json_response(
        app.clone()
            .oneshot(review("approve", None, Some("x")))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    std::env::set_var("CAMSTAR_ADMIN_TOKEN", "test-admin-token-0123456789abcdef");

    // Wrong token, then missing token: 401 and 401 (never approved).
    for admin in [Some("wrong-token"), None] {
        let (status, _) = json_response(
            app.clone()
                .oneshot(review("approve", None, admin))
                .await
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "admin={admin:?}");
    }

    // Rejection requires a reason; an empty one is refused.
    let (status, _) = json_response(
        app.clone()
            .oneshot(review(
                "reject",
                None,
                Some("test-admin-token-0123456789abcdef"),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = json_response(
        app.clone()
            .oneshot(review(
                "dance",
                None,
                Some("test-admin-token-0123456789abcdef"),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Real rejection: record flips, reason lands, and payouts stay forbidden.
    let (status, body) = json_response(
        app.clone()
            .oneshot(review(
                "reject",
                Some("blurred PAN"),
                Some("test-admin-token-0123456789abcdef"),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "rejected");
    let kyc = Request::get("/api/v1/kyc/status")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(kyc).await.unwrap()).await;
    assert_eq!(body["status"], "rejected");
    assert_eq!(body["rejection_reason"], "blurred PAN");

    // Then approval: capability granted, preview works.
    let (status, body) = json_response(
        app.clone()
            .oneshot(review(
                "approve",
                None,
                Some("test-admin-token-0123456789abcdef"),
            ))
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "approved");
    let preview = Request::get("/api/v1/payouts/preview?diamonds=1000")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(preview).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["net_payout_inr"], 99); // 100 inr gross, 1% TDS → 99 net

    std::env::remove_var("CAMSTAR_ADMIN_TOKEN");
}
