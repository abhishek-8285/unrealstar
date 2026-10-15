# CamStar — Feature Plan

**Positioning:** live streaming where the camera is the product, not an accessory.
**Launch:** India-first.
**Scope:** feature parity with the RangLive class of app, plus a camera layer the category mostly lacks.

---

## ⚠️ Read this before Phase 3

India's 2025 amendment to the IT Rules prohibits online money gaming where there is chance and withdrawable value. RangLive's model — buy coins with UPI, send gifts, **cash the winnings out to a bank account** — sits squarely in that zone, and an aggregator/agency payout tier compounds it. There is also 1% TDS under s.194Q on online-gaming income above ₹10k/year, and DPDP Act 2023 obligations.

This does not kill the product, but it means the wallet/cash-out architecture has a legal counterpart that must be designed with counsel from Phase 0, not retrofitted at Phase 3. **Everything in Phase 1–2 (rooms, camera, gifts funded by a non-withdrawable balance) is structurally safe. The cash-out tier is the part that can force a redesign.**

---

## The one bet

RangLive and its competitors all ship the same streaming stack and none of them have a real camera layer. Streaming is commoditised; **the camera is the only defensible surface we own.** Every feature below is chosen to either (a) make the camera better, or (b) feed the loop that makes people open the camera.

---

## Who is the user — the role model

**A user is not "a creator" or "a viewer."** On Twitch nobody applies to be a creator — going live is something any verified account can do, and "creator" is a status you *become* by publishing. The same person hosts a room tonight, watches tomorrow, and holds a VIP tier while doing both. Modelling `user.role = CREATOR` breaks on contact with reality, and it conflates *capability* with *status* — which is how apps end up letting a VIP viewer moderate a room.

**Rule: eligibility is derived from account state, capability is scoped and granted, status is derived and read-only.** Never store "is a creator" or "balance" on the user row.

### The three-way split

| Question | Answered by | Lifetime | Example |
|---|---|---|---|
| **Are you allowed to do this at all?** | *Eligibility* — derived from account state | permanent | age confirmed + phone verified + not banned ⇒ **can publish** |
| **Are you allowed to do this *here*?** | *Grant* — scoped, deliberate, expiring | minutes–years | moderator in room 42, payout approval for account 7 |
| **Who are you?** | *Status* — derived, read-only | recomputed | creator status, wealth tier, VIP level |

### Four layers of role

| Layer | Values | Lifetime | Where it lives |
|---|---|---|---|
| **Identity** (global) | `User`, `Official`, `Agency`, `System` | permanent | `users` table + grants |
| **Eligibility** (derived) | can publish / can gift / can transact | recomputed | from `AccountState` |
| **Status** (derived) | creator status, tier, VIP, badges | recomputed | **from the ledger + publish history** |
| **Room** | host, co-host, guest, moderator, muted | minutes, TTL | live room state only |
| **Relationship** | followed, blocked, muted | days–years | per pair, not on the user |

### How someone becomes a creator

1. Sign up with a phone number. Account type is `User`. No application, no review.
2. `AccountState::can_publish()` is true the moment age is confirmed and the number is verified.
3. They hit go-live. A room opens. They are now a host.
4. `derive_creator_status` reads their publish history: `Viewer` → `Streamer` (first stream) → `Established` (10+ streams, 500+ peak) → `Featured` (50+ streams, 5k+ peak, real receipts).
5. **Nothing was written to the user row.** The status is computed on read.

A viewer with zero streams is `Viewer`. A viewer with one stream is a creator. The transition is an event, not a role assignment — so there is no "creator table" to keep in sync, and no way for a client to claim a status it has not earned.

### What each role can actually do

| Role | Can | Cannot | Scope |
|---|---|---|---|
| Viewer / audience | watch, send gifts, follow, comment, IM, **go live** | receive gifts, moderate | — |
| Host (anchor) | own a room, receive gifts, promote/demote, kick, end | gift into their own room | their room only |
| Co-host / guest | sit on a seat, receive gifts | moderate, change settings | the room |
| Moderator | mute/kick, pin, warn | payouts, room settings, promote | the room, granted by host, revocable |
| Official account | be featured, verified badge | nothing extra in other people's rooms | platform |
| Agency | manage sub-accounts, approve payouts for them | act as a creator in a room | its own sub-accounts only |
| System | everything | — | internal only |

### Rules encoded as tests, not conventions

- **Publishing is eligibility, not a grant** — `a_first_timer_and_a_veteran_have_identical_publishing_eligibility`. No application, no review queue, no upgrade path.
- **`can(capability, scope)`** — every grant is `(account, capability, scope)` with optional expiry. A moderator grant in room 42 grants nothing in room 43.
- **Sensitive capabilities can never be held by a plain `User`** — `RequestPayout`, `ApprovePayout`, `ManageSubAccounts`, `ManageRoomSettings`. Refused before any grant is consulted, so a forgotten registration fails closed.
- **A host cannot gift into their own room.** Self-dealing is the laundering vector that turns a gift economy into a payout-laundering one. Tested in `RoomRoster::can_send_gift`.
- **Status is a pure function of ledger output and publish history.** It cannot drift from the money, and it cannot be written by a client. A spender with no lifetime receipts is a `Newcomer` no matter how long their streak; a single gifted stream does not make anyone `Featured`.
- **Creator status and wealth tier are independent axes.** Generosity does not make you a creator; publishing does not make you rich.
- **Audience is not modelled as rows.** A 48,000-viewer room is a count plus ephemeral presence. Only seats, moderators and the host are durable — seats capped at 15.

