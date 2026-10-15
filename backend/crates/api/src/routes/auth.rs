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
    state::{user_coins_account, user_diamonds_account, AppState, OtpSession},
};

pub async fn otp_send(
    State(state): State<AppState>,
    Json(req): Json<OtpSendRequest>,
) -> Result<Json<OtpSendResponse>, ApiError> {
    if req.phone_number.trim().is_empty() {
        return Err(ApiError::BadRequest("Phone number is required".into()));
    }

    let session_id = Uuid::new_v4().to_string();
    let otp_code = "123456".to_string(); // In production, integrated with SMS gateway

    let mut sessions = state.otp_sessions.write().await;
    sessions.insert(
        session_id.clone(),
        OtpSession {
            phone: req.phone_number.clone(),
            code: otp_code.clone(),
            created_at: Utc::now(),
        },
    );

    Ok(Json(OtpSendResponse {
        otp_session_id: session_id,
        retry_after_secs: 60,
        debug_otp: Some(otp_code),
    }))
}

pub async fn otp_verify(
    State(state): State<AppState>,
    Json(req): Json<OtpVerifyRequest>,
) -> Result<Json<AuthTokensResponse>, ApiError> {
    let phone = {
        let sessions = state.otp_sessions.read().await;
        let session = sessions
            .get(&req.otp_session_id)
            .ok_or_else(|| ApiError::BadRequest("Invalid or expired OTP session".into()))?;

        if session.code != req.otp_code {
            return Err(ApiError::BadRequest("Incorrect OTP code".into()));
        }
        session.phone.clone()
    };

    let (user_id, is_new) = {
        let phone_map = state.phone_to_user.read().await;
        if let Some(&uid) = phone_map.get(&phone) {
            (uid, false)
        } else {
            drop(phone_map);
            let mut phone_map = state.phone_to_user.write().await;
            let mut users = state.users.write().await;
            let mut ledger = state.ledger.write().await;

            let uid = (users.len() as u64) + 1;

            // Register accounts in ledger
            ledger.register_account(user_coins_account(uid), camstar_ledger::AccountKind::Owned);
            ledger.register_account(user_diamonds_account(uid), camstar_ledger::AccountKind::Owned);

            let profile = UserProfile {
                user_id: uid,
                phone: phone.clone(),
                nickname: format!("Star_{uid}"),
                avatar_url: format!("https://cdn.camstar.live/avatars/{uid}.png"),
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
                live_room_id: None, likes_received: 0,
            };

            users.insert(uid, profile);
            phone_map.insert(phone, uid);
            (uid, true)
        }
    };

    let access_token = format!("tok_{user_id}_{}", Uuid::new_v4());
    let refresh_token = format!("ref_{user_id}_{}", Uuid::new_v4());

    let mut tokens = state.tokens_to_user.write().await;
    tokens.insert(access_token.clone(), user_id);
    tokens.insert(refresh_token.clone(), user_id);

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
    let mut tokens = state.tokens_to_user.write().await;
    let user_id = tokens
        .get(&req.refresh_token)
        .copied()
        .ok_or_else(|| ApiError::Unauthorized("Invalid refresh token".into()))?;

    // Invalidate old refresh token and issue new pair
    tokens.remove(&req.refresh_token);

    let access_token = format!("tok_{user_id}_{}", Uuid::new_v4());
    let refresh_token = format!("ref_{user_id}_{}", Uuid::new_v4());

    tokens.insert(access_token.clone(), user_id);
    tokens.insert(refresh_token.clone(), user_id);

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
    let mut tokens = state.tokens_to_user.write().await;
    tokens.remove(&req.refresh_token);
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

pub async fn get_user(
    Path(user_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<UserProfile>, ApiError> {
    let users = state.users.read().await;
    let profile = users
        .get(&user_id)
        .cloned()
        .ok_or_else(|| ApiError::NotFound(format!("User {user_id} not found")))?;

    Ok(Json(profile))
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
        diamonds: 7_500_000.0,
        stats: MeStats {
            videos_watched: 0,
            lives_watched: 0,
            time_spent_minutes: 0,
            active_days: 0,
        },
    }))
}
