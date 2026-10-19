use axum::{extract::State, http::HeaderMap, Json};
use hmac::{Hmac, Mac};
use sha1::Sha1;

use crate::{
    error::ApiError,
    models::{IceServerEntry, IceServersResponse},
    state::AppState,
};

/// Credential TTL: short enough that a leaked password dies on its own.
pub const TURN_TTL_SECS: u64 = 24 * 3600;

/// `GET /api/v1/rtc/ice-servers` — per-user ICE configuration.
///
/// Always includes public STUN. A TURN relay entry is appended only when the
/// deployment sets both `CAMSTAR_TURN_URL` (e.g. `turn:turn.camstar.live:3478`)
/// and `CAMSTAR_TURN_SECRET` (the same static-auth-secret in coturn.conf).
/// Without them the response is STUN-only and clients fall back to direct
/// paths — symmetric-NAT viewers then cannot connect, which is a deployment
/// gap, not a client bug.
///
/// Credentials follow coturn's time-limited REST scheme so no long-lived
/// password ever ships: `username = "<expiry_epoch>:<user_id>"`,
/// `password = base64(HMAC-SHA1(secret, username))`.
pub async fn ice_servers(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<IceServersResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut servers = vec![IceServerEntry {
        urls: vec!["stun:stun.l.google.com:19302".into()],
        username: None,
        credential: None,
    }];

    let turn_url = std::env::var("CAMSTAR_TURN_URL")
        .map(|s| s.trim().to_string())
        .unwrap_or_default();
    let turn_secret = std::env::var("CAMSTAR_TURN_SECRET").unwrap_or_default();
    if !turn_url.is_empty() && !turn_secret.is_empty() {
        let expiry = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0)
            + TURN_TTL_SECS;
        let username = format!("{expiry}:{user_id}");
        let mut mac = Hmac::<Sha1>::new_from_slice(turn_secret.as_bytes())
            .map_err(|_| ApiError::Internal("TURN secret rejected".into()))?;
        mac.update(username.as_bytes());
        let password = base64::Engine::encode(
            &base64::engine::general_purpose::STANDARD,
            mac.finalize().into_bytes(),
        );
        servers.push(IceServerEntry {
            urls: vec![turn_url],
            username: Some(username),
            credential: Some(password),
        });
    }

    Ok(Json(IceServersResponse {
        servers,
        ttl_secs: TURN_TTL_SECS,
    }))
}
