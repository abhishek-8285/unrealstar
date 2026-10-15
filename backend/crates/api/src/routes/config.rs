use axum::{extract::State, Json};

use crate::{
    models::{AppConfigResponse, BeautyCategory},
    state::AppState,
};

pub async fn get_app_config() -> Json<AppConfigResponse> {
    Json(AppConfigResponse {
        min_version: "1.0.0".into(),
        is_cashout_enabled: true,
        rtc_app_id: "camstar_agora_livekit_app_id".into(),
        live_gateway: "https://api.camstar.live".into(),
    })
}

pub async fn get_beauty_assets(State(state): State<AppState>) -> Json<Vec<BeautyCategory>> {
    Json((*state.beauty_manifest).clone())
}
