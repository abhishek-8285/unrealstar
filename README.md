# CamStar

Live streaming where the camera is the product. India-first.

## Layout

```
docs/FEATURE_PLAN.md        every feature, with the reason it exists and its phase
docs/API_SPECIFICATION.md   complete REST + WebSocket API contract
docs/PRODUCT_BLUEPRINT.md    product architecture & domain blueprint
backend/                    Rust workspace
  crates/ledger/            append-only double-entry wallet ledger
  crates/roles/             multi-role account model, scoped capabilities, derived status
  crates/api/               Axum REST and WebSocket API service implementing all 9 domains
```

## Current state

**Phase 0 — foundation.** The ledger is the first thing built because every money feature depends on its invariants, and getting money wrong is the most expensive thing to change later.

`camstar-ledger` implements:

- `Money` — a newtype over `rust_decimal::Decimal`, no `f64` anywhere. Every constructor and arithmetic result is checked to stay inside the `i64` minor-unit domain, because `rust_decimal` will happily absorb an addition past its range instead of erroring.
- `Ledger` — append-only, double-entry, idempotent. No `UPDATE balance SET …`; balance is the sum of lines, never a stored truth.

Enforced by tests, not convention:

| Invariant | Test |
|---|---|
| An entry must balance; an unbalanced one writes nothing | `unbalanced_entry_is_rejected_and_writes_nothing` |
| Owned accounts can never go negative | `an_owned_account_may_not_be_driven_negative` |
| External/clearing accounts may (that is where value enters) | `an_external_account_may_go_negative` |
| Unregistered accounts default to **strict** | `an_unregistered_account_defaults_to_owned_and_strict` |
| A retried request never double-charges | `retried_request_never_double_charges` |
| A failed post does not burn its idempotency key | `a_failed_post_does_not_consume_its_idempotency_key` |
| A client-supplied price cannot imbalance an entry | `a_client_supplied_price_cannot_imbalance_an_entry` |
| Sum of all account balances is always zero | `total_of_all_accounts_stays_zero_across_a_long_run` |

`camstar-roles` implements the role model — a user is not "a creator", roles are scoped and situational:

- **Three-way split.** *Eligibility* (derived from account state) answers "are you allowed at all"; *grants* (scoped, expiring) answer "are you allowed here"; *status* (derived, read-only) answers "who are you".
- **Publishing is eligibility, not a grant.** Any verified account can go live — no application, no review. `derive_creator_status` turns publish history into `Viewer → Streamer → Established → Featured`, so "becoming a creator" is an event, not a role assignment.
- `Authorizer` — grants are `(account, capability, scope)` with expiry. A moderation grant in room 42 grants nothing in room 43.
- Sensitive capabilities (`RequestPayout`, `ApprovePayout`, `ManageSubAccounts`, `ManageRoomSettings`) can never be held by a plain `User` — refused before any grant is consulted, so a forgotten registration fails closed.
- `RoomRoster` — host, co-host, guest, moderator are live room state with a TTL; audience is a count, not rows. Seats capped at 15.
- **`RoomRoster::can_send_gift` refuses a host gifting into their own room** — self-dealing is the laundering vector that turns a gift economy into a payout-laundering one.
- `derive_tier` / `derive_vip` are pure functions of ledger output, so status can never drift from the money and can never be written by a client. Creator status and wealth tier are independent axes.

## Run

```bash
cd backend
cargo test
cargo clippy --all-targets -- -D warnings
```

### API server (dev)

```bash
cd backend
cargo run -p camstar-api
```

Auth is OTP-based (`/api/v1/auth/otp/send` → `/api/v1/auth/otp/verify`). In debug builds the OTP is returned in the response as `debug_otp`; in release builds set `CAMSTAR_DEBUG_OTP=1` to get the same.

The Android debug build ships with `DEV_AUTH_TOKEN = "guest_token_1001"`. For local dev without OTP, enable the guest-token fallback:

```bash
CAMSTAR_ALLOW_GUEST_TOKEN=1 cargo run -p camstar-api
```

This accepts any `Bearer guest_token_<uid>` and authenticates as that uid. **Never enable it in production.**

Token TTLs (env-overridable): `ACCESS_TOKEN_TTL_SECS` (default 900), `REFRESH_TOKEN_TTL_SECS` (default 2 592 000).

## Next

Auth hardening is in (session-bound tokens, OTP TTL/cooldown/attempt limits, refresh rotation, guest fallback gated behind env). Next: money-path fixes (webhook-verified pay-in, gift red-packet, games EV, payouts KYC), then persistence (Postgres + `sqlx`), WS auth, CORS lockdown, CI. Then Phase 1: camera with live filters and 1:1 rooms.

Before Phase 3, see the legal note at the top of `docs/FEATURE_PLAN.md`.