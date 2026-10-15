use crate::ledger::{AccountId, AccountKind, Direction, EntryId, EntryKind, LedgerError, Line};
use crate::money::Money;
use sqlx::{PgPool, Row};

/// Persistent double-entry PostgreSQL ledger for CamStar.
/// Guarantees:
/// 1. ACID transactions on all multi-line entries.
/// 2. Idempotent mutations via unique constraints on idempotency keys.
/// 3. Zero-balance enforcement across debits and credits.
/// 4. Non-negative balances for Owned accounts.
#[derive(Clone, Debug)]
pub struct PostgresLedger {
    pool: PgPool,
}

impl PostgresLedger {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    pub async fn migrate(&self) -> Result<(), sqlx::migrate::MigrateError> {
        sqlx::migrate!("./migrations").run(&self.pool).await
    }

    pub async fn register_account(&self, account: AccountId, kind: AccountKind) -> Result<(), LedgerError> {
        let kind_str = match kind {
            AccountKind::Owned => "Owned",
            AccountKind::External => "External",
        };
        sqlx::query(
            "INSERT INTO accounts (id, kind) VALUES ($1, $2) \
             ON CONFLICT (id) DO UPDATE SET kind = EXCLUDED.kind"
        )
        .bind(account.0)
        .bind(kind_str)
        .execute(&self.pool)
        .await
        .map_err(|e| LedgerError::Database(e.to_string()))?;

        Ok(())
    }

    pub async fn account_kind(&self, account: AccountId) -> Result<AccountKind, LedgerError> {
        let row = sqlx::query("SELECT kind FROM accounts WHERE id = $1")
            .bind(account.0)
            .fetch_optional(&self.pool)
            .await
            .map_err(|e| LedgerError::Database(e.to_string()))?;

        match row {
            Some(r) => {
                let k: String = r.get("kind");
                if k == "External" {
                    Ok(AccountKind::External)
                } else {
                    Ok(AccountKind::Owned)
                }
            }
            None => Ok(AccountKind::Owned),
        }
    }

    pub async fn balance_of(&self, account: AccountId) -> Result<Money, LedgerError> {
        let row = sqlx::query(
            "SELECT COALESCE(SUM(CASE WHEN direction = 'Credit' THEN amount_paise ELSE -amount_paise END), 0)::BIGINT as balance \
             FROM lines WHERE account_id = $1"
        )
        .bind(account.0)
        .fetch_one(&self.pool)
        .await
        .map_err(|e| LedgerError::Database(e.to_string()))?;

        let balance_paise: i64 = row.get("balance");
        Money::from_minor(balance_paise).map_err(LedgerError::from)
    }

    pub async fn post(
        &self,
        idempotency_key: &str,
        kind: EntryKind,
        lines: &[Line],
    ) -> Result<EntryId, LedgerError> {
        if lines.len() < 2 {
            return Err(LedgerError::TooFewLines);
        }

        let mut total_debits = Money::zero();
        let mut total_credits = Money::zero();

        for line in lines {
            if line.amount == Money::zero() {
                return Err(LedgerError::ZeroAmountLine);
            }
            match line.direction {
                Direction::Debit => total_debits = total_debits.checked_add(line.amount)?,
                Direction::Credit => total_credits = total_credits.checked_add(line.amount)?,
            }
        }

        if total_debits != total_credits {
            return Err(LedgerError::Unbalanced);
        }

        let mut tx = self.pool.begin().await.map_err(|e| LedgerError::Database(e.to_string()))?;

        // 1. Check idempotency
        let existing = sqlx::query("SELECT id FROM entries WHERE idempotency_key = $1")
            .bind(idempotency_key)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|e| LedgerError::Database(e.to_string()))?;

        if let Some(row) = existing {
            let id: i64 = row.get("id");
            tx.rollback().await.map_err(|e| LedgerError::Database(e.to_string()))?;
            return Ok(EntryId(id));
        }

        // 2. Validate sufficient funds for all owned accounts being debited
        for line in lines {
            if line.direction == Direction::Debit {
                let kind_row = sqlx::query("SELECT kind FROM accounts WHERE id = $1")
                    .bind(line.account.0)
                    .fetch_optional(&mut *tx)
                    .await
                    .map_err(|e| LedgerError::Database(e.to_string()))?;

                let is_external = match kind_row {
                    Some(r) => {
                        let k: String = r.get("kind");
                        k == "External"
                    }
                    None => false,
                };

                if !is_external {
                    let bal_row = sqlx::query(
                        "SELECT COALESCE(SUM(CASE WHEN direction = 'Credit' THEN amount_paise ELSE -amount_paise END), 0)::BIGINT as balance \
                         FROM lines WHERE account_id = $1"
                    )
                    .bind(line.account.0)
                    .fetch_one(&mut *tx)
                    .await
                    .map_err(|e| LedgerError::Database(e.to_string()))?;

                    let current_balance_paise: i64 = bal_row.get("balance");
                    let available = Money::from_minor(current_balance_paise)?;
                    if available < line.amount {
                        tx.rollback().await.map_err(|e| LedgerError::Database(e.to_string()))?;
                        return Err(LedgerError::InsufficientFunds {
                            account: line.account,
                            available,
                            requested: line.amount,
                        });
                    }
                }
            }
        }

        // 3. Ensure all accounts exist in accounts table
        for line in lines {
            sqlx::query("INSERT INTO accounts (id, kind) VALUES ($1, 'Owned') ON CONFLICT (id) DO NOTHING")
                .bind(line.account.0)
                .execute(&mut *tx)
                .await
                .map_err(|e| LedgerError::Database(e.to_string()))?;
        }

        // 4. Insert entry
        let kind_str = match kind {
            EntryKind::TopUp => "TopUp",
            EntryKind::GiftSpend => "GiftSpend",
            EntryKind::Payout => "Payout",
            EntryKind::Reversal => "Reversal",
            EntryKind::Adjustment => "Adjustment",
        };

        let entry_row = sqlx::query("INSERT INTO entries (idempotency_key, kind) VALUES ($1, $2) RETURNING id")
            .bind(idempotency_key)
            .bind(kind_str)
            .fetch_one(&mut *tx)
            .await
            .map_err(|e| LedgerError::Database(e.to_string()))?;

        let entry_id: i64 = entry_row.get("id");

        // 5. Insert lines
        for line in lines {
            let dir_str = match line.direction {
                Direction::Debit => "Debit",
                Direction::Credit => "Credit",
            };
            sqlx::query(
                "INSERT INTO lines (entry_id, account_id, direction, amount_paise) VALUES ($1, $2, $3, $4)"
            )
            .bind(entry_id)
            .bind(line.account.0)
            .bind(dir_str)
            .bind(line.amount.to_minor())
            .execute(&mut *tx)
            .await
            .map_err(|e| LedgerError::Database(e.to_string()))?;
        }

        tx.commit().await.map_err(|e| LedgerError::Database(e.to_string()))?;

        Ok(EntryId(entry_id))
    }
}
