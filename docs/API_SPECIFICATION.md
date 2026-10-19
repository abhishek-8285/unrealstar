# CamStar — Complete API Specification (REST + WebSocket)

This document specifies the exact API catalog required to power **CamStar**. Unlike the competitor's 355 opaque integer-dispatched gateway shapes (`FuncTag`), CamStar uses structured, type-safe RESTful endpoints with JSON payloads and dedicated WebSocket protocols.

All authenticated REST endpoints require the header:
```http
Authorization: Bearer <access_token>
X-Device-Id: <device_hardware_uid>
```

---

## 1. Authentication & Identity (`/api/v1/auth`, `/api/v1/users`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `POST` | `/api/v1/auth/otp/send` | Request SMS OTP | Req: `phone_number`, `country_code`<br>Res: `otp_session_id`, `retry_after_secs` |
| `POST` | `/api/v1/auth/otp/verify` | Verify OTP & Login/Register | Req: `otp_session_id`, `otp_code`, `device_uid`, `device_model`, `play_integrity_token`<br>Res: `access_token`, `refresh_token`, `user_id`, `is_new_user` |
| `POST` | `/api/v1/auth/token/refresh` | Rotate access token | Req: `refresh_token`, `device_uid`<br>Res: `access_token`, `refresh_token` |
| `POST` | `/api/v1/auth/logout` | Revoke session | Req: `refresh_token`<br>Res: `success: true` |
| `GET` | `/api/v1/users/me` | Fetch authenticated user profile & eligibility | Res: `user_id`, `nickname`, `avatar_url`, `gender`, `can_publish`, `creator_status`, `wealth_level` |
| `PUT` | `/api/v1/users/me` | Update profile | Req: `nickname`, `bio`, `gender`, `city`, `avatar_url` |
| `GET` | `/api/v1/users/:user_id` | Fetch public creator profile | Res: `user_id`, `nickname`, `avatar_url`, `followers_count`, `following_count`, `is_live`, `live_room_id`, `badges` |

---

## 2. Wallet & Top-Up Pay-In (`/api/v1/wallet`, `/api/v1/payments`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `GET` | `/api/v1/wallet/balance` | Query current balances | Res: `coins` (purchased, non-withdrawable), `diamonds` (earned from gifts) |
| `GET` | `/api/v1/wallet/transactions` | Query ledger transaction history | Req query: `limit`, `cursor`<br>Res: `items: [{ transaction_id, type, amount, balance_after, created_at }]` |
| `GET` | `/api/v1/payments/packages` | List available coin purchase packages | Res: `packages: [{ package_id, coins, bonus_coins, price_inr, price_usd }]` |
| `POST` | `/api/v1/payments/orders/create` | Initiate UPI/Card payment order | Req: `package_id`, `provider` (`razorpay`/`cashfree`), `idempotency_key`<br>Res: `order_id`, `payment_url`, `upi_intent_uri` |
| `POST` | `/api/v1/payments/webhook/:provider` | Gateway asynchronous webhook | Req: Gateway callback payload + signature header<br>Res: `status: 200 OK` (triggers ledger deposit) |

---

## 3. Rooms & Live Streaming (`/api/v1/rooms`, `/ws/rooms/:id`)

### REST Endpoints
| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `POST` | `/api/v1/rooms/create` | Anchor opens a new live room | Req: `title`, `cover_image_url`, `category_id`, `is_private`<br>Res: `room_id`, `rtc_token`, `rtc_channel_id`, `ws_url` |
| `POST` | `/api/v1/rooms/:room_id/join` | Viewer joins a room | Res: `room_id`, `host_id`, `host_nickname`, `rtc_token`, `viewers_count`, `active_seats: []`, `ws_url`, `is_following` (viewer follows host), `is_pk` (duel running) |
| `POST` | `/api/v1/rooms/:room_id/leave` | Viewer/Guest leaves room | Req: `room_id`<br>Res: `success: true` |
| `POST` | `/api/v1/rooms/:room_id/close` | Anchor ends the stream | Req: `room_id`<br>Res: `summary: { duration_secs, peak_viewers, total_diamonds_earned }` |
| `POST` | `/api/v1/rooms/:room_id/seats/request` | Guest requests to take a seat | Req: `slot_number`<br>Res: `request_id`, `status: pending` |
| `POST` | `/api/v1/rooms/:room_id/seats/action` | Host approves/rejects/kicks seat | Req: `target_user_id`, `action` (`approve`/`reject`/`kick`/`mute_mic`) |
| `POST` | `/api/v1/rooms/:room_id/moderate` | Host/Mod mutes or bans user | Req: `target_user_id`, `action` (`mute_chat`/`kick_room`/`ban_room`), `duration_minutes` |

