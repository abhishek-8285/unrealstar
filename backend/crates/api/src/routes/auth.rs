use axum::{
    extract::{Path, State},
    http::HeaderMap,
    Json,
};
use chrono::Utc;
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{
        AuthTokensResponse, LogoutRequest, MeDashboard, MeStats, OtpSendRequest, OtpSendResponse,
        OtpVerifyRequest, RefreshTokenRequest, UpdateProfileRequest, UserProfile,
    },
    state::{
        access_token_ttl_secs, refresh_token_ttl_secs, user_coins_account, user_diamonds_account,
        AppState, OtpSession, TokenKind, TokenRecord,
    },
};

/// Codes must arrive within 5 minutes or the session is dropped.
const OTP_TTL_SECS: i64 = 300;
/// Minimum gap between two OTP sends for the same phone number.
const OTP_RESEND_COOLDOWN_SECS: i64 = 60;
/// Failed verifies before the session is destroyed (brute-force guard).
const OTP_MAX_ATTEMPTS: u32 = 5;

/// A fresh six-digit code derived from the UUID entropy pool. No global
/// `rand` dependency needed; `Uuid::new_v4` uses the OS CSPRNG.
fn random_otp() -> String {
    let n = u32::from_be_bytes(Uuid::new_v4().into_bytes()[..4].try_into().unwrap()) % 1_000_000;
    format!("{n:06}")
}

/// E.164 sanity shared with the Android client: optional leading `+`,
/// then 7–15 ASCII digits. Rejects letters, spaces, and separators so one
/// identity cannot fan out into several phone records.
fn is_valid_e164(phone: &str) -> bool {
    let digits = phone.strip_prefix('+').unwrap_or(phone);
    !digits.is_empty()
        && digits.len() <= 15
        && digits.len() >= 7
        && digits.bytes().all(|b| b.is_ascii_digit())
}

/// `debug_otp` is returned only when explicitly enabled: always in debug
/// builds so local development and tests work, or via
/// `CAMSTAR_DEBUG_OTP=1` in a release build. Production leaves it unset so
/// codes are never echoed in HTTP responses.
fn debug_otp_enabled() -> bool {
    cfg!(debug_assertions)
        || matches!(
            std::env::var("CAMSTAR_DEBUG_OTP").as_deref(),
            Ok("1") | Ok("true") | Ok("TRUE") | Ok("yes") | Ok("YES")
        )
}

/// Mint a matched access/refresh pair under one session id. The refresh
/// token can never authenticate requests; rotation and logout revoke the
/// whole session by its id.
async fn issue_session(state: &AppState, user_id: u64) -> (String, String) {
    let session_id = Uuid::new_v4();
    let now = Utc::now();
    let access_token = format!("tok_{user_id}_{session_id}");
    let refresh_token = format!("ref_{user_id}_{session_id}");

    let mut tokens = state.tokens_to_user.write().await;
    tokens.insert(
        access_token.clone(),
        TokenRecord {
            user_id,
            session_id,
            kind: TokenKind::Access,
            expires_at: now + chrono::Duration::seconds(access_token_ttl_secs() as i64),
        },
    );
    tokens.insert(
        refresh_token.clone(),
        TokenRecord {
            user_id,
            session_id,
            kind: TokenKind::Refresh,
            expires_at: now + chrono::Duration::seconds(refresh_token_ttl_secs() as i64),
        },
    );
    (access_token, refresh_token)
}

pub async fn otp_send(
    State(state): State<AppState>,
    Json(req): Json<OtpSendRequest>,
) -> Result<Json<OtpSendResponse>, ApiError> {
    let phone = req.phone_number.trim().to_string();
    // E.164 sanity: optional +, 7–15 digits. Anything else wastes SMS
    // budget and can split identity server-side (same user, two records).
    if !is_valid_e164(&phone) {
        return Err(ApiError::BadRequest(
            "Phone number must be 7-15 digits, optional leading +".into(),
        ));
    }

    let now = Utc::now();
    let mut sessions = state.otp_sessions.write().await;

    // Opportunistic prune: nothing past the TTL can ever verify, so drop it.
    sessions.retain(|_, s| now.signed_duration_since(s.created_at).num_seconds() <= OTP_TTL_SECS);

    // Resend cooldown per phone number.
    let last_sent = sessions
        .values()
        .filter(|s| s.phone == phone)
        .map(|s| s.created_at)
        .max();
    if let Some(last) = last_sent {
        let age = now.signed_duration_since(last).num_seconds();
        if age < OTP_RESEND_COOLDOWN_SECS {
            return Err(ApiError::BadRequest(format!(
                "OTP already sent, retry in {}s",
                OTP_RESEND_COOLDOWN_SECS - age
            )));
        }
    }

    let session_id = Uuid::new_v4().to_string();
    let otp_code = random_otp(); // No SMS gateway yet: code is generated in-process.
    sessions.insert(
        session_id.clone(),
        OtpSession {
            phone,
            code: otp_code.clone(),
            created_at: now,
            attempts: 0,
        },
    );
    drop(sessions);

    Ok(Json(OtpSendResponse {
        otp_session_id: session_id,
        retry_after_secs: OTP_RESEND_COOLDOWN_SECS as u32,
        debug_otp: debug_otp_enabled().then_some(otp_code),
    }))
}

pub async fn otp_verify(
    State(state): State<AppState>,
    Json(req): Json<OtpVerifyRequest>,
) -> Result<Json<AuthTokensResponse>, ApiError> {
    // Validate expiry/code and count the attempt under a single write lock.
    // Everything needed after the lock is copied out first so we never hold
    // `sessions` across other lock acquisitions.
    let phone = {
        let mut sessions = state.otp_sessions.write().await;
        let now = Utc::now();
        let (expired, code_ok, attempts, phone) = {
            let session = sessions
                .get(&req.otp_session_id)
                .ok_or_else(|| ApiError::BadRequest("Invalid or expired OTP session".into()))?;
            (
                now.signed_duration_since(session.created_at).num_seconds() > OTP_TTL_SECS,
                session.code == req.otp_code,
                session.attempts,
                session.phone.clone(),
            )
        };

        if expired {
            sessions.remove(&req.otp_session_id);
            return Err(ApiError::BadRequest("OTP session expired".into()));
        }

        if !code_ok {
            let attempts = attempts + 1;
            if attempts >= OTP_MAX_ATTEMPTS {
                sessions.remove(&req.otp_session_id);
                return Err(ApiError::BadRequest("Too many incorrect attempts".into()));
            }
            if let Some(session) = sessions.get_mut(&req.otp_session_id) {
                session.attempts = attempts;
            }
            return Err(ApiError::BadRequest("Incorrect OTP code".into()));
        }

        // Single-use: a verified session can never be replayed.
        sessions.remove(&req.otp_session_id);
        phone
    };

    // Lock order matches `create_user`: users -> phone_to_user -> ledger.
    let (user_id, is_new) = {
        let mut users = state.users.write().await;
        let mut phone_map = state.phone_to_user.write().await;

        if let Some(&uid) = phone_map.get(&phone) {
            (uid, false)
        } else {
            // Highest existing id, not count: seeded demo users (1001, 9999)
            // must not collide with the next registered account.
            let uid = users.keys().max().copied().unwrap_or(0) + 1;

            state
                .ledger
                .register_account(user_coins_account(uid), camstar_ledger::AccountKind::Owned)
                .await?;
            state
                .ledger
                .register_account(
                    user_diamonds_account(uid),
                    camstar_ledger::AccountKind::Owned,
                )
                .await?;

            let origin = crate::public_origin();
            let profile = UserProfile {
                user_id: uid,
                phone: phone.clone(),
                nickname: format!("Star_{uid}"),
                avatar_url: format!("{origin}/assets/avatars/a{}.jpg", (uid % 10) + 1),
                bio: "Just joined CamStar!".into(),
                gender: "unspecified".into(),
                city: "Mumbai".into(),
                age: 21,
                can_publish: true,
                creator_status: camstar_roles::CreatorStatus::Viewer,
                wealth_tier: camstar_roles::Tier::Newcomer,
                vip_level: camstar_roles::VipLevel::None,
                followers_count: 0,
                following_count: 0,
                is_live: false,
                live_room_id: None,
                likes_received: 0,
            };

            users.insert(uid, profile);
            phone_map.insert(phone, uid);
            (uid, true)
        }
    };

    let (access_token, refresh_token) = issue_session(&state, user_id).await;

    Ok(Json(AuthTokensResponse {
        access_token,
        refresh_token,
        user_id,
        is_new_user: is_new,
    }))
}

pub async fn token_refresh(
    State(state): State<AppState>,
    Json(req): Json<RefreshTokenRequest>,
) -> Result<Json<AuthTokensResponse>, ApiError> {
    let now = Utc::now();

    // Look up under a read borrow, then revoke the whole old session and
    // mint the new pair — refresh tokens are single-use (rotation).
    let (user_id, session_id) = {
        let tokens = state.tokens_to_user.read().await;
        let rec = tokens
            .get(&req.refresh_token)
            .ok_or_else(|| ApiError::Unauthorized("Invalid refresh token".into()))?;
        if rec.kind != TokenKind::Refresh || rec.expires_at <= now {
            return Err(ApiError::Unauthorized("Invalid refresh token".into()));
        }
        (rec.user_id, rec.session_id)
    };

    {
        let mut tokens = state.tokens_to_user.write().await;
        tokens.retain(|_, rec| rec.session_id != session_id);
    }

    let (access_token, refresh_token) = issue_session(&state, user_id).await;

    Ok(Json(AuthTokensResponse {
        access_token,
        refresh_token,
        user_id,
        is_new_user: false,
    }))
}

pub async fn logout(
    State(state): State<AppState>,
    Json(req): Json<LogoutRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    // Revoke every token minted in the session the refresh token belongs
    // to (access + refresh). Unknown tokens are a no-op: logout is
    // idempotent.
    let mut tokens = state.tokens_to_user.write().await;
    if let Some(rec) = tokens.get(&req.refresh_token) {
        let session_id = rec.session_id;
        tokens.retain(|_, rec| rec.session_id != session_id);
    }
    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn get_me(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<UserProfile>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let users = state.users.read().await;
    let profile = users
        .get(&user_id)
        .cloned()
        .ok_or_else(|| ApiError::NotFound("User not found".into()))?;

    Ok(Json(profile))
}

pub async fn update_me(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<UpdateProfileRequest>,
) -> Result<Json<UserProfile>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut users = state.users.write().await;
    let profile = users
        .get_mut(&user_id)
        .ok_or_else(|| ApiError::NotFound("User not found".into()))?;

    if let Some(nick) = req.nickname {
        profile.nickname = nick;
    }
    if let Some(avatar) = req.avatar_url {
        profile.avatar_url = avatar;
    }
    if let Some(bio) = req.bio {
        profile.bio = bio;
    }
    if let Some(gender) = req.gender {
        profile.gender = gender;
    }
    if let Some(city) = req.city {
        profile.city = city;
    }

    Ok(Json(profile.clone()))
}

/// Public profile lookup. The `UserProfile` contract stays untouched (see the
/// note on `get_me_dashboard`); the per-caller follow state is added on the
/// serialized value instead, so `is_following` is present here and only here.
pub async fn get_user(
    headers: HeaderMap,
    Path(user_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let users = state.users.read().await;
    let profile = users
        .get(&user_id)
        .cloned()
        .ok_or_else(|| ApiError::NotFound(format!("User {user_id} not found")))?;
    drop(users);

    let mut value = serde_json::to_value(&profile)
        .map_err(|e| ApiError::Internal(format!("serialize profile: {e}")))?;

    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    if let Some(viewer) = state.authenticate_header(auth_header).await {
        if viewer != user_id {
            let follows = state.follows.read().await;
            let is_following = follows
                .get(&viewer)
                .map(|set| set.contains(&user_id))
                .unwrap_or(false);
            value["is_following"] = serde_json::json!(is_following);
        }
    }

    Ok(Json(value))
}

/// Payload for the Me tab. Kept separate from `UserProfile` so the profile
/// contract used by follow/lookup stays untouched.
pub async fn get_me_dashboard(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<MeDashboard>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let origin = crate::public_origin();
    let users = state.users.read().await;

    let profile = users.get(&user_id).cloned();
    let nickname = profile
        .as_ref()
        .map(|p| p.nickname.clone())
        .unwrap_or_else(|| format!("User_{user_id}"));
    let bio = profile.as_ref().map(|p| p.bio.clone()).unwrap_or_default();
    let age = profile.as_ref().map(|p| p.age).unwrap_or(0);
    let gender = profile
        .as_ref()
        .map(|p| p.gender.clone())
        .unwrap_or_else(|| "unspecified".into());
    let vip_level = profile
        .as_ref()
        .map(|p| format!("{:?}", p.vip_level))
        .unwrap_or_else(|| "None".into());
    let followers = profile.as_ref().map(|p| p.followers_count).unwrap_or(0);
    let following = profile.as_ref().map(|p| p.following_count).unwrap_or(0);

    // Avatar: prefer a stored URL, else fall back to the seeded asset set.
    let avatar = match profile.as_ref().map(|p| p.avatar_url.clone()) {
        Some(url) if !url.is_empty() => url,
        _ => format!("{origin}/assets/avatars/a1.jpg"),
    };

    // Engagement totals are derived from live state where available so the
    // numbers move when the user actually likes or gifts.
    let likes_count = profile.as_ref().map(|p| p.likes_received).unwrap_or(0);

    // Real diamond balance from the ledger — never a demo constant.
    drop(users);
    let diamonds = state
        .ledger
        .balance(user_diamonds_account(user_id))
        .await
        .map(|m| m.to_minor() as f64)
        .unwrap_or(0.0)
        .max(0.0);

    Ok(Json(MeDashboard {
        user_id,
        nickname,
        avatar_url: avatar,
        bio,
        display_id: "Nakhri".into(),
        age,
        gender,
        level: 48,
        vip_level,
        following_count: following,
        followers_count: followers,
        likes_count,
        diamonds,
        stats: MeStats {
            videos_watched: 0,
            lives_watched: 0,
            time_spent_minutes: 0,
            active_days: 0,
        },
    }))
}
