-- Domain tables for Unreal Star (Phase 0 persistence step 2).
-- Ledger tables live in 0001. These tables mirror the in-memory AppState maps;
-- route cutover to Sqlx reads/writes is the follow-up. All tables use
-- IF NOT EXISTS so the migration is safe to re-run.

CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT PRIMARY KEY,
    phone VARCHAR(32) NOT NULL UNIQUE,
    nickname VARCHAR(128) NOT NULL,
    avatar_url TEXT NOT NULL DEFAULT '',
    bio TEXT NOT NULL DEFAULT '',
    gender VARCHAR(32) NOT NULL DEFAULT 'unspecified',
    city VARCHAR(128) NOT NULL DEFAULT '',
    age INTEGER NOT NULL DEFAULT 0,
    can_publish BOOLEAN NOT NULL DEFAULT TRUE,
    followers_count BIGINT NOT NULL DEFAULT 0,
    following_count BIGINT NOT NULL DEFAULT 0,
    likes_received BIGINT NOT NULL DEFAULT 0,
    is_live BOOLEAN NOT NULL DEFAULT FALSE,
    live_room_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS otp_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    phone VARCHAR(32) NOT NULL,
    code VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_otp_sessions_phone ON otp_sessions(phone);

CREATE TABLE IF NOT EXISTS auth_tokens (
    token VARCHAR(256) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    session_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_session ON auth_tokens(session_id);

CREATE TABLE IF NOT EXISTS pending_orders (
    order_id VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    coins BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS order_keys (
    idempotency_key VARCHAR(256) PRIMARY KEY,
    order_id VARCHAR(128) NOT NULL REFERENCES pending_orders(order_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS rooms (
    room_id BIGINT PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    host_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    category VARCHAR(64) NOT NULL,
    cover_image_url TEXT NOT NULL DEFAULT '',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_private BOOLEAN NOT NULL DEFAULT FALSE,
    peak_viewers BIGINT NOT NULL DEFAULT 0,
    diamonds_earned BIGINT NOT NULL DEFAULT 0,
    is_pk BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS room_seats (
    room_id BIGINT NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
    slot SMALLINT NOT NULL,
    occupant_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    is_muted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (room_id, slot)
);
CREATE TABLE IF NOT EXISTS room_bans (
    room_id BIGINT NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (room_id, user_id)
);

CREATE TABLE IF NOT EXISTS follows (
    follower_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    followed_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, followed_id)
);

CREATE TABLE IF NOT EXISTS moments (
    moment_id VARCHAR(64) PRIMARY KEY,
    author_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    media_url TEXT NOT NULL,
    cover_url TEXT NOT NULL DEFAULT '',
    caption TEXT NOT NULL DEFAULT '',
    duration_secs INTEGER NOT NULL DEFAULT 0,
    likes_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    share_count BIGINT NOT NULL DEFAULT 0,
    gift_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS moment_likes (
    moment_id VARCHAR(64) NOT NULL REFERENCES moments(moment_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    PRIMARY KEY (moment_id, user_id)
);
CREATE TABLE IF NOT EXISTS moment_comments (
    comment_id VARCHAR(64) PRIMARY KEY,
    moment_id VARCHAR(64) NOT NULL REFERENCES moments(moment_id) ON DELETE CASCADE,
    parent_id VARCHAR(64),
    author_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    likes_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS red_packets (
    packet_id VARCHAR(64) PRIMARY KEY,
    dropper_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    total_coins BIGINT NOT NULL,
    split_count INTEGER NOT NULL,
    claimed_count INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS red_packet_claims (
    packet_id VARCHAR(64) NOT NULL REFERENCES red_packets(packet_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    amount BIGINT NOT NULL,
    PRIMARY KEY (packet_id, user_id)
);

CREATE TABLE IF NOT EXISTS kyc_records (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    payer_name VARCHAR(256) NOT NULL,
    payer_address TEXT NOT NULL,
    pan_number VARCHAR(16) NOT NULL,
    aadhaar_last4 VARCHAR(4) NOT NULL,
    bank_account_number VARCHAR(32) NOT NULL,
    ifsc_code VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    rejection_reason TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS payouts (
    payout_id VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    diamonds BIGINT NOT NULL,
    gross_inr BIGINT NOT NULL,
    net_inr BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'processing',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_payouts_user ON payouts(user_id);

CREATE TABLE IF NOT EXISTS push_tokens (
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    fcm_token VARCHAR(4096) NOT NULL,
    device_uid VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, fcm_token)
);