### WebSocket Protocol (`/ws/rooms/:room_id`)
Bi-directional real-time events over WebSocket:
* **Client → Server**:
  * `join`: Authenticate connection and sync room state.
  * `chat`: Send text message / danmu.
  * `seat_signal`: Update local camera/mic mute status (`app_common_self_ready`).
  * `ping`: Heartbeat keeping presence alive.
* **Server → Client**:
  * `room_state`: Snapshot of host, viewers count, active seats, and current PK state.
  * `chat_broadcast`: Real-time text comment.
  * `seat_updated`: Notification of seat join/leave/mute (`pos: 1..15`).
  * `gift_broadcast`: Real-time gift animation event (sender, recipient, SKU, multiplier combo).
  * `marquee_broadcast`: Global broadcast of luxury tier gift to all active rooms.
  * `pk_state_changed`: Notification of PK start, real-time score updates, and match end.

---

## 4. In-Room Gifting & Mini-Games (`/api/v1/gifts`, `/api/v1/games`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `GET` | `/api/v1/gifts/catalog` | Get list of all gifts & animation assets | Res: `gifts: [{ gift_id, name, price_coins, tier, icon_url, animation_url, is_broadcast }]` |
| `POST` | `/api/v1/gifts/send` | Send a gift to host/guest | Req: `room_id`, `recipient_id`, `gift_id`, `count`, `combo_seq`, `idempotency_key`<br>Res: `success: true`, `remaining_coins`, `earned_diamonds` |
| `POST` | `/api/v1/gifts/red-packet/drop` | Drop lucky money (Hongbao) into room | Req: `room_id`, `total_coins`, `split_count`, `idempotency_key`<br>Res: `packet_id`, `expires_at` |
| `POST` | `/api/v1/gifts/red-packet/:id/claim`| Viewer taps to claim lucky money | Req: `packet_id`<br>Res: `coins_claimed`, `split_index` |
| `POST` | `/api/v1/games/turntable/spin` | Play in-room monetised lucky wheel | Req: `room_id`, `wheel_id`, `coins_bet`<br>Res: `reward_tier`, `reward_coins`, `remaining_coins` |

---

## 5. PK Battles (`/api/v1/pk`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `POST` | `/api/v1/pk/match/request` | Enter random matchmaking queue | Req: `room_id`<br>Res: `match_ticket_id`, `status: searching` |
| `POST` | `/api/v1/pk/invite` | Directly challenge another live host | Req: `from_room_id`, `to_host_id`, `duration_secs`<br>Res: `invite_id` |
| `POST` | `/api/v1/pk/invite/:id/respond` | Accept or decline PK challenge | Req: `action` (`accept`/`decline`)<br>Res: `pk_id`, `opponent_rtc_channel` |
| `GET` | `/api/v1/pk/:pk_id/status` | Query active PK match state | Res: `time_remaining_secs`, `host_a_points`, `host_b_points`, `punish_time_secs` |

---

## 6. Social Graph, Moments & Discovery (`/api/v1/social`, `/api/v1/feed`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `POST` | `/api/v1/social/follow/:user_id` | Follow a creator | Res: `is_following: true` |
| `POST` | `/api/v1/social/unfollow/:user_id`| Unfollow a creator | Res: `is_following: false` |
| `GET` | `/api/v1/feed/rooms` | Explore live streams (Home tabs) | Req query: `tab` (`hot`/`nearby`/`pk`/`new_stars`), `page`<br>Res: `rooms: [{ room_id, title, cover_url, host, viewers_count, is_pk }]` |
| `GET` | `/api/v1/feed/moments` | Short video and photo feed | Req query: `cursor`<br>Res: `posts: [{ post_id, creator, video_url, cover_url, likes_count, comments_count }]` |
| `POST` | `/api/v1/feed/moments/create` | Publish a Moment clip/photo | Req: `media_url`, `caption`, `duration_secs`<br>Res: `post_id` |
| `POST` | `/api/v1/feed/moments/:id/like`| Like a Moment post | Res: `likes_count` |

---

## 7. Gamification, VIP & Tasks (`/api/v1/leaderboards`, `/api/v1/tasks`, `/api/v1/vip`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `GET` | `/api/v1/leaderboards` | Top spenders / top anchors | Req query: `type` (`patron`/`anchor`/`pk`), `period` (`daily`/`weekly`/`monthly`)<br>Res: `rankings: [{ rank, user_id, nickname, avatar_url, score }]` |
| `GET` | `/api/v1/tasks/daily` | Check-in streak and daily missions | Res: `tasks: [{ task_id, name, progress, target, reward_coins, is_claimed }]` |
| `POST` | `/api/v1/tasks/:id/claim` | Claim daily mission reward | Res: `reward_coins`, `updated_balance` |
| `GET` | `/api/v1/vip/tiers` | List VIP subscription plans | Res: `tiers: [{ tier_id, name, price_inr, privileges: [] }]` |
| `GET` | `/api/v1/users/me/bag` | Owned/equipped cosmetics for the caller | Res: `equipped_frame`, `equipped_ride`, `equipped_bubble`, `items: [{ item_id, name, category, icon_url, is_owned, is_equipped, price_coins }]` |
| `POST` | `/api/v1/users/me/bag/equip` | Equip an owned cosmetic, or buy then equip | Req: `item_id`, `equip`<br>Res: `is_equipped`, `coin_balance`; unowned items debit the ledger (400 when short) |

