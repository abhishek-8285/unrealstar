pub mod error;
pub mod models;
pub mod routes;
pub mod state;

use axum::{
    extract::{ConnectInfo, State},
    http::Request,
    middleware::{self, Next},
    response::Response,
    routing::{get, post},
    Router,
};
use std::net::SocketAddr;
use std::path::PathBuf;
use tower_http::cors::CorsLayer;

use crate::error::ApiError;

pub use state::{system_clearing_account, user_coins_account, user_diamonds_account, AppState};

/// Per-IP sliding-window rate limit on OTP sends, so one client cannot rotate
/// phone numbers to dodge the per-phone cooldown. Reads `ConnectInfo`
/// optionally: unit tests (no connection info) skip the check, production
/// (`into_make_service_with_connect_info`) enforces it.
async fn rate_limit_otp_sends(
    State(state): State<AppState>,
    req: Request<axum::body::Body>,
    next: Next,
) -> Result<Response, ApiError> {
    if let Some(ConnectInfo(addr)) = req.extensions().get::<ConnectInfo<SocketAddr>>() {
        let now = chrono::Utc::now();
        let mut log = state.otp_ip_log.write().await;
        let hits: &mut Vec<chrono::DateTime<chrono::Utc>> = log.entry(addr.ip()).or_default();
        hits.retain(|t| now.signed_duration_since(*t).num_seconds() <= 600);
        if hits.len() >= 20 {
            return Err(ApiError::TooManyRequests(
                "Too many OTP requests from this address, try again later".into(),
            ));
        }
        hits.push(now);
    }
    Ok(next.run(req).await)
}

