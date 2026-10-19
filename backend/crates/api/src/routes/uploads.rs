use axum::{body::Bytes, extract::State, http::HeaderMap, Json};
use uuid::Uuid;

use crate::{error::ApiError, state::AppState};

/// Raw-body ceiling: a 1080p JPEG poster is ~200–500 KiB; 3 MiB leaves headroom
/// while bounding one request's memory and disk write.
pub const MAX_POSTER_BYTES: usize = 3 * 1024 * 1024;

/// `POST /api/v1/uploads/poster` — room-cover upload for the Go-Live setup
/// screen (camera capture or gallery pick, JPEG-compressed client-side).
///
/// Raw bytes with `Content-Type: image/jpeg` (or `image/png`); no multipart
/// framing, so no extra dependency. Magic bytes are sniffed — the header is
/// not trusted. Files land under `{MEDIA_ROOT}/posters/` and are served by
/// the existing `/assets` static mount, so the returned URL is immediately
/// usable as `cover_image_url` in `POST /rooms/create`.
pub async fn upload_poster(
    headers: HeaderMap,
    State(state): State<AppState>,
    body: Bytes,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    if body.is_empty() || body.len() > MAX_POSTER_BYTES {
        return Err(ApiError::BadRequest("Poster must be 1 byte..3 MiB".into()));
    }
    let content_type = headers
        .get("Content-Type")
        .and_then(|h| h.to_str().ok())
        .unwrap_or("");
    // JPEG magic FFD8FF; PNG magic 89504E47. Sniffed, not trusted from header.
    let ext = match content_type.split(';').next().unwrap_or("").trim() {
        "image/jpeg" | "image/jpg" if body.starts_with(&[0xFF, 0xD8, 0xFF]) => "jpg",
        "image/png" if body.starts_with(&[0x89, 0x50, 0x4E, 0x47]) => "png",
        _ => {
            return Err(ApiError::BadRequest(
                "Poster must be a JPEG or PNG image".into(),
            ))
        }
    };

    let dir = crate::media_root().join("posters");
    tokio::fs::create_dir_all(&dir)
        .await
        .map_err(|e| ApiError::Internal(format!("poster store unavailable: {e}")))?;
    let name = format!("poster_{}.{}", Uuid::new_v4(), ext);
    tokio::fs::write(dir.join(&name), &body)
        .await
        .map_err(|e| ApiError::Internal(format!("poster write failed: {e}")))?;

    Ok(Json(serde_json::json!({
        "url": format!("{}/assets/posters/{name}", crate::public_origin()),
        "bytes": body.len(),
    })))
}