---

## 8. Creator Payouts & KYC Compliance (`/api/v1/kyc`, `/api/v1/payouts`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `GET` | `/api/v1/kyc/status` | Check user KYC verification status | Res: `status` (`none`/`pending`/`approved`/`rejected`), `rejection_reason` |
| `POST` | `/api/v1/kyc/submit` | Submit identity documents | Req: `payer_name`, `payer_address`, `pan_number`, `aadhaar_last4`, `bank_account_number`, `ifsc_code`, `doc_image_urls: []`<br>Res: `status: pending` |
| `GET` | `/api/v1/payouts/preview` | Preview withdrawal amount & TDS tax | Req query: `diamonds_amount`<br>Res: `gross_inr`, `tds_194q_deduction_inr` (1%), `net_payout_inr`, `exchange_rate` |
| `POST` | `/api/v1/payouts/request` | Submit cash-out request | Req: `diamonds_amount`, `bank_account_id`, `idempotency_key`<br>Res: `payout_id`, `status: processing` |
| `GET` | `/api/v1/payouts/history` | List past withdrawal requests | Res: `history: [{ payout_id, gross_inr, net_inr, status, created_at }]` |

---

## 9. Dynamic Config & Beauty Assets (`/api/v1/config`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `GET` | `/api/v1/config/app` | Client runtime configuration | Res: `features: { is_cashout_enabled, min_app_version }, rtc_servers: [], api_endpoints: {}` |
| `GET` | `/api/v1/config/beauty/assets` | Manifest of 2D makeup & AR assets | Res: `categories: [{ name: "lipstick", mattes: [{ id, url, default_intensity }] }]` |

---

## 10. Inbox & Direct Messages (`/api/v1/inbox`, `/api/v1/conversations`)

| Method | Endpoint | Description | Key Request / Response Fields |
|---|---|---|---|
| `GET` | `/api/v1/inbox/categories` | Five activity tiles with unread counts | Res: `categories: [{ key, label, unread }]` (`likes`/`mentions`/`followers`/`comments`/`gift`) |
| `GET` | `/api/v1/inbox` | Notification rows, optionally filtered by tile | Req query: `category`<br>Res: `messages: [{ message_id, category, sender_id, sender_nickname, sender_avatar_url, preview, created_at, unread }]` |
| `GET` | `/api/v1/inbox/official` | Official notices feed (reference notifications anatomy) | Req query: `section` (`official`/`event`/`picked`/`task`)<br>Res: `sections: [{ key, label, unread }]` (also the four sub-tabs), legacy `notices`, `groups: [{ key, label, rows }]` — buckets `week`/`month`/`earlier`; row: `{ row_id, kind: notice/activity, section, icon: app/info, body, actors: [{ user_id, nickname, avatar_url }], trailing: { kind: none/thumbnail/follow/confirm_delete, user_id, is_following, image_url }, created_at }` (in `body`, `[tokens]` are highlight chips, `{actor}`/`{actor2}` the row's people, `@mentions` render gold); `follow_requests: { title, subtitle, avatars: [url], pending: [{ user_id, nickname, avatar_url }] }`; `suggested: { label, users: [{ user_id, nickname, social_proof, avatar_url, is_following }], see_all_label, see_all_target }` |
| `POST` | `/api/v1/inbox/follow-requests/:peer_id/confirm` | Accept a pending follow request — its row becomes "started following you" | Auth required. Res: the refreshed official feed page (one round-trip) |
| `POST` | `/api/v1/inbox/follow-requests/:peer_id/delete` | Dismiss a pending follow request — its row leaves the feed | Auth required. Res: the refreshed official feed page (one round-trip) |
| `GET` | `/api/v1/inbox/conversations` | Message inbox: one row per conversation, newest first | Auth required. Res: `conversations: [{ peer_id, peer_nickname, peer_avatar_url, preview, last_message_at, unread_count }]` — `preview` is the last line, `"You: …"` when it is the reader's own |
| `GET` | `/api/v1/conversations/:user_id` | Open a 1:1 thread (seeded history + live lines) | Auth required. Res: `recipient_id`, `recipient_nickname`, `recipient_avatar_url`, `messages: [{ message_id, sender_id, recipient_id, text, created_at, is_self }]` |
| `POST` | `/api/v1/conversations/:user_id/send` | Send a direct message | Auth required. Req: `text`<br>Res: the stored message (`is_self: true`) |
| `POST` | `/api/v1/conversations/:user_id/read` | Opening a thread clears its unread badge | Auth required. Res: the refreshed `conversations` list |
