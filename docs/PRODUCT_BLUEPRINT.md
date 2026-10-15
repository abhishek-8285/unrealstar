# CamStar — Comprehensive Product Feature Blueprint

This blueprint outlines every feature, domain, and flow required to build and operate **CamStar**, synthesized from the ground truth extracted from reverse-engineering `com.srang.tlive` (RangLive) and engineered for an India-first production live-streaming platform.

---

## 1. User Identity, Onboarding & Roles (The Foundation)

### 1.1 Authentication & Onboarding
* **Phone OTP Authentication**: Primary login vector for India (low-end devices, no email dependency).
* **Truecaller One-Tap Verification**: Cuts SMS gateway latency and OTP drop-off during onboarding.
* **Social Account Linking**: OAuth linking for Google and Facebook with unified account identity (`unionid`).
* **Session & Device Security**:
  * Dual-token model: short-lived JWT access tokens and rotating refresh tokens strictly bound to device hardware IDs (`deviceUId`).
  * Google Play Integrity API attestation on app launch (blocking root, Frida, and debugger hooks).

### 1.2 User Profile
* **Public Profile**: Avatar, nickname, custom user ID, bio, age, gender, city/region.
* **Storefront Presence**: Displays user's published Moment clips, follower/following counts, honour wall, earned badges, and current live status.
* **Direct Room Entry**: One tap from any creator's profile into their active live stream.

### 1.3 Three-Axis Role Architecture
* **Global Identity**: `User` (general public), `Official` (platform/verified), `Agency` (management org), `System` (internal services).
* **Derived Eligibility**: Evaluated from account state without manual application:
  * `can_publish()`: Age confirmed + verified phone + good standing = immediate go-live permission.
  * `can_gift()`: Positive wallet balance + verified account.
  * `can_transact()`: KYC documents approved.
* **Derived Status (Read-Only)**: Pure function of publish history and ledger data. Never stored as a mutable client/user field:
  * *Creator Status*: `Viewer` → `Streamer` (1st stream) → `Established` (10+ streams) → `Featured`.
  * *Wealth Level*: Derived strictly from lifetime coins spent.
* **Room-Scoped Roles**:
  * `Host` (room owner, receives gifts, cannot gift into own room).
  * `Co-host / Guest` (occupies an audio/video seat).
  * `Moderator` (scoped grant per room; can mute/kick audience, cannot access payouts).
  * `Audience` (viewers).

---

## 2. Camera & Video Capture (The Front Door)

### 2.1 Front-Door Camera Access
* Launching the camera is always one tap from app launch.
* Zero-latency preview initialization before room connection.

### 2.2 Live Beauty & Filter Pipeline
* **Segmentation Matte Compositing**: Real-time 2D face segmentation overlay (5 categories: Blush, Eyebrows, Eyelashes, Eyeshadow, Lipstick) with 10 intensity/matte variations each.
* **Face Beautification**: Skin smoothing, brightening/whitening, face thinning, eye enlargement, jawline contouring.
* **Colour Grading**: Cinematic LUT filters for indoor/warm lighting conditions common in Indian homes.

### 2.3 AR & Creative Suite
* **Interactive 3D Face Masks**: Face-tracking rigs with reactive triggers (mouth open, blink, head nod).
* **Virtual Backgrounds**: Real-time background replacement/blur powered by device NPU without a green screen.
* **Ring-Light Coaching**: Ambient brightness detection alerting creators to adjust lighting.

### 2.4 Resilient Video Capture
* **Low-Bandwidth RTC Fallback**: On 2G/3G packet loss spikes, degrades stream to a high-quality still camera frame with live audio instead of dropping the connection.
* **9:16 Video Editor**: In-app recording, trimming, and music overlay for creating short video clips.
* **Highlight Generator**: Post-stream 15-second auto-cut of peak gift/reaction moments to publish to Moments.

---

## 3. Core Streaming & Room Mechanics (The Main Product)

### 3.1 Streaming Formats
* **Solo Broadcast (1:Many)**: Traditional anchor live stream capable of scaling to tens of thousands of concurrent viewers.
* **Multi-Guest / Voice Party Rooms**: Up to 15 explicit numbered seats (`pos 1..15`) for co-hosts/panelists.
* **1:1 Video Calls**: Private fan-to-anchor video sessions with per-minute coin billing.

### 3.2 Explicit Room State Machine
* **Server-Authoritative State**: Every seat change, ready status (`app_common_self_ready`), and active state (`app_common_self_playing`) is validated and broadcasted by the server via WebSocket/RTC data channels.
* **Seamless Reconnect**: Robust room session resumption on transient network drops without restarting the UI.

### 3.3 PK Battle (Anchor vs Anchor Battle)
* **Matchmaking & Invites**: Random match or direct invite between two live creators.
* **Split-Screen RTC**: Low-latency video stitching of both broadcasters.
* **Real-Time Gift Bar**: Dynamic progress bar showing gift points contributed by each anchor's fans.
* **Punishment Phase**: Post-match timer where the losing anchor performs an agreed task or spin-the-wheel punishment.
* **PK Seasons & Rankings**: Seasonal leaderboards rewarding top PK anchors.

### 3.4 In-Room Moderation & Controls
* Host and scoped moderators can mute user text chat, mute guest microphones, kick users from seats, or ban users from the room.
* Word blocklist filtering for chat.

---

## 4. In-Room Economy & Gifting Engine

### 4.1 Currency Dual-Model
* **Coins (Gold)**: Purchased by users with fiat currency. Non-withdrawable virtual commodity used exclusively for gifts, games, and interactions.
* **Diamonds / Gems (KBi)**: Creator earnings accumulated when receiving gifts. Convertible into fiat currency during payout.

### 4.2 Gifting Architecture
* **Catalog Hierarchy**:
  * *Standard Gifts*: Low-tier emoji/static icons (e.g., 1–10 coins).
  * *Animated Gifts*: Mid-tier animated effects (PAG / SVGA / Lottie) rendered over the video viewport.
  * *Luxury Broadcast Gifts*: High-tier animations (supercars, airplanes, fireworks) triggering an app-wide announcement banner across all active rooms.
* **Combo Gifting**: Multi-tap burst mode allowing users to send gifts in rapid succession (e.g., ×10, ×66, ×520, ×1314) with cumulative combo animations.
* **Strict Server Pricing**: The client requests a SKU identifier (`gift_id`); the backend calculates price, verifies balance, and records the transfer.

### 4.3 Lucky Red Packets (Hongbao / Lucky Money)
* Viewers or anchors can drop coin packets into the room chat.
* Countdown timer engages audience retention; viewers tap to claim a randomized split of coins.

### 4.4 In-Room Games & Mini-Apps
* **Monetised Turntable**: Lucky wheel inside the room with coin entry fees (`turntableId`, price in coins).
* **Social Mini-Games**: Casual multiplayer games (Ludo, Tambola/Bingo) embedded directly into the live room layout.

---

## 5. Wallet, Payments & Payouts (The Financial Core)

### 5.1 Append-Only Double-Entry Ledger
* **Mathematical Invariant**: Sum of all debits and credits across all accounts in a transaction equals exactly zero.
* **High Precision**: Exact integer minor-unit arithmetic (`rust_decimal`), eliminating floating-point rounding errors.
* **Idempotency**: Every transaction mutation requires a unique `idempotency_key` preventing double-charging on retries.
* **Balance Verification**: Balances are calculated by summing journal lines, never by overwriting a mutable table column.
* **Anti-Laundering Rule**: Broadcasters are strictly prohibited from gifting into their own rooms.

### 5.2 Pay-In / Top-Up (India-First)
* **Unified Payment Gateway**: Integration with Razorpay / Cashfree covering:
  * UPI (Google Pay, PhonePe, Paytm, BHIM, QR code intents).
  * NetBanking & Debit/Credit Cards.
