use axum::{
    extract::{Path, State},
    http::HeaderMap,
    Json,
};

use crate::{
    error::ApiError,
    models::{FamilyItem, MyFamilyResponse},
    state::AppState,
};

/// GET /api/v1/family/my
pub async fn get_my_family(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<MyFamilyResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let user_fam_map = state.user_family.read().await;
    let fam_id = user_fam_map.get(&user_id);

    if let Some(id) = fam_id {
        let families = state.families.read().await;
        if let Some(fam) = families.iter().find(|f| f.family_id == *id) {
            return Ok(Json(MyFamilyResponse {
                family: Some(fam.clone()),
                my_role: "member".into(),
                contribution_score: 5200,
            }));
        }
    }

    Ok(Json(MyFamilyResponse {
        family: None,
        my_role: "none".into(),
        contribution_score: 0,
    }))
}

/// GET /api/v1/family/leaderboard
pub async fn get_family_leaderboard(
    State(state): State<AppState>,
) -> Json<Vec<FamilyItem>> {
    let families = state.families.read().await;
    let mut list = families.clone();
    list.sort_by_key(|f| f.rank);
    Json(list)
}

/// POST /api/v1/family/join/{family_id}
pub async fn join_family(
    headers: HeaderMap,
    Path(family_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<MyFamilyResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut families = state.families.write().await;
    let fam = families
        .iter_mut()
        .find(|f| f.family_id == family_id)
        .ok_or_else(|| ApiError::NotFound("Family not found".into()))?;

    fam.members_count += 1;
    let joined_fam = fam.clone();

    let mut user_fam_map = state.user_family.write().await;
    user_fam_map.insert(user_id, family_id);

    Ok(Json(MyFamilyResponse {
        family: Some(joined_fam),
        my_role: "member".into(),
        contribution_score: 0,
    }))
}