pub fn create_router(state: AppState) -> Router {
    // CORS: lock down to known origins. Default is the production origin;
    // `CAMSTAR_ALLOWED_ORIGINS` (comma-separated) overrides for local dev.
    let allowed_origins: Vec<String> = std::env::var("CAMSTAR_ALLOWED_ORIGINS")
        .map(|s| {
            s.split(',')
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(String::from)
                .collect()
        })
        .unwrap_or_else(|_| vec![public_origin()]);
    let cors = CorsLayer::new()
        .allow_origin(
            allowed_origins
                .iter()
                .filter_map(|o| o.parse().ok())
                .collect::<Vec<_>>(),
        )
        .allow_methods([
            axum::http::Method::GET,
            axum::http::Method::POST,
            axum::http::Method::PUT,
            axum::http::Method::DELETE,
        ])
        .allow_headers([
            axum::http::header::AUTHORIZATION,
            axum::http::header::CONTENT_TYPE,
        ]);

    Router::new()
        // Auth & Users (OTP send carries its own per-IP rate-limit layer)
        .route(
            "/api/v1/auth/otp/send",
            post(routes::auth::otp_send).route_layer(middleware::from_fn_with_state(
                state.clone(),
                rate_limit_otp_sends,
            )),
        )
        .route("/api/v1/auth/otp/verify", post(routes::auth::otp_verify))
        .route(
            "/api/v1/auth/token/refresh",
            post(routes::auth::token_refresh),
        )
        .route("/api/v1/auth/logout", post(routes::auth::logout))
        .route(
            "/api/v1/users/me",
            get(routes::auth::get_me).put(routes::auth::update_me),
        )
        .route(
            "/api/v1/users/me/dashboard",
            get(routes::auth::get_me_dashboard),
        )
        .route("/api/v1/users/{user_id}", get(routes::auth::get_user))
        // Wallet & Payments
        .route("/api/v1/wallet/balance", get(routes::wallet::get_balance))
        .route(
            "/api/v1/wallet/transactions",
            get(routes::wallet::get_transactions),
        )
        .route(
            "/api/v1/payments/packages",
            get(routes::wallet::list_packages),
        )
        .route(
            "/api/v1/payments/orders/create",
            post(routes::wallet::create_order),
        )
        .route(
            "/api/v1/payments/webhook/{provider}",
            post(routes::wallet::payment_webhook),
        )
        // Rooms & Streaming
        .route("/api/v1/rooms/create", post(routes::rooms::create_room))
        .route(
            "/api/v1/rooms/{room_id}/join",
            post(routes::rooms::join_room),
        )
        .route(
            "/api/v1/rooms/{room_id}/leave",
            post(routes::rooms::leave_room),
        )
        .route(
            "/api/v1/rooms/{room_id}/close",
            post(routes::rooms::close_room),
        )
        .route(
            "/api/v1/rooms/{room_id}/seats/request",
            post(routes::rooms::request_seat),
        )
        .route(
            "/api/v1/rooms/{room_id}/seats/action",
            post(routes::rooms::seat_action),
        )
        .route(
            "/api/v1/rooms/{room_id}/moderate",
            post(routes::rooms::moderate),
        )
        .route("/ws/rooms/{room_id}", get(routes::rooms::ws_handler))
        // Gifts & Games
        .route("/api/v1/gifts/catalog", get(routes::gifts::list_gifts))
        .route("/api/v1/gifts/send", post(routes::gifts::send_gift))
        .route(
            "/api/v1/gifts/red-packet/drop",
            post(routes::gifts::drop_red_packet),
        )
        .route(
            "/api/v1/gifts/red-packet/{id}/claim",
            post(routes::gifts::claim_red_packet),
        )
        .route(
            "/api/v1/games/turntable/spin",
            post(routes::gifts::spin_turntable),
        )
        .route("/api/v1/games/shovel/dig", post(routes::gifts::dig_shovel))
        .route(
            "/api/v1/games/magiclamp/wish",
            post(routes::gifts::wish_magic_lamp),
        )
        .route(
            "/api/v1/gateway/rpc",
            post(routes::gifts::handle_functag_rpc),
        )
        // PK Battles
        .route("/api/v1/pk/match/request", post(routes::pk::request_match))
        .route("/api/v1/pk/invite", post(routes::pk::send_invite))
        .route(
            "/api/v1/pk/invite/{id}/respond",
            post(routes::pk::respond_invite),
        )
        .route("/api/v1/pk/{pk_id}/status", get(routes::pk::get_status))
        // Social & Moments Feed
        .route(
            "/api/v1/social/follow/{user_id}",
            post(routes::social::follow_user),
        )
        .route(
            "/api/v1/social/unfollow/{user_id}",
            post(routes::social::unfollow_user),
        )
        .route("/api/v1/feed/rooms", get(routes::social::feed_rooms))
        .route(
            "/api/v1/feed/live/banners",
            get(routes::social::feed_live_banners),
        )
        // Inbox
        .route(
            "/api/v1/inbox/categories",
            get(routes::inbox::inbox_categories),
        )
        .route("/api/v1/inbox", get(routes::inbox::inbox_messages))
        .route("/api/v1/inbox/official", get(routes::inbox::inbox_official))
        .route("/api/v1/feed/moments", get(routes::social::feed_moments))
        .route(
            "/api/v1/feed/moments/create",
            post(routes::social::create_moment),
        )
        .route(
            "/api/v1/feed/moments/{id}/like",
            post(routes::social::like_moment),
        )
        .route(
            "/api/v1/feed/moments/{id}/unlike",
            post(routes::social::unlike_moment),
        )
        .route(
            "/api/v1/feed/moments/{id}/comments",
            get(routes::social::list_comments).post(routes::social::comment_moment),
        )
        .route(
            "/api/v1/feed/comments/{comment_id}/like",
            post(routes::social::like_comment),
        )
        .route(
            "/api/v1/feed/comments/{comment_id}/unlike",
            post(routes::social::unlike_comment),
        )
        .route(
            "/api/v1/feed/moments/{id}/share",
            post(routes::social::share_moment),
        )
        .route(
            "/api/v1/feed/moments/{id}/gift",
            post(routes::social::gift_moment),
        )
        // Gamification, VIP & Tasks
        .route(
            "/api/v1/leaderboards",
            get(routes::gamification::get_leaderboards),
        )
        .route(
            "/api/v1/tasks/daily",
            get(routes::gamification::get_daily_tasks),
        )
        .route(
            "/api/v1/tasks/{id}/claim",
            post(routes::gamification::claim_task),
        )
        .route(
            "/api/v1/vip/tiers",
            get(routes::gamification::get_vip_tiers),
        )
        // Search (Trendo SearchActivity)
        .route("/api/v1/search", get(routes::social::search))
        // Backpack & Cosmetics (Trendo MyBagActivity & StoreBagActivity)
        .route("/api/v1/users/me/bag", get(routes::gamification::get_my_bag))
        .route(
            "/api/v1/users/me/bag/equip",
            post(routes::gamification::equip_cosmetic),
        )
        // Level & Honor Wall (Trendo MyLevelActivity & BadgeWallActivity)
        .route(
            "/api/v1/users/me/level",
            get(routes::gamification::get_user_level),
        )
        .route(
            "/api/v1/users/me/badges",
            get(routes::gamification::get_user_badges),
        )
        // Family Guilds (Trendo)
        .route("/api/v1/family/my", get(routes::family::get_my_family))
        .route(
            "/api/v1/family/leaderboard",
            get(routes::family::get_family_leaderboard),
        )
        .route(
            "/api/v1/family/join/{family_id}",
            post(routes::family::join_family),
        )
        // Creator Payouts & KYC
        .route("/api/v1/kyc/status", get(routes::payouts::get_kyc_status))
        .route("/api/v1/kyc/submit", post(routes::payouts::submit_kyc))
        .route(
            "/api/v1/kyc/approve/{user_id}",
            post(routes::payouts::approve_kyc),
        )
        .route(
            "/api/v1/payouts/preview",
            get(routes::payouts::preview_payout),
        )
        .route(
            "/api/v1/payouts/request",
            post(routes::payouts::request_payout),
        )
        .route(
            "/api/v1/payouts/history",
            get(routes::payouts::payout_history),
        )
        // Push registration (FCM tokens; delivery follow-up needs server key)
        .route("/api/v1/push/register", post(routes::push::register_push))
        // App Config & Beauty Assets
        .route("/api/v1/config/app", get(routes::config::get_app_config))
        .route(
            "/api/v1/config/beauty/assets",
            get(routes::config::get_beauty_assets),
        )
        .layer(cors)
        .with_state(state)
}

/// Base URL clients should use to reach this deployment.
pub fn public_origin() -> String {
    std::env::var("PUBLIC_ORIGIN").unwrap_or_else(|_| "https://us.avandab.com".to_string())
}

/// Directory the API serves static media from. Kept here so the binary and any
/// embedder agree on the default without duplicating the env lookup.
pub fn media_root() -> PathBuf {
    std::env::var("MEDIA_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("/srv/camstar/media"))
}
