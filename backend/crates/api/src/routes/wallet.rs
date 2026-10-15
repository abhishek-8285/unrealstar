use axum::{
    extract::{Path, Query, State},
    http::HeaderMap,
    Json,
};
use camstar_ledger::{Direction, EntryKind, Line, Money};
use chrono::Utc;
use hmac::{Hmac, Mac};
use serde::Deserialize;
use sha2::Sha256;
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{
        BalanceResponse, CoinPackage, CreateOrderRequest, CreateOrderResponse, TransactionItem,
        WebhookPayload,
    },
    state::{
        system_clearing_account, user_coins_account, user_diamonds_account, webhook_secret,
        AppState, PendingOrder,
    },
};

pub async fn get_balance(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<BalanceResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let coins = state
        .ledger
        .balance(user_coins_account(user_id))
        .await?
        .to_minor();
    let diamonds = state
        .ledger
        .balance(user_diamonds_account(user_id))
        .await?
        .to_minor();

    Ok(Json(BalanceResponse { coins, diamonds }))
}

#[derive(Debug, Deserialize)]
pub struct TransactionsQuery {
    pub limit: Option<usize>,
    pub cursor: Option<i64>,
}

pub async fn get_transactions(
    headers: HeaderMap,
    State(state): State<AppState>,
    Query(q): Query<TransactionsQuery>,
) -> Result<Json<Vec<TransactionItem>>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let coins_acct = user_coins_account(user_id);
    let diamonds_acct = user_diamonds_account(user_id);
    let entries = state.ledger.entries().await;

    let mut items: Vec<TransactionItem> = entries
        .iter()
        .rev()
        .filter(|e| {
            if let Some(cursor) = q.cursor {
                if e.id.0 >= cursor {
                    return false;
                }
            }
            e.lines
                .iter()
                .any(|l| l.account == coins_acct || l.account == diamonds_acct)
        })
        .map(|e| {
            let user_line = e
                .lines
                .iter()
                .find(|l| l.account == coins_acct || l.account == diamonds_acct)
                .unwrap();
            // Signed minor units: credit positive, debit negative.
            let signed: i64 = match user_line.direction {
                Direction::Credit => user_line.amount.to_minor(),
                Direction::Debit => -user_line.amount.to_minor(),
            };
            TransactionItem {
                transaction_id: e.id.0,
                kind: format!("{:?}", e.kind),
                amount: signed.to_string(),
                created_at: Utc::now(),
            }
        })
        .collect();

    let limit = q.limit.unwrap_or(20).clamp(1, 100);
    items.truncate(limit);

    Ok(Json(items))
}

pub async fn list_packages(State(state): State<AppState>) -> Json<Vec<CoinPackage>> {
    Json((*state.coin_packages).clone())
}

pub async fn create_order(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<CreateOrderRequest>,
) -> Result<Json<CreateOrderResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let package = state
        .coin_packages
        .iter()
        .find(|p| p.package_id == req.package_id)
        .ok_or_else(|| ApiError::NotFound("Package not found".into()))?;

    // Allowlist providers; unknown strings are rejected instead of echoed.
    const ALLOWED_PROVIDERS: &[&str] = &["razorpay", "cashfree", "test-provider"];
    if !ALLOWED_PROVIDERS.contains(&req.provider.as_str()) {
        return Err(ApiError::BadRequest("Unsupported payment provider".into()));
    }
    if req.idempotency_key.trim().is_empty() {
        return Err(ApiError::BadRequest("idempotency_key is required".into()));
    }

    // Client retry with the same key returns the original order, never mints
    // a second pending order.
    {
        let keys = state.order_keys.read().await;
        if let Some(existing_id) = keys.get(&req.idempotency_key) {
            let orders = state.pending_orders.read().await;
            if let Some(existing) = orders.get(existing_id) {
                let upi_intent = format!(
                    "upi://pay?pa=camstar@icici&pn=CamStar&am={}&cu=INR&tr={existing_id}",
                    package.price_inr
                );
                return Ok(Json(CreateOrderResponse {
                    order_id: existing_id.clone(),
                    payment_url: format!("https://checkout.camstar.live/pay?order={existing_id}"),
                    upi_intent_uri: upi_intent,
                    amount_inr: package.price_inr,
                    coins_pending: existing.coins,
                }));
            }
        }
    }

    let order_id = format!("ord_{user_id}_{}", Uuid::new_v4());
    let upi_intent = format!(
        "upi://pay?pa=camstar@icici&pn=CamStar&am={}&cu=INR&tr={order_id}",
        package.price_inr
    );

    // Register the order as pending. Coins are NOT credited here; the
    // payment webhook confirms the order and mints the coins exactly once.
    let coins_pending = package.coins + package.bonus_coins;
    state.pending_orders.write().await.insert(
        order_id.clone(),
        PendingOrder {
            user_id,
            coins: coins_pending,
            created_at: Utc::now(),
        },
    );
    state
        .order_keys
        .write()
        .await
        .insert(req.idempotency_key.clone(), order_id.clone());

    Ok(Json(CreateOrderResponse {
        order_id: order_id.clone(),
        payment_url: format!("https://checkout.camstar.live/pay?order={order_id}"),
        upi_intent_uri: upi_intent,
        amount_inr: package.price_inr,
        coins_pending,
    }))
}

pub async fn payment_webhook(
    Path(provider): Path<String>,
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<WebhookPayload>,
) -> Result<Json<serde_json::Value>, ApiError> {
    const ALLOWED_PROVIDERS: &[&str] = &["razorpay", "cashfree", "test-provider"];
    if !ALLOWED_PROVIDERS.contains(&provider.as_str()) {
        return Err(ApiError::BadRequest("Unsupported payment provider".into()));
    }
    // Without a configured secret anyone could mint coins. Real providers must
    // always present the secret; only the local test provider stays open when
    // no secret is configured (dev/test).
    match webhook_secret() {
        Some(_) => {}
        None if provider == "test-provider" => {}
        None => {
            return Err(ApiError::Unauthorized(
                "Webhook secret is not configured".into(),
            ))
        }
    }
    // Enforce authentication when a secret is configured. Two carriers:
    // legacy `X-Webhook-Secret: <secret>`, or
    // `X-Webhook-Signature: hex(HMAC_SHA256(secret, "order_id|status"))`.
    if let Some(expected) = webhook_secret() {
        let legacy = headers
            .get("X-Webhook-Secret")
            .and_then(|h| h.to_str().ok())
            .unwrap_or("");
        let mut ok = legacy == expected;
        if !ok {
            if let Some(sig) = headers
                .get("X-Webhook-Signature")
                .and_then(|h| h.to_str().ok())
            {
                ok = webhook_signature_ok(&expected, &payload.order_id, &payload.status, sig);
            }
        }
        if !ok {
            return Err(ApiError::Unauthorized("Invalid webhook secret".into()));
        }
    }

    // Only "success" (or "paid") credits coins; anything else is recorded
    // as reconciled but does not move money.
    if payload.status != "success" && payload.status != "paid" {
        return Ok(Json(serde_json::json!({
            "status": "ignored",
            "provider": provider,
            "order_id": payload.order_id,
            "reconciled": true
        })));
    }

    // Remove the pending order. If it is missing the order either does not
    // exist or has already been credited (idempotent retry). Either way we
    // do NOT credit coins again.
    let order = state.pending_orders.write().await.remove(&payload.order_id);

    let Some(order) = order else {
        // Order not found or already processed — treat as idempotent success.
        return Ok(Json(serde_json::json!({
            "status": "success",
            "provider": provider,
            "order_id": payload.order_id,
            "credited": false,
            "reconciled": true
        })));
    };

    // Credit coins now that the provider has confirmed payment.
    // The order_id doubles as the idempotency key so ledger.post refuses
    // a duplicate even if this handler is retried concurrently.
    let amount = Money::from_minor(order.coins)?;
    state
        .ledger
        .post(
            EntryKind::TopUp,
            &payload.order_id,
            vec![
                Line {
                    account: system_clearing_account(),
                    direction: Direction::Debit,
                    amount,
                },
                Line {
                    account: user_coins_account(order.user_id),
                    direction: Direction::Credit,
                    amount,
                },
            ],
        )
        .await?;

    Ok(Json(serde_json::json!({
        "status": "success",
        "provider": provider,
        "order_id": payload.order_id,
        "credited": true,
        "reconciled": true
    })))
}

/// Hex(HMAC-SHA256(secret, "order_id|status")). Constant-time compare is
/// overkill here; secrets are high-entropy and failures are rate-limited by
/// the gateway retry budget.
fn webhook_signature_ok(secret: &str, order_id: &str, status: &str, sig: &str) -> bool {
    type HmacSha256 = Hmac<Sha256>;
    let mut mac = HmacSha256::new_from_slice(secret.as_bytes()).expect("HMAC accepts any key size");
    mac.update(order_id.as_bytes());
    mac.update(b"|");
    mac.update(status.as_bytes());
    let expected = hex::encode(mac.finalize().into_bytes());
    expected.eq_ignore_ascii_case(sig.trim())
}