### Why this matters commercially

The role model is where fraud lives. Gifts move value from viewers to hosts; payouts move it out to a bank. Every interesting abuse case in this category — host gifting to themselves, an agency approving its own creators, a client claiming a tier it never earned — is a role-model bug, not a payments bug. Building this correctly first is cheaper than auditing it later.

And the inverse matters just as much: an over-restrictive role model is how a category loses creators to a competitor. Nobody joins a live-streaming app that asks them to apply.

| Feature | Why | Phase |
|---|---|---|
| Phone OTP login | In India the phone number *is* the identity — no email habit to lean on. Cheapest verification that actually works on ₹8k devices. | 0 |
| Truecaller number lookup | Cuts OTP drop-off. Drop-off here compounds across every later step. | 0 |
| Device/app attestation (Play Integrity) | RangLive ships **zero** root/Frida detection — we are the ones who learned that lesson. Needed before payouts exist. | 0 |
| Profile + basic KYC | Payouts require identity verification; collect it once, early, not at cash-out time. | 0 |
| Session token lifecycle | The single most valuable token in the system. Short-lived access + rotating refresh, bound to device. | 0 |

## 2. Camera layer — *the differentiator*

| Feature | Why | Phase |
|---|---|---|
| In-app camera, always one tap from launch | The category's best apps bury the camera. Ours is the front door. | 1 |
| Live beauty + colour filters | The retention loop. Users who like their own face stay. | 1 |
| AR face effects | "CamStar" — effects are the shareable artefact. Drives organic installs better than any ad. | 2 |
| Real-time virtual background | Works on ₹15k devices with modern NPUs; strongly perceived quality. | 2 |
| 9:16 capture + trim/filter editor | Content flywheel — user-generated posts feed the feed, which feeds rooms. | 2 |
| Camera as RTC fallback | On 2G/3G and congested networks, video degrades to a still camera frame. The room stays alive, the session doesn't die. This is a real quality moat nobody else does. | 1 |
| Post-session highlight clip | One tap to publish a clip of your best moment. Turns spectators into creators. | 2 |
| Ring-light / camera-state guidance | Users join rooms in the dark. Cheap coaching, real retention. | 3 |

## 3. Core streaming

| Feature | Why | Phase |
|---|---|---|
| 1:1 rooms | The atomic unit. Proves the RTC stack before we complicate it. | 1 |
| Seat-based multi-guest rooms | Where PK comes from, and where the actual "live party" feel lives. | 2 |
| PK / match | Highest-arousal format, highest gift velocity. It's the engine of the economy. | 2 |
| Room roles (host/co-host/guest/audience) | Permissions make rooms safe to open to strangers. | 2 |
| Room state machine (explicit, server-owned) | RangLive's implicit state is why deep links reach screens out of order. Ours must never. | 1 |
| Moderator / mute / kick / ban | Rooms with strangers need them from day one. | 2 |
| Reconnect + resync | Dropped streams are the #1 support complaint in this category. | 1 |

## 4. Social graph

| Feature | Why | Phase |
|---|---|---|
| Follow / fans | Notification surface, and the reason to come back. | 1 |
| Profile v2 (camera-forward) | Profile is the storefront for a creator. | 1 |
| Moment feed (photo/video posts) | Consumes camera output; creates activity between rooms. | 2 |
| Block / report / mute | Non-negotiable for any UGC app. | 1 |
| Badges, rich level, wealth level | Status is the currency of live streaming. Also the hook for gifts. | 3 |
| Direct entry to a room from a profile | Removes the dead-ends that kill retention. | 2 |

## 5. Economy

| Feature | Why | Phase |
|---|---|---|
| **Append-only double-entry wallet** | The crown jewel. See §7. Built first because everything money-related depends on its invariants. | 0 |
| Coin balance (non-withdrawable) | Lets gifts work in Phase 1–2 while staying clear of the 2025 restriction. | 1 |
| Gift catalogue + animations + combo gifts | Gifts are the revenue primitive. Combos drive the multi-tap loops that make rooms profitable. | 1 |
| Gift effects pipeline (client render, server-authoritative charge) | Server decides cost; client only renders. Never the reverse. | 1 |
| UPI top-up (Razorpay/Cashfree) | One integration instead of four. UPI-first is regulatory and competitive, not optional. | 3 |
| Webhook reconciliation | "Pay success" callbacks lie. Reconciliation is the difference between a ledger you trust and one you hope is right. | 3 |
| VIP tiers / recharge bonuses | ARPU lever, and the standard ladder in this category. | 4 |
| Daily/weekly missions and sign-in streaks | Cheap retention; costs nothing to build, measurable immediately. | 3 |
| **Cash-out / payouts** | The legal boundary. Defer until counsel + TDS design exist. | 5 |
| Agency / aggregator payouts | Higher legal risk again. Last, and possibly never. | 6 |

