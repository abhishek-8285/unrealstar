use crate::money::{Money, MoneyError};
use std::collections::{BTreeMap, HashMap};
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct AccountId(pub i64);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    Debit,
    Credit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AccountKind {
    Owned,
    External,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EntryKind {
    TopUp,
    GiftSpend,
    Payout,
    Reversal,
    Adjustment,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EntryId(pub i64);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Line {
    pub account: AccountId,
    pub direction: Direction,
    pub amount: Money,
}

impl Line {
    pub fn signed(&self) -> Money {
        match self.direction {
            Direction::Credit => self.amount,
            Direction::Debit => Money::zero().checked_sub(self.amount).unwrap_or_default(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub id: EntryId,
    pub idempotency_key: String,
    pub kind: EntryKind,
    pub lines: Vec<Line>,
}

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum LedgerError {
    #[error("entry must contain at least two lines")]
    TooFewLines,
    #[error("entry is not balanced: debits and credits differ")]
    Unbalanced,
    #[error("entry contains a zero-amount line")]
    ZeroAmountLine,
    #[error("insufficient funds in account {account:?}")]
    InsufficientFunds {
        account: AccountId,
        available: Money,
        requested: Money,
    },
    #[error("idempotency key already used by entry {existing:?}")]
    IdempotencyConflict { existing: EntryId },
    #[error("arithmetic error: {0}")]
    Arithmetic(#[from] MoneyError),
    #[error("database error: {0}")]
    Database(String),
}

#[derive(Debug, Clone, Default)]
pub struct Ledger {
    entries: Vec<Entry>,
    next_entry_id: i64,
    idempotency: HashMap<String, EntryId>,
    balances: BTreeMap<AccountId, Money>,
    kinds: BTreeMap<AccountId, AccountKind>,
}

impl Ledger {
    pub fn new() -> Self {
        Ledger {
            entries: Vec::new(),
            next_entry_id: 1,
            idempotency: HashMap::new(),
            balances: BTreeMap::new(),
            kinds: BTreeMap::new(),
        }
    }

    pub fn register_account(&mut self, account: AccountId, kind: AccountKind) {
        self.kinds.insert(account, kind);
    }

    pub fn account_kind(&self, account: AccountId) -> AccountKind {
        self.kinds
            .get(&account)
            .copied()
            .unwrap_or(AccountKind::Owned)
    }

    pub fn balance(&self, account: AccountId) -> Money {
        self.balances.get(&account).copied().unwrap_or_default()
    }

    pub fn entries(&self) -> &[Entry] {
        &self.entries
    }

    pub fn entry(&self, id: EntryId) -> Option<&Entry> {
        self.entries.iter().find(|entry| entry.id == id)
    }

    pub fn find_by_idempotency_key(&self, key: &str) -> Option<EntryId> {
        self.idempotency.get(key).copied()
    }

    pub fn post(
        &mut self,
        kind: EntryKind,
        idempotency_key: &str,
        lines: Vec<Line>,
    ) -> Result<EntryId, LedgerError> {
        if let Some(existing) = self.idempotency.get(idempotency_key) {
            return Err(LedgerError::IdempotencyConflict {
                existing: *existing,
            });
        }
        let entry = self.build(kind, idempotency_key, lines)?;
        let id = entry.id;
        self.commit(entry);
        Ok(id)
    }

    pub fn post_idempotent(
        &mut self,
        kind: EntryKind,
        idempotency_key: &str,
        lines: Vec<Line>,
    ) -> Result<EntryId, LedgerError> {
        if let Some(existing) = self.idempotency.get(idempotency_key) {
            return Ok(*existing);
        }
        self.post(kind, idempotency_key, lines)
    }

    fn build(
        &mut self,
        kind: EntryKind,
        idempotency_key: &str,
        lines: Vec<Line>,
    ) -> Result<Entry, LedgerError> {
        if lines.len() < 2 {
            return Err(LedgerError::TooFewLines);
        }
        if lines.iter().any(|line| line.amount.to_minor() == 0) {
            return Err(LedgerError::ZeroAmountLine);
        }

        let mut net = Money::zero();
        for line in &lines {
            net = net.checked_add(line.signed())?;
        }
        if net.to_minor() != 0 {
            return Err(LedgerError::Unbalanced);
        }

        let mut touched: Vec<(AccountId, Money)> = Vec::new();
        for line in &lines {
            let signed = line.signed();
            let opening = self.balance(line.account);
            let closing = opening.checked_add(signed)?;
            if let Some(slot) = touched.iter_mut().find(|(id, _)| *id == line.account) {
                slot.1 = closing;
            } else {
                touched.push((line.account, closing));
            }
        }
        for (account, closing) in &touched {
            if self.account_kind(*account) == AccountKind::Owned && closing.is_negative() {
                return Err(LedgerError::InsufficientFunds {
                    account: *account,
                    available: self.balance(*account),
                    requested: Money::zero().checked_sub(*closing).unwrap_or_default(),
                });
            }
        }

        Ok(Entry {
            id: EntryId(self.next_entry_id),
            idempotency_key: idempotency_key.to_owned(),
            kind,
            lines,
        })
    }

    fn commit(&mut self, entry: Entry) {
        for line in &entry.lines {
            let signed = line.signed();
            let opening = self.balance(line.account);
            let closing = opening.checked_add(signed).expect("validated in build");
            self.balances.insert(line.account, closing);
        }
        self.idempotency
            .insert(entry.idempotency_key.clone(), entry.id);
        self.entries.push(entry);
        self.next_entry_id += 1;
    }

    pub fn total_of_all_accounts(&self) -> Money {
        let mut total = Money::zero();
        for balance in self.balances.values() {
            total = total
                .checked_add(*balance)
                .expect("cannot overflow in practice");
        }
        total
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const EXTERNAL: AccountId = AccountId(1);
    const USER_WALLET: AccountId = AccountId(100);
    const CREATOR_REVENUE: AccountId = AccountId(200);

    fn money(minor: i64) -> Money {
        Money::from_minor(minor).expect("valid amount")
    }

    fn seeded_ledger(minor: i64) -> Ledger {
        let mut ledger = Ledger::new();
        ledger.register_account(EXTERNAL, AccountKind::External);
        ledger
            .post(
                EntryKind::TopUp,
                "topup-seed",
                vec![
                    Line {
                        account: EXTERNAL,
                        direction: Direction::Debit,
                        amount: money(minor),
                    },
                    Line {
                        account: USER_WALLET,
                        direction: Direction::Credit,
                        amount: money(minor),
                    },
                ],
            )
            .expect("seed");
        ledger
    }

    fn gift_lines(amount: i64) -> Vec<Line> {
        vec![
            Line {
                account: USER_WALLET,
                direction: Direction::Debit,
                amount: money(amount),
            },
            Line {
                account: CREATOR_REVENUE,
                direction: Direction::Credit,
                amount: money(amount),
            },
        ]
    }

    #[test]
    fn top_up_moves_value_from_clearing_into_wallet() {
        let ledger = seeded_ledger(1000);
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 1000);
        assert_eq!(ledger.balance(EXTERNAL).to_minor(), -1000);
        assert_eq!(ledger.total_of_all_accounts().to_minor(), 0);
    }

    #[test]
    fn unbalanced_entry_is_rejected_and_writes_nothing() {
        let mut ledger = seeded_ledger(1000);
        let result = ledger.post(
            EntryKind::GiftSpend,
            "gift-1",
            vec![
                Line {
                    account: USER_WALLET,
                    direction: Direction::Debit,
                    amount: money(500),
                },
                Line {
                    account: CREATOR_REVENUE,
                    direction: Direction::Credit,
                    amount: money(499),
                },
            ],
        );
        assert_eq!(result, Err(LedgerError::Unbalanced));
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 1000);
        assert_eq!(ledger.balance(CREATOR_REVENUE).to_minor(), 0);
        assert_eq!(ledger.entries().len(), 1);
    }

    #[test]
    fn spending_more_than_the_balance_is_rejected() {
        let mut ledger = seeded_ledger(1000);
        let result = ledger.post(EntryKind::GiftSpend, "gift-1", gift_lines(1001));
        assert!(matches!(result, Err(LedgerError::InsufficientFunds { .. })));
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 1000);
        assert_eq!(ledger.entries().len(), 1);
    }

    #[test]
    fn spending_exactly_the_balance_is_allowed() {
        let mut ledger = seeded_ledger(1000);
        ledger
            .post(EntryKind::GiftSpend, "gift-1", gift_lines(1000))
            .expect("spend to zero is legal");
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 0);
        assert_eq!(ledger.balance(CREATOR_REVENUE).to_minor(), 1000);
    }

    #[test]
    fn retried_request_never_double_charges() {
        let mut ledger = seeded_ledger(1000);
        let first = ledger
            .post(EntryKind::GiftSpend, "gift-abc", gift_lines(300))
            .expect("first succeeds");
        let second = ledger
            .post_idempotent(EntryKind::GiftSpend, "gift-abc", gift_lines(300))
            .expect("retry is safe");

        assert_eq!(first, second);
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 700);
        assert_eq!(ledger.entries().len(), 2);
    }

    #[test]
    fn a_retry_of_a_spend_that_would_now_overdraw_still_returns_the_original() {
        let mut ledger = seeded_ledger(1000);
        ledger
            .post(EntryKind::GiftSpend, "gift-abc", gift_lines(800))
            .expect("first succeeds");

        let retry = ledger.post_idempotent(EntryKind::GiftSpend, "gift-abc", gift_lines(800));
        assert_eq!(retry, Ok(EntryId(2)));
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 200);
        assert_eq!(ledger.entries().len(), 2);
    }

    #[test]
    fn duplicate_key_on_strict_post_is_reported() {
        let mut ledger = seeded_ledger(1000);
        ledger
            .post(EntryKind::GiftSpend, "gift-abc", gift_lines(300))
            .expect("first succeeds");
        let repeat = ledger.post(EntryKind::GiftSpend, "gift-abc", gift_lines(300));
        assert_eq!(
            repeat,
            Err(LedgerError::IdempotencyConflict {
                existing: EntryId(2)
            })
        );
    }

    #[test]
    fn a_client_supplied_price_cannot_imbalance_an_entry() {
        let mut ledger = seeded_ledger(1000);
        let result = ledger.post(
            EntryKind::GiftSpend,
            "gift-tamper",
            vec![
                Line {
                    account: USER_WALLET,
                    direction: Direction::Debit,
                    amount: money(1),
                },
                Line {
                    account: CREATOR_REVENUE,
                    direction: Direction::Credit,
                    amount: money(9999),
                },
            ],
        );
        assert_eq!(result, Err(LedgerError::Unbalanced));
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 1000);
    }

    #[test]
    fn zero_amount_lines_are_rejected() {
        let mut ledger = seeded_ledger(1000);
        let result = ledger.post(
            EntryKind::GiftSpend,
            "gift-zero",
            vec![
                Line {
                    account: USER_WALLET,
                    direction: Direction::Debit,
                    amount: Money::zero(),
                },
                Line {
                    account: CREATOR_REVENUE,
                    direction: Direction::Credit,
                    amount: Money::zero(),
                },
            ],
        );
        assert_eq!(result, Err(LedgerError::ZeroAmountLine));
    }

    #[test]
    fn entries_need_at_least_two_lines() {
        let mut ledger = seeded_ledger(1000);
        let result = ledger.post(
            EntryKind::TopUp,
            "single-line",
            vec![Line {
                account: USER_WALLET,
                direction: Direction::Credit,
                amount: money(100),
            }],
        );
        assert_eq!(result, Err(LedgerError::TooFewLines));
    }

    #[test]
    fn an_unregistered_account_defaults_to_owned_and_strict() {
        let mut ledger = seeded_ledger(1000);
        assert_eq!(ledger.account_kind(AccountId(999)), AccountKind::Owned);
        let result = ledger.post(
            EntryKind::GiftSpend,
            "gift-1",
            vec![
                Line {
                    account: AccountId(999),
                    direction: Direction::Debit,
                    amount: money(1),
                },
                Line {
                    account: CREATOR_REVENUE,
                    direction: Direction::Credit,
                    amount: money(1),
                },
            ],
        );
        assert!(matches!(result, Err(LedgerError::InsufficientFunds { .. })));
    }

    #[test]
    fn an_external_account_may_go_negative() {
        let ledger = seeded_ledger(1000);
        assert_eq!(ledger.account_kind(EXTERNAL), AccountKind::External);
        assert_eq!(ledger.balance(EXTERNAL).to_minor(), -1000);
    }

    #[test]
    fn a_failed_post_does_not_consume_its_idempotency_key() {
        let mut ledger = seeded_ledger(1000);
        assert!(ledger
            .post(EntryKind::GiftSpend, "gift-key", gift_lines(9999))
            .is_err());
        assert_eq!(ledger.find_by_idempotency_key("gift-key"), None);
        ledger
            .post(EntryKind::GiftSpend, "gift-key", gift_lines(300))
            .expect("key is still free");
        assert_eq!(ledger.find_by_idempotency_key("gift-key"), Some(EntryId(2)));
    }

    #[test]
    fn the_ledger_is_append_only() {
        let mut ledger = seeded_ledger(1000);
        ledger
            .post(EntryKind::GiftSpend, "gift-1", gift_lines(300))
            .expect("gift");
        ledger
            .post(EntryKind::GiftSpend, "gift-2", gift_lines(200))
            .expect("gift");
        let ids: Vec<i64> = ledger.entries().iter().map(|entry| entry.id.0).collect();
        assert_eq!(ids, vec![1, 2, 3]);
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 500);
        assert_eq!(ledger.balance(CREATOR_REVENUE).to_minor(), 500);
    }

    #[test]
    fn total_of_all_accounts_stays_zero_across_a_long_run() {
        let mut ledger = Ledger::new();
        ledger.register_account(EXTERNAL, AccountKind::External);
        for round in 0..50i64 {
            ledger
                .post(
                    EntryKind::TopUp,
                    &format!("topup-{round}"),
                    vec![
                        Line {
                            account: EXTERNAL,
                            direction: Direction::Debit,
                            amount: money(1000),
                        },
                        Line {
                            account: USER_WALLET,
                            direction: Direction::Credit,
                            amount: money(1000),
                        },
                    ],
                )
                .expect("top up");
            ledger
                .post(
                    EntryKind::GiftSpend,
                    &format!("gift-{round}"),
                    gift_lines(400),
                )
                .expect("gift");
        }
        assert_eq!(ledger.balance(USER_WALLET).to_minor(), 50 * 600);
        assert_eq!(ledger.balance(CREATOR_REVENUE).to_minor(), 50 * 400);
        assert_eq!(ledger.total_of_all_accounts().to_minor(), 0);
        assert_eq!(ledger.entries().len(), 100);
    }
}
