use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde::{Deserialize, Serialize};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ApiError {
    #[error("not found: {0}")]
    NotFound(String),

    #[error("bad request: {0}")]
    BadRequest(String),

    #[error("unauthorized: {0}")]
    Unauthorized(String),

    #[error("forbidden: {0}")]
    Forbidden(String),

    #[error("conflict: {0}")]
    Conflict(String),

    #[error("internal server error: {0}")]
    Internal(String),

    #[error("ledger error: {0}")]
    Ledger(#[from] camstar_ledger::LedgerError),

    #[error("money error: {0}")]
    Money(#[from] camstar_ledger::MoneyError),

    #[error("room error: {0:?}")]
    RoomDenial(camstar_roles::RoomDenial),

    #[error("authorization denial: {0:?}")]
    AuthDenial(camstar_roles::Denial),
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ErrorResponse {
    pub error: String,
    pub message: String,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, msg) = match self {
            ApiError::NotFound(m) => (StatusCode::NOT_FOUND, m),
            ApiError::BadRequest(m) => (StatusCode::BAD_REQUEST, m),
            ApiError::Unauthorized(m) => (StatusCode::UNAUTHORIZED, m),
            ApiError::Forbidden(m) => (StatusCode::FORBIDDEN, m),
            ApiError::Conflict(m) => (StatusCode::CONFLICT, m),
            ApiError::Internal(m) => (StatusCode::INTERNAL_SERVER_ERROR, m),
            ApiError::Ledger(err) => (StatusCode::UNPROCESSABLE_ENTITY, err.to_string()),
            ApiError::Money(err) => (StatusCode::BAD_REQUEST, err.to_string()),
            ApiError::RoomDenial(denial) => (StatusCode::FORBIDDEN, format!("room operation denied: {denial:?}")),
            ApiError::AuthDenial(denial) => (StatusCode::FORBIDDEN, format!("capability denied: {denial:?}")),
        };

        let body = Json(ErrorResponse {
            error: status.canonical_reason().unwrap_or("Error").to_string(),
            message: msg,
        });

        (status, body).into_response()
    }
}
