# CamStar — API capability model

Derived from RangLive's shipped client (`com.srang.tlive` v1.0.8), by binding every `const-string` in the DEX to its owning class and method, then extracting the request-builder field sets.

## Evidence quality — read this first

| | |
|---|---|
| Strings bound to `(class, method)` | 62,719 across 8 dex files |
| URL/path strings bound | **261 / 270** |
| Directly confirmed against decompiler output | **60 / 66 sampled — 100% of sampled, 0 contradictions** |
| Confirmed as constant-folding | 6 more |
| **Unverifiable** | **179** — the decompiler emits "no decompiled body" for the 177-URL constant-dump class |
| FuncTag catalogue | 355 request builders, 18 first-party classes; **only 5 have a labelled FuncTag** |

So: the *shape* of the API surface is solid and verified. The exact FuncTag integers are largely unrecovered — don't plan against them. Artifacts: `api_endpoints.{json,md}`, `api_functags.{json,md}`, `api_verify_log.txt`.

---

## Architecture: one gateway, integer dispatch

Every request funnels through a small gateway — `/api8/s0`, `/api8/s1`, `/api8/s3`, `/meShow/entrance` — with the request type selected by an integer `FuncTag` field in the JSON body. **355 request shapes across 18 builder classes.**

That is efficient at the network layer and expensive everywhere else: one integer silently selects a request shape, so a client and server can drift out of step with no type error. Verified example: cash-out carries `FuncTag 51020202` and sends `{FuncTag, exchangeAmount, token, userId, up}` — nothing names what it is.

**CamStar: do not copy this.** Either give each capability its own named route, or make `FuncTag` a generated enum shared between client and server so drift is a compile error.

**`userId` appears in 266 of 355 request builders** — essentially everything is user-scoped. Auth is the backbone, not an edge case.

---

## The eight domains, and what each needs

### 1. Identity — `ug.b`
`userId, phoneNum, verifyCode, deviceUId, unionid, deviceName, up, sv`
Phone OTP, device identity, social-unionid (Facebook/Google/Weibo/QQ), per-device push token.
→ **Phone OTP + device binding + social account linking.** We have this in Phase 0.

### 2. Feed & social graph — `ug.b`, `m7.j`
`newsId, commentId, rewardId, actorId, followedId, inviteId, medalId, pageIndex`
Moments-style feed, comments, follow graph, invitation links, medal/badges.
→ **Moments feed, comments, follow.** Phase 2.

### 3. Rooms — `m7.j`, `z7.m`, `xe.a`
`roomId, groupId, guestId, pos, x, y, state, app_common_self_ready, app_common_self_playing, teamId, pkInterval, MsgTag, artistId`
Seat positions, ready-state signalling, PK team/interval, turn-taking (`MsgTag`).
→ **The room state machine is the centrepiece.** Note `xe.a` proves seat state is an explicit protocol (`ready` / `playing` per participant), not implicit. Build ours that way — RangLive's implicit state is exactly why a deep link could reach a screen out of order.

### 4. Gifts & in-room economy — `qa.c`, `z7.m`
`giftId, price, liveId, taskIds, productId, resId`
Gift catalogue with server-side prices, task-linked gifts.
→ **Prices server-side only.** Client names a SKU; server prices it. This is the rule that would have prevented their client-writable-balance bug.

### 5. Wallet, payouts & KYC — `y7.l`, `qa.a`
`payerName, payerAddress, customerCert, productId, fromUserId, startTime, endTime, countPerPage`
**Payout requests carry identity documents over the wire** — payer name, address, and a certificate/customer record. Plus order history windows.
→ **This is the compliance surface, and it is the reason Phase 5 is gated.** Design the KYC document model before you design the payout flow.

### 6. Ranking, tasks & engagement — `c5.b`, `m7.j`
`toUserId, topUserId, rankType, taskId, feedbackId, gameId, seasonId, turntableId, kbNum`
Leaderboards, daily tasks, in-room games (turntable with a `price`), seasonal competitions.
→ **In-room games are monetised.** That is a design choice worth copying deliberately, with the price resolved server-side.

### 7. Notifications — multi-channel
FCM data → notification, plus OEM push (`com.thankyo.hwgame`), job scheduler, websocket `wss://ws.kktv9.com:81/?roomId=`.
→ **One push payload must never reach a route unvalidated.** In their client it does.

### 8. Payments — `skpay.ranglive.com`
`/api/v1|v2/{paytm, paytmupi, sslcommerz, payoneer, jazz, mpesa, easypaisa, aamarpay}/{init, callback, inquiry, notify}`
Nine providers, four lifecycle verbs each, **plus a live `skpaygray` staging twin shipped in the release build**.
→ **Ship one gateway integration, not nine.** Razorpay or Cashfree gives India-first UPI in one integration. And never ship the staging twin to users.

Also noted: `usd` pricing alongside the India stack — they price in USD on at least one surface. Decide our pricing currency policy before Phase 3, not after.

---

## What this changes in our plan

| From the API evidence | Change to CamStar |
|---|---|
| 355 shapes behind one integer-dispatched gateway | Named routes or a generated shared `FuncTag` enum — never a bare magic integer |
| `userId` in 266/355 builders | Auth-first: every API call is user-scoped by construction |
| `payerName/payerAddress/customerCert` over the wire | KYC document model designed in Phase 0, not bolted on at cash-out |
| `xe.a` ready/playing per participant | Explicit, server-owned room state machine |
| `giftId` + server `price` | Client names a SKU only; server resolves price (already in our ledger tests) |
| `app_common_self_*` field naming | Namespace our protocol fields from day one — theirs is unmaintainable |
| `skpaygray` shipped to release | Staging endpoints blocked at build time, not by discipline |

## What I still don't know

- **Only 5 of 355 FuncTags are recovered.** The rest are unlabelled request shapes — I know what fields they send, not what the server calls them.
- **179 bound strings are unverifiable** — the decompiler produced no body for the constant-dump class. `apktool` would resolve the obfuscated `R.string` keys and might recover more labels.
- No runtime capture, so **none of this is confirmed against live traffic**. The host list is what the client *would* call; remote config selects the actual roots at runtime and was never fetched.
- Nine URLs remain unbound to any method.

If you want the last of the FuncTags, the honest route is a runtime capture — a proxy on the device, watching real requests as you use the app. That closes all of the above at once.