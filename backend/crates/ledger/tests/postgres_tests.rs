#[cfg(feature = "postgres")]
mod tests {
    use camstar_ledger::{AccountId, AccountKind, Direction, EntryKind, LedgerError, Line, Money, PostgresLedger};
    use sqlx::postgres::PgPoolOptions;
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    const DATABASE_URL: &str = "postgres://camstar:camstar@127.0.0.1:5433/camstar_ledger";
    static ID_GEN: AtomicI64 = AtomicI64::new(0);

    fn next_account_id() -> AccountId {
        let current = ID_GEN.load(Ordering::Relaxed);
        if current == 0 {
            let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos() as i64;
            // mask to positive i63
            let seed = nanos.abs() % 1_000_000_000_000;
            let _ = ID_GEN.compare_exchange(0, seed, Ordering::SeqCst, Ordering::SeqCst);
        }
        AccountId(ID_GEN.fetch_add(1, Ordering::SeqCst))
    }

    async fn setup_ledger() -> Option<PostgresLedger> {
        let pool = PgPoolOptions::new()
            .max_connections(5)
            .connect(DATABASE_URL)
            .await
            .ok()?;

        let ledger = PostgresLedger::new(pool);
        let _ = ledger.migrate().await;
        Some(ledger)
    }

    #[tokio::test]
    async fn test_postgres_account_registration_and_kinds() {
        let Some(ledger) = setup_ledger().await else {
            eprintln!("PostgreSQL not available at {}, skipping test", DATABASE_URL);
            return;
        };

        let user_acc = next_account_id();
        let ext_acc = next_account_id();

        ledger.register_account(user_acc, AccountKind::Owned).await.unwrap();
        ledger.register_account(ext_acc, AccountKind::External).await.unwrap();

        assert_eq!(ledger.account_kind(user_acc).await.unwrap(), AccountKind::Owned);
        assert_eq!(ledger.account_kind(ext_acc).await.unwrap(), AccountKind::External);
    }

    #[tokio::test]
    async fn test_postgres_top_up_and_balance_derivation() {
        let Some(ledger) = setup_ledger().await else {
            eprintln!("PostgreSQL not available at {}, skipping test", DATABASE_URL);
            return;
        };

        let user = next_account_id();
        let clearing = next_account_id();

        ledger.register_account(user, AccountKind::Owned).await.unwrap();
        ledger.register_account(clearing, AccountKind::External).await.unwrap();

        let top_up_amount = Money::from_minor(5000).unwrap(); // ₹50.00
        let idempotency = format!("topup_{}", uuid::Uuid::new_v4());

        let lines = vec![
            Line {
                account: clearing,
                direction: Direction::Debit,
                amount: top_up_amount,
            },
            Line {
                account: user,
                direction: Direction::Credit,
                amount: top_up_amount,
            },
        ];

        let entry_id = ledger.post(&idempotency, EntryKind::TopUp, &lines).await.unwrap();
        assert!(entry_id.0 > 0);

        let user_balance = ledger.balance_of(user).await.unwrap();
        assert_eq!(user_balance, top_up_amount);

        // Idempotent retry returns identical EntryId and does not double-credit
        let retry_entry_id = ledger.post(&idempotency, EntryKind::TopUp, &lines).await.unwrap();
        assert_eq!(entry_id, retry_entry_id);

        let user_balance_after_retry = ledger.balance_of(user).await.unwrap();
        assert_eq!(user_balance_after_retry, top_up_amount);
    }

    #[tokio::test]
    async fn test_postgres_overdraw_prevented_and_insufficient_funds() {
        let Some(ledger) = setup_ledger().await else {
            eprintln!("PostgreSQL not available at {}, skipping test", DATABASE_URL);
            return;
        };

        let user = next_account_id();
        let host = next_account_id();

        ledger.register_account(user, AccountKind::Owned).await.unwrap();
        ledger.register_account(host, AccountKind::Owned).await.unwrap();

        let spend_amount = Money::from_minor(1000).unwrap(); // ₹10.00
        let idempotency = format!("spend_{}", uuid::Uuid::new_v4());

        let lines = vec![
            Line {
                account: user,
                direction: Direction::Debit,
                amount: spend_amount,
            },
            Line {
                account: host,
                direction: Direction::Credit,
                amount: spend_amount,
            },
        ];

        // Should fail because user has 0 balance
        let result = ledger.post(&idempotency, EntryKind::GiftSpend, &lines).await;
        match result {
            Err(LedgerError::InsufficientFunds { account, available, requested }) => {
                assert_eq!(account, user);
                assert_eq!(available, Money::zero());
                assert_eq!(requested, spend_amount);
            }
            other => panic!("expected InsufficientFunds, got {:?}", other),
        }
    }

    #[tokio::test]
    async fn test_postgres_unbalanced_entry_rejected() {
        let Some(ledger) = setup_ledger().await else {
            eprintln!("PostgreSQL not available at {}, skipping test", DATABASE_URL);
            return;
        };

        let acc1 = next_account_id();
        let acc2 = next_account_id();

        let lines = vec![
            Line {
                account: acc1,
                direction: Direction::Debit,
                amount: Money::from_minor(100).unwrap(),
            },
            Line {
                account: acc2,
                direction: Direction::Credit,
                amount: Money::from_minor(200).unwrap(), // unbalanced!
            },
        ];

        let result = ledger.post("unbalanced_tx", EntryKind::Adjustment, &lines).await;
        assert_eq!(result, Err(LedgerError::Unbalanced));
    }
}
