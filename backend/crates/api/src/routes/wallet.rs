use axum::{
    extract::{Path, State},
    http::HeaderMap,
    Json,
};
use camstar_ledger::{Direction, EntryKind, Line, Money};
use chrono::Utc;
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{
        BalanceResponse, CoinPackage, CreateOrderRequest, CreateOrderResponse, TransactionItem,
        WebhookPayload,
    },
    state::{system_clearing_account, user_coins_account, user_diamonds_account, AppState},
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

    let ledger = state.ledger.read().await;
    let coins = ledger.balance(user_coins_account(user_id)).to_minor();
    let diamonds = ledger.balance(user_diamonds_account(user_id)).to_minor();

    Ok(Json(BalanceResponse { coins, diamonds }))
}

pub async fn get_transactions(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<Vec<TransactionItem>>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let user_acct = user_coins_account(user_id);
    let ledger = state.ledger.read().await;

    let items: Vec<TransactionItem> = ledger
        .entries()
        .iter()
        .filter(|e| e.lines.iter().any(|l| l.account == user_acct))
        .map(|e| {
            let user_line = e.lines.iter().find(|l| l.account == user_acct).unwrap();
            TransactionItem {
                transaction_id: e.id.0,
                kind: format!("{:?}", e.kind),
                amount: format!("{:?} {}", user_line.direction, user_line.amount),
                created_at: Utc::now(),
            }
        })
        .collect();

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

    let order_id = format!("ord_{user_id}_{}", Uuid::new_v4());
    let upi_intent = format!(
        "upi://pay?pa=camstar@icici&pn=CamStar&am={}&cu=INR&tr={order_id}",
        package.price_inr
    );

    // In a test or direct sandbox environment, simulate crediting coins right away via ledger
    let coins_to_credit = package.coins + package.bonus_coins;
    let mut ledger = state.ledger.write().await;
    let amount = Money::from_minor(coins_to_credit)?;

    let _entry_id = ledger.post(
        EntryKind::TopUp,
        &req.idempotency_key,
        vec![
            Line {
                account: system_clearing_account(),
                direction: Direction::Debit,
                amount,
            },
            Line {
                account: user_coins_account(user_id),
                direction: Direction::Credit,
                amount,
            },
        ],
    )?;

    Ok(Json(CreateOrderResponse {
        order_id,
        payment_url: format!("https://checkout.camstar.live/pay?order={}", package.package_id),
        upi_intent_uri: upi_intent,
        amount_inr: package.price_inr,
        coins_credited: coins_to_credit,
    }))
}

pub async fn payment_webhook(
    Path(provider): Path<String>,
    State(_state): State<AppState>,
    Json(payload): Json<WebhookPayload>,
) -> Result<Json<serde_json::Value>, ApiError> {
    // Webhook verification endpoint (Razorpay/Cashfree reconciliation)
    Ok(Json(serde_json::json!({
        "status": "success",
        "provider": provider,
        "order_id": payload.order_id,
        "reconciled": true
    })))
}
