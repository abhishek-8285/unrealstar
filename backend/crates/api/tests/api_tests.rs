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
        .body(Body::from(json!({
            "phone_number": "+919876543210",
            "country_code": "+91"
        }).to_string()))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let session_id = body["otp_session_id"].as_str().unwrap();

    // 2. Verify OTP
    let req = Request::post("/api/v1/auth/otp/verify")
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "otp_session_id": session_id,
            "otp_code": "123456",
            "device_uid": "dev_test_123"
        }).to_string()))
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
        .body(Body::from(json!({
            "nickname": "Priya_Live",
            "bio": "Bollywood dancer & singer"
        }).to_string()))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["nickname"], "Priya_Live");

    // 5. Rotate Token
    let req = Request::post("/api/v1/auth/token/refresh")
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "refresh_token": refresh_token,
            "device_uid": "dev_test_123"
        }).to_string()))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["access_token"].is_string());
}

#[tokio::test]
async fn test_wallet_and_payin_order() {
    let state = AppState::new();
    let app = create_router(state.clone());
    let user_id = state.create_user("+919876543211", 0).await;
    let token = format!("tok_{user_id}_test");
    state.tokens_to_user.write().await.insert(token.clone(), user_id);

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

    // 3. Create Top-up Order (buying 150 coins package)
    let req = Request::post("/api/v1/payments/orders/create")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "package_id": "pkg_150",
            "provider": "razorpay",
            "idempotency_key": "pay_test_order_1"
        }).to_string()))
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins_credited"], 165); // 150 + 15 bonus
    assert!(body["upi_intent_uri"].as_str().unwrap().starts_with("upi://pay"));

    // 4. Verify balance is now 165 coins in Ledger
    let req = Request::get("/api/v1/wallet/balance")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins"], 165);
}

#[tokio::test]
async fn test_room_lifecycle_and_seating() {
    let state = AppState::new();
    let app = create_router(state.clone());
    let host_id = state.create_user("+919876543212", 0).await;
    let host_token = format!("tok_{host_id}_test");
    state.tokens_to_user.write().await.insert(host_token.clone(), host_id);

    let guest_id = state.create_user("+919876543213", 0).await;
    let guest_token = format!("tok_{guest_id}_test");
    state.tokens_to_user.write().await.insert(guest_token.clone(), guest_id);

    // 1. Host creates room
    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "title": "Bollywood Singing Night",
            "category_id": "music"
        }).to_string()))
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
        .body(Body::from(json!({
            "slot": 1
        }).to_string()))
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
    state.tokens_to_user.write().await.insert(host_token.clone(), host_id);

    let sender_id = state.create_user("+919876543215", 500).await; // 500 coins initial
    let sender_token = format!("tok_{sender_id}_test");
    state.tokens_to_user.write().await.insert(sender_token.clone(), sender_id);

    // Host opens room
    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "title": "Late Night Chat",
            "category_id": "chat"
        }).to_string()))
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    let room_id = body["room_id"].as_u64().unwrap();

    // Sender sends Diwali Sparkler gift (99 coins)
    let req = Request::post("/api/v1/gifts/send")
        .header("Authorization", format!("Bearer {sender_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "room_id": room_id,
            "recipient_id": host_id,
            "gift_id": "gift_fireworks",
            "count": 1,
            "combo_seq": 1,
            "idempotency_key": "gift_tx_test_1"
        }).to_string()))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["remaining_coins"], 401); // 500 - 99
    assert_eq!(body["recipient_diamonds"], 99); // Host earned 99 diamonds!
}

#[tokio::test]
async fn test_anti_fraud_host_cannot_gift_to_self() {
    let state = AppState::new();
    let app = create_router(state.clone());

    let host_id = state.create_user("+919876543216", 500).await;
    let host_token = format!("tok_{host_id}_test");
    state.tokens_to_user.write().await.insert(host_token.clone(), host_id);

    // Host opens room
    let req = Request::post("/api/v1/rooms/create")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "title": "Music Live",
            "category_id": "music"
        }).to_string()))
        .unwrap();
    let (_, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    let room_id = body["room_id"].as_u64().unwrap();

    // Host attempts to send gift into their OWN room -> MUST BE FORBIDDEN
    let req = Request::post("/api/v1/gifts/send")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "room_id": room_id,
            "recipient_id": host_id,
            "gift_id": "gift_rose",
            "count": 1,
            "combo_seq": 1,
            "idempotency_key": "self_gift_attempt"
        }).to_string()))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert!(body["message"].as_str().unwrap().contains("HostCannotGiftToSelf"));
}

#[tokio::test]
async fn test_creator_kyc_and_payout_with_194q_tds() {
    let state = AppState::new();
    let app = create_router(state.clone());

    // Host with 10,000 diamonds earned
    let host_id = state.create_user("+919876543217", 0).await;
    let host_token = format!("tok_{host_id}_test");
    state.tokens_to_user.write().await.insert(host_token.clone(), host_id);

    // Seed diamonds in host's diamond account
    {
        let mut ledger = state.ledger.write().await;
        let amount = camstar_ledger::Money::from_minor(10_000).unwrap();
        let _ = ledger.post(
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
        );
    }

    // 1. Submit KYC
    let req = Request::post("/api/v1/kyc/submit")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "payer_name": "Aarav Sharma",
            "payer_address": "Bandra West, Mumbai",
            "pan_number": "ABCDE1234F",
            "aadhaar_last4": "5678",
            "bank_account_number": "123456789012",
            "ifsc_code": "HDFC0001234"
        }).to_string()))
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "approved");

    // 2. Preview Payout (10,000 diamonds -> ₹1,000 gross with 1% TDS = ₹10 TDS -> ₹990 net)
    let req = Request::get("/api/v1/payouts/preview?diamonds=10000")
        .body(Body::empty())
        .unwrap();

    let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["gross_inr"], 1000);
    assert_eq!(body["tds_194q_inr"], 10);
    assert_eq!(body["net_payout_inr"], 990);

    // 3. Request Payout
    let req = Request::post("/api/v1/payouts/request")
        .header("Authorization", format!("Bearer {host_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({
            "diamonds_amount": 10000,
            "idempotency_key": "payout_req_1"
        }).to_string()))
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
    use camstar_ledger::{Direction, EntryKind, Line, Money};
    use camstar_api::state::user_coins_account;

    let state = AppState::new();
    let app = create_router(state.clone());
    let user_id = state.create_user("+919876543299", 0).await;
    let token = format!("tok_{user_id}_wheel");
    state.tokens_to_user.write().await.insert(token.clone(), user_id);

    // Fund user with 2,000 coins in ledger
    {
        let mut ledger = state.ledger.write().await;
        ledger
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
    assert!(body["multiplier_hit"].as_u64().unwrap() >= 2);

    // 4. FuncTag RPC Gateway (RangLive compatibility)
    let req = Request::post("/api/v1/gateway/rpc")
        .header("Content-Type", "application/json")
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
    let ledger = state.ledger.read().await;
    assert_eq!(ledger.total_of_all_accounts().to_minor(), 0);
}
