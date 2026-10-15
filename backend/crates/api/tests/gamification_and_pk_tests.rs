use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use camstar_api::{create_router, state::AppState};
use serde_json::json;
use tower::ServiceExt;

mod common {
    use axum::{
        body::Body,
        http::Request,
    };
    use http_body_util::BodyExt;
    use serde_json::{json, Value};
    use tower::ServiceExt;

    pub async fn json_response(resp: axum::response::Response) -> (axum::http::StatusCode, Value) {
        let status = resp.status();
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let value: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
        (status, value)
    }

    pub async fn login_user(app: &axum::Router, phone: &str) -> (String, u64) {
        let req = Request::post("/api/v1/auth/otp/send")
            .header("Content-Type", "application/json")
            .body(Body::from(
                json!({
                    "phone_number": phone,
                    "country_code": "+91"
                })
                .to_string(),
            ))
            .unwrap();
        let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        assert_eq!(status, axum::http::StatusCode::OK);
        let session_id = body["otp_session_id"].as_str().unwrap();
        let otp_code = body["debug_otp"].as_str().unwrap().to_string();

        let req = Request::post("/api/v1/auth/otp/verify")
            .header("Content-Type", "application/json")
            .body(Body::from(
                json!({
                    "otp_session_id": session_id,
                    "otp_code": otp_code,
                    "device_uid": format!("dev_{phone}")
                })
                .to_string(),
            ))
            .unwrap();
        let (status, body) = json_response(app.clone().oneshot(req).await.unwrap()).await;
        assert_eq!(status, axum::http::StatusCode::OK);
        (
            body["access_token"].as_str().unwrap().to_string(),
            body["user_id"].as_u64().unwrap(),
        )
    }
}

#[tokio::test]
async fn test_gamification_leaderboards_and_tasks() {
    let state = AppState::new();
    let app = create_router(state);

    let (token, _user_id) = common::login_user(&app, "+919876543210").await;

    // 1. Fetch Leaderboards
    let req = Request::get("/api/v1/leaderboards")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let items = body.as_array().expect("leaderboards array");
    assert!(!items.is_empty(), "leaderboard should contain seeded items");
    assert_eq!(items[0]["rank"], 1);

    // 2. Fetch VIP Tiers
    let req = Request::get("/api/v1/vip/tiers")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let tiers = body.as_array().expect("vip tiers array");
    assert!(!tiers.is_empty(), "vip tiers should not be empty");

    // 3. Fetch Daily Tasks
    let req = Request::get("/api/v1/tasks/daily")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let tasks = body.as_array().expect("tasks array");
    assert!(tasks.len() >= 3);

    // 4. Claim Completed Daily Check-in Task
    let req = Request::post("/api/v1/tasks/task_checkin/claim")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["success"], true);
    assert_eq!(body["reward_coins"], 10);

    // 5. Verify wallet balance credited by 10 coins
    let req = Request::get("/api/v1/wallet/balance")
        .header("Authorization", format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["coins"], 10);

    // 6. Double claim must be rejected
    let req = Request::post("/api/v1/tasks/task_checkin/claim")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let (status, _body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // 7. Claiming an uncompleted task must be rejected
    let req = Request::post("/api/v1/tasks/task_send_gift/claim")
        .header("Authorization", format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let (status, _body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn test_pk_invite_and_battle_lifecycle() {
    let state = AppState::new();
    let app = create_router(state);

    let (host_a_token, host_a_id) = common::login_user(&app, "+919876543211").await;
    let (host_b_token, host_b_id) = common::login_user(&app, "+919876543212").await;

    // Host A sends PK invite to Host B
    let req = Request::post("/api/v1/pk/invite")
        .header("Authorization", format!("Bearer {host_a_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(
            json!({
                "from_room_id": 101,
                "to_host_id": host_b_id,
                "duration_secs": 300
            })
            .to_string(),
        ))
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let invite_id = body["invite_id"].as_str().unwrap();

    // Check status is pending / invited
    let req = Request::get(format!("/api/v1/pk/{invite_id}/status"))
        .header("Authorization", format!("Bearer {host_a_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "invited");
    assert_eq!(body["host_a_id"], host_a_id);
    assert_eq!(body["host_b_id"], host_b_id);

    // Host B accepts the invite
    let req = Request::post(format!("/api/v1/pk/invite/{invite_id}/respond"))
        .header("Authorization", format!("Bearer {host_b_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from(json!({ "action": "accept" }).to_string()))
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "in_battle");

    // Check status is now in_battle
    let req = Request::get(format!("/api/v1/pk/{invite_id}/status"))
        .header("Authorization", format!("Bearer {host_a_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "in_battle");
}

#[tokio::test]
async fn test_social_follow_and_engagement() {
    let state = AppState::new();
    let app = create_router(state);

    let (user_token, _user_id) = common::login_user(&app, "+919876543213").await;
    let (_target_token, target_id) = common::login_user(&app, "+919876543214").await;

    // 1. Follow target user
    let req = Request::post(format!("/api/v1/social/follow/{target_id}"))
        .header("Authorization", format!("Bearer {user_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["is_following"], true);

    // 2. Fetch followed feed moments (before following target_id, empty if target_id has moments)
    let req = Request::get("/api/v1/feed/moments?followed_only=true")
        .header("Authorization", format!("Bearer {user_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["posts"].as_array().unwrap().len(), 0);

    // 3. Unfollow target user
    let req = Request::post(format!("/api/v1/social/unfollow/{target_id}"))
        .header("Authorization", format!("Bearer {user_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["is_following"], false);
}

#[tokio::test]
async fn test_trendo_family_lifecycle() {
    let state = AppState::new();
    let app = create_router(state);

    let (user_token, _user_id) = common::login_user(&app, "+919876543215").await;

    // 1. Initial state: user has no family
    let req = Request::get("/api/v1/family/my")
        .header("Authorization", format!("Bearer {user_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["my_role"], "none");
    assert!(body["family"].is_null());

    // 2. Fetch Family Leaderboard
    let req = Request::get("/api/v1/family/leaderboard")
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let families = body.as_array().expect("family list");
    assert!(!families.is_empty(), "seeded families must exist");
    let target_family_id = families[0]["family_id"].as_str().unwrap();
    let initial_members = families[0]["members_count"].as_u64().unwrap();

    // 3. User joins family
    let req = Request::post(format!("/api/v1/family/join/{target_family_id}"))
        .header("Authorization", format!("Bearer {user_token}"))
        .header("Content-Type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["my_role"], "member");
    assert_eq!(body["family"]["family_id"], target_family_id);
    assert_eq!(body["family"]["members_count"], initial_members + 1);

    // 4. Verify user's family is now reflected in /api/v1/family/my
    let req = Request::get("/api/v1/family/my")
        .header("Authorization", format!("Bearer {user_token}"))
        .body(Body::empty())
        .unwrap();
    let (status, body) = common::json_response(app.clone().oneshot(req).await.unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["my_role"], "member");
    assert_eq!(body["family"]["family_id"], target_family_id);
}