* **App Store In-App Purchases**: Google Play Billing for compliant in-app purchases.
* **Webhook Reconciliation Engine**: Asynchronous payment verification reconciling gateway callbacks against order states.

### 5.3 Creator Payouts & Compliance
* **Gems-to-INR Conversion**: Transparent exchange rate from creator diamonds to rupee balances.
* **KYC Document Pipeline**: Mandatory identity verification (`payerName`, `payerAddress`, `customerCert`, PAN card, bank account verification) prior to initiating payouts.
* **Tax & Regulatory Compliance**:
  * Automatic 1% TDS deduction under Section 194Q for annual creator earnings exceeding ₹10,000.
  * Compliance with India's 2025 IT Rules for virtual asset flows.
* **Direct Bank Transfer**: Disbursal via automated IMPS / NEFT transfers.
* **Agency Settlement Tier**: Multi-creator agency management allowing rev-share splits between creators and agency owners.

---

## 6. Social Graph, Content & Discovery

### 6.1 Moments & UGC Feed
* Photo and short video feed for creators to publish updates between live broadcasts.
* Interactive social features: likes, threaded comments, and social sharing.

### 6.2 Social Connections
* Following / Fans graph with instant push notifications when followed anchors begin streaming.
* User blocking, reporting, and direct message privacy controls.

### 6.3 Discovery & Categorization
* **Home Feed Tabs**:
  * *Popular / Hot*: Algorithmic ranking based on concurrent viewers and gift velocity.
  * *Nearby*: Geolocation-based discovery of local streamers.
  * *Talent / Music*: Categorized streams by genre.
  * *PK Arena*: Live battles currently in progress.
  * *New Stars*: Spotlight tab for newcomer broadcasters.

---

## 7. Gamification, Status & Retention

### 7.1 Status Ladders
* **Rich / Wealth Level**: Progressive levels unlocked strictly by cumulative coins spent. Unlocks special username glow, VIP badges, and custom chat colors.
* **Streamer Level**: Levels unlocked by total broadcast hours and received gift value.

### 7.2 VIP Privilege System
* Monthly VIP tier subscription conferring exclusive privileges:
  * Special entrance vehicles/animations when joining rooms.
  * Custom animated avatar frames.
  * Immunity to basic moderator kick actions.

### 7.3 Engagement & Competitions
* **Leaderboards**: Daily, weekly, and monthly rankings for Top Spenders (patrons), Top Anchors, and PK Champions.
* **Missions & Streaks**: Daily check-in rewards, watch-time milestones, and gifting tasks awarding bonus points or free badges.
* **Honour & Glory Wall**: Permanent showcase of trophies won during platform events and tournaments.

---

## 8. Communications & Real-Time Messaging

### 8.1 In-Room Chat & Bullet Comments (Danmu)
* Real-time WebSocket chat room handling thousands of messages per second with client-side rate limiting and text rendering.
* Dedicated high-priority gift announcements taking visual precedence over plain text.

### 8.2 Direct Messaging (IM)
* 1:1 private text, voice note, and image messaging between mutual follows.
* Official system announcements channel for notices, event alerts, and transaction receipts.

### 8.3 Push Notification Engine
* Firebase Cloud Messaging (FCM) integration for live room start alerts and event updates.
* Strict payload verification preventing arbitrary route injection.

---

## 9. Operations, Safety & Backoffice

### 9.1 Trust & Safety
* Automated text filtering for profanity and phone number/contact info harvesting.
* Real-time automated visual moderation for nudity, violence, and policy violations.
* User reporting queue for human moderator escalation.

### 9.2 Admin Portal & Operations
* Real-time live room monitoring dashboard with live terminate/kill controls.
* KYC verification queue for identity document inspection and tax record validation.
* Payout review and manual release controls for flagged transactions.
* Feature flags and remote configuration enabling instant feature toggles without app store updates.