## 6. Comms & engagement

| Feature | Why | Phase |
|---|---|---|
| Group + 1:1 IM | Post-room relationship. This is the retention engine. | 2 |
| In-room chat + danmu | Cheap dopamine, high message volume, makes rooms feel alive. | 1 |
| FCM push | Re-engagement. Must be rate-limited and never carry an unvalidated route. | 2 |
| Leaderboards (room / daily / weekly) | Competition is the cheapest retention mechanism we have. | 3 |
| Room recommendations | Without it, discovery dies and the catalogue starves. | 4 |

## 7. Platform & operations

| Feature | Why | Phase |
|---|---|---|
| Feature flags + remote config | Ship without an app release. Also our kill-switch. | 0 |
| Admin backoffice | Support, moderation, payout ops. Build with the ledger, not after. | 1 |
| Content moderation (text + image + video) | UGC + camera + strangers = mandatory, and it's an ongoing cost. | 2 |
| Analytics event pipeline | Cannot fix what you cannot measure. Phase 0. | 0 |
| Crash/error reporting | Phase 0. | 0 |
| H5/campaign surface | Share landing pages, promo pages. Keep it minimal and isolated. | 3 |
| Push + notification service | Separate, because it needs its own rate limits. | 2 |
| Abuse/rate limiting at the edge | In-app gifts and OTPs are fraud targets from launch. | 1 |

---

## Phase plan

| Phase | Weeks | Ships |
|---|---|---|
| **0 — Foundation** | 6–8 | Auth, KYC, flags, analytics, the wallet ledger, CI. No user-facing feature. |
| **1 — The slice** | 8–10 | Camera with live filters, 1:1 rooms, reconnect, non-withdrawable coins, gifts, chat. **This is the proof.** |
| **2 — The party** | 8–10 | Multi-guest rooms, PK, IM, feed, AR effects, moderation, admin moderation queue. |
| **3 — The money** | 6–8 | UPI top-up, reconciliation, missions, leaderboards. *(Gate: legal sign-off.)* |
| **4 — The business** | 6–8 | VIP tiers, recommendations, clips, brand features. |
| **5 — The boundary** | 4–6 | Cash-out with TDS/194Q design. *(Gate: counsel.)* |
| **6 — Optional** | — | Agency payouts. Possibly never. |

**Phase 1 is the whole bet.** If camera + 1:1 rooms + gifts isn't compelling, phases 2–6 are expensive answers to the wrong question.

---

## 8. Wallet ledger — designed before anything else

Everything money-related sits on this. Design, in order of importance:

1. **Append-only.** No `UPDATE balance SET …`. A `balance` column is a cache, never the truth.
2. **Double-entry.** Every movement is balanced pairs. The sum of all entries per account is the balance, and the invariant is checked in a test.
3. **`rust_decimal`, never `f64`.** A `Money` newtype in minor units. The type system rejects rupees where paise are expected.
4. **Idempotency key on every mutation.** Client sends an *intent*; a retry must never double-charge or double-credit. Network retries are the normal case, not the exception.
5. **Amount comes from the server.** The client names a SKU (`gift_rose_99`); the server prices it. Never accept an amount from a client — and never from a WebView.
6. **Balance is server-authoritative, always.** No client-side writer of any kind. The most serious defect we found in RangLive was exactly this.
7. **Replay-only balances.** If a forged value is written once and legitimate syncs only ever *raise* it, the fake value persists. Always overwrite from the server.
8. **Append-only audit log.** Every entry records actor, reason, request ID, and the balance *after*.

## 9. Do-not-repeat list

Learned at cost from the RangLive analysis — each of these is a defect we have evidence for:

| Anti-pattern | Why it burned us there |
|---|---|
| Exported activity + unvalidated URI → WebView | Chain runtime-confirmed; reached arbitrary JS in a privileged context |
| `@JavascriptInterface` with no origin check | Two bridges, injected unconditionally, one ungated → session token leak |
| `Class.forName()` from page JS, no allowlist | Unrestricted intent primitive with arbitrary extras |
| Client-writable wallet balance | Display/persistence break; syncs only raise, so fakes stick |
| Plaintext password + OAuth tokens in unencrypted MMKV + `allowBackup` | Session takeover from a backup or a lost phone |
| Server secret in the dex | OAuth AppSecret shipped — cannot be un-shipped, only rotated |
| Dev/gray endpoints and internal IPs in release | Free reconnaissance |
| Log interceptor dumping full URLs + all headers at `Log.i` | Token leak into logs, analytics, crash reports |
| No cert pinning + `cleartextTrafficPermitted` | MITM plus one user tap = full compromise |
| A security check that returns literal `true` | `isKKUrlOrInternalChannel()` — decorative, and worse than absent |
| Dev config shipped to users | `api-dev-*`, gray mirrors, `10.0.0.2` |

## 10. Explicitly out of scope for v1

Voice-only rooms · long video · web client · desktop app · NFT/tokenisation · agent payouts · anything requiring real-money withdrawal.