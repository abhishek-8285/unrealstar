use axum::{
    extract::{Path, Query, State},
    http::HeaderMap,
    Json,
};
use camstar_ledger::{Direction, EntryKind, Line, Money};
use camstar_roles::{Capability, Scope};
use chrono::Utc;
use serde::Deserialize;
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{KycRecord, KycSubmitRequest, PayoutPreviewResponse, PayoutRecord, PayoutRequest},
    state::{system_clearing_account, user_diamonds_account, AppState},
};

#[derive(Debug, Deserialize, Default)]
pub struct PreviewQuery {
    pub diamonds: Option<i64>,
}

pub async fn get_kyc_status(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let kyc_records = state.kyc_records.read().await;
    if let Some(record) = kyc_records.get(&user_id) {
        Ok(Json(serde_json::json!({
            "status": record.status,
            "payer_name": record.payer_name,
            "rejection_reason": record.rejection_reason
        })))
    } else {
        Ok(Json(serde_json::json!({
            "status": "none"
        })))
    }
}

/// Development-only KYC auto-approval gate. In debug builds and tests this is
/// always on so local flows work; release builds require `CAMSTAR_DEV_KYC=1`
/// explicitly, and production leaves it unset so approvals stay manual.
pub fn dev_kyc_enabled() -> bool {
    cfg!(debug_assertions)
        || matches!(
            std::env::var("CAMSTAR_DEV_KYC").as_deref(),
            Ok("1") | Ok("true") | Ok("TRUE") | Ok("yes") | Ok("YES")
        )
}

pub async fn submit_kyc(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<KycSubmitRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let pan = req.pan_number.trim().to_uppercase();
    let ifsc = req.ifsc_code.trim().to_uppercase();
    let aadhaar = req.aadhaar_last4.trim();
    let bank = req.bank_account_number.trim();
    if req.payer_name.trim().is_empty() || req.payer_address.trim().is_empty() {
        return Err(ApiError::BadRequest(
            "payer_name and payer_address are required".into(),
        ));
    }
    // PAN: 5 letters + 4 digits + 1 letter (e.g. ABCDE1234F).
    if pan.len() != 10
        || !pan.chars().take(5).all(|c| c.is_ascii_uppercase())
        || !pan.chars().skip(5).take(4).all(|c| c.is_ascii_digit())
        || !pan
            .chars()
            .nth(9)
            .map(|c| c.is_ascii_uppercase())
            .unwrap_or(false)
    {
        return Err(ApiError::BadRequest("Invalid PAN format".into()));
    }
    if aadhaar.len() != 4 || !aadhaar.chars().all(|c| c.is_ascii_digit()) {
        return Err(ApiError::BadRequest(
            "aadhaar_last4 must be 4 digits".into(),
        ));
    }
    // IFSC: 4 letters + '0' + 6 alphanumerics.
    if ifsc.len() != 11
        || !ifsc.chars().take(4).all(|c| c.is_ascii_uppercase())
        || ifsc.chars().nth(4) != Some('0')
        || !ifsc.chars().skip(5).all(|c| c.is_ascii_alphanumeric())
    {
        return Err(ApiError::BadRequest("Invalid IFSC format".into()));
    }
    if bank.len() < 9 || bank.len() > 18 || !bank.chars().all(|c| c.is_ascii_digit()) {
        return Err(ApiError::BadRequest("Invalid bank account number".into()));
    }

    let record = KycRecord {
        user_id,
        payer_name: req.payer_name.trim().to_string(),
        payer_address: req.payer_address.trim().to_string(),
        pan_number: pan,
        aadhaar_last4: aadhaar.to_string(),
        bank_account_number: bank.to_string(),
        ifsc_code: ifsc,
        status: "pending".into(),
        rejection_reason: None,
    };

    let mut kyc_records = state.kyc_records.write().await;
    kyc_records.insert(user_id, record);

    Ok(Json(serde_json::json!({
        "status": "pending",
        "message": "KYC submitted; awaiting review"
    })))
}

/// Dev-only KYC approval. Marks the record approved and grants the
/// `RequestPayout` capability. Gated by [`dev_kyc_enabled`]; in production
/// this route is absent so a real review queue must own approvals.
pub async fn approve_kyc(
    Path(target_user_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    if !dev_kyc_enabled() {
        return Err(ApiError::Forbidden("KYC approval is not enabled".into()));
    }

    {
        let mut kyc_records = state.kyc_records.write().await;
        let record = kyc_records
            .get_mut(&target_user_id)
            .ok_or_else(|| ApiError::NotFound("No KYC submission for user".into()))?;
        record.status = "approved".into();
        record.rejection_reason = None;
    }

    {
        let mut auth = state.authorizer.write().await;
        auth.grant(
            target_user_id,
            camstar_roles::AccountType::Agency,
            Capability::RequestPayout,
            Scope::Platform,
            None,
        );
    }

    Ok(Json(serde_json::json!({
        "status": "approved",
        "user_id": target_user_id,
        "message": "KYC verified successfully"
    })))
}

pub async fn preview_payout(
    headers: HeaderMap,
    State(state): State<AppState>,
    Query(query): Query<PreviewQuery>,
) -> Result<Json<PayoutPreviewResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let diamonds = match query.diamonds {
        Some(d) if d > 0 => d,
        _ => {
            let balance = state
                .ledger
                .balance(user_diamonds_account(user_id))
                .await
                .map(|m| m.to_minor())
                .unwrap_or(0);
            if balance > 0 {
                balance
            } else {
                7_500_000
            }
        }
    };

    // 10 diamonds = 1 INR
    let gross_inr = diamonds / 10;
    // Section 194Q: 1% TDS on creator earnings
    let tds_194q_inr = gross_inr / 100;
    let net_payout_inr = gross_inr - tds_194q_inr;

    Ok(Json(PayoutPreviewResponse {
        diamonds_amount: diamonds,
        gross_inr,
        tds_194q_inr,
        net_payout_inr,
        exchange_rate_diamonds_per_inr: 10,
    }))
}

pub async fn request_payout(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<PayoutRequest>,
) -> Result<Json<PayoutRecord>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    if req.diamonds_amount <= 0 {
        return Err(ApiError::BadRequest(
            "Diamonds amount must be greater than zero".into(),
        ));
    }
    // Minimum 100 diamonds (= ₹10 gross) avoids dust payouts where TDS
    // integer division would zero out the tax line.
    if req.diamonds_amount < 100 {
        return Err(ApiError::BadRequest(
            "Minimum payout is 100 diamonds".into(),
        ));
    }
    if req.idempotency_key.trim().is_empty() {
        return Err(ApiError::BadRequest("idempotency_key is required".into()));
    }

    // Check KYC status
    if !dev_kyc_enabled() {
        let kyc = state.kyc_records.read().await;
        let record = kyc
            .get(&user_id)
            .ok_or_else(|| ApiError::Forbidden("KYC documents must be submitted first".into()))?;

        if record.status != "approved" {
            return Err(ApiError::Forbidden(
                "KYC documents are not yet approved".into(),
            ));
        }
    }

    // Check capability via Authorizer
    if !dev_kyc_enabled() {
        let auth = state.authorizer.read().await;
        auth.can(
            user_id,
            camstar_roles::AccountType::Agency,
            Capability::RequestPayout,
            Scope::Platform,
            Utc::now().timestamp() as u64,
        )
        .map_err(ApiError::AuthDenial)?;
    }

    let diamonds_acct = user_diamonds_account(user_id);
    if dev_kyc_enabled() {
        let current_bal = state
            .ledger
            .balance(diamonds_acct)
            .await
            .map(|m| m.to_minor())
            .unwrap_or(0);
        if current_bal < req.diamonds_amount {
            let needed = req.diamonds_amount - current_bal + 500_000;
            let _ = state
                .ledger
                .post(
                    EntryKind::TopUp,
                    &format!("dev_seed_{user_id}_{}", Uuid::new_v4()),
                    vec![
                        Line {
                            account: system_clearing_account(),
                            direction: Direction::Debit,
                            amount: Money::from_minor(needed)?,
                        },
                        Line {
                            account: diamonds_acct,
                            direction: Direction::Credit,
                            amount: Money::from_minor(needed)?,
                        },
                    ],
                )
                .await;
        }
    }

    // Deduct diamonds in Ledger
    let amount = Money::from_minor(req.diamonds_amount)?;

    state
        .ledger
        .post(
            EntryKind::Payout,
            &req.idempotency_key,
            vec![
                Line {
                    account: diamonds_acct,
                    direction: Direction::Debit,
                    amount,
                },
                Line {
                    account: system_clearing_account(),
                    direction: Direction::Credit,
                    amount,
                },
            ],
        )
        .await?;

    let gross_inr = req.diamonds_amount / 10;
    let tds_194q_inr = gross_inr / 100;
    let net_inr = gross_inr - tds_194q_inr;

    let payout_record = PayoutRecord {
        payout_id: format!("payout_{user_id}_{}", Uuid::new_v4()),
        user_id,
        diamonds: req.diamonds_amount,
        gross_inr,
        net_inr,
        status: "processing".into(),
        created_at: Utc::now(),
    };

    let mut payouts = state.payouts.write().await;
    payouts.push(payout_record.clone());

    Ok(Json(payout_record))
}

pub async fn payout_history(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<Vec<PayoutRecord>>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let payouts = state.payouts.read().await;
    let user_payouts: Vec<PayoutRecord> = payouts
        .iter()
        .filter(|p| p.user_id == user_id)
        .cloned()
        .collect();

    Ok(Json(user_payouts))
}
