use camstar_roles::{CreatorStatus, Tier, VipLevel};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Auth & Users
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct OtpSendRequest {
    pub phone_number: String,
    pub country_code: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct OtpSendResponse {
    pub otp_session_id: String,
    pub retry_after_secs: u32,
    pub debug_otp: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct OtpVerifyRequest {
    pub otp_session_id: String,
    pub otp_code: String,
    pub device_uid: String,
    pub device_model: Option<String>,
    pub play_integrity_token: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct AuthTokensResponse {
    pub access_token: String,
    pub refresh_token: String,
    pub user_id: u64,
    pub is_new_user: bool,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RefreshTokenRequest {
    pub refresh_token: String,
    pub device_uid: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LogoutRequest {
    pub refresh_token: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct UserProfile {
    pub user_id: u64,
    pub phone: String,
    pub nickname: String,
    pub avatar_url: String,
    pub bio: String,
    pub gender: String,
    pub city: String,
    pub age: u8,
    pub can_publish: bool,
    pub creator_status: CreatorStatus,
    pub wealth_tier: Tier,
    pub vip_level: VipLevel,
    pub followers_count: u64,
    pub following_count: u64,
    pub is_live: bool,
    pub live_room_id: Option<u64>,
    /// Total likes received across this user's posts. Profile stat, not a
    /// live counter, so it defaults for profiles created before it existed.
    #[serde(default)]
    pub likes_received: u64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct UpdateProfileRequest {
    pub nickname: Option<String>,
    pub avatar_url: Option<String>,
    pub bio: Option<String>,
    pub gender: Option<String>,
    pub city: Option<String>,
}

// ---------------------------------------------------------------------------
// Wallet & Payments
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct BalanceResponse {
    pub coins: i64,
    pub diamonds: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct TransactionItem {
    pub transaction_id: i64,
    pub kind: String,
    pub amount: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct CoinPackage {
    pub package_id: String,
    pub coins: i64,
    pub bonus_coins: i64,
    pub price_inr: u32,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateOrderRequest {
    pub package_id: String,
    pub provider: String,
    pub idempotency_key: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateOrderResponse {
    pub order_id: String,
    pub payment_url: String,
    pub upi_intent_uri: String,
    pub amount_inr: u32,
    /// Coins that will be credited when the provider webhook confirms
    /// payment. Not yet spendable at order-creation time.
    pub coins_pending: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct WebhookPayload {
    pub order_id: String,
    pub status: String,
    pub provider_reference: String,
}

// ---------------------------------------------------------------------------
// Rooms & Streaming
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateRoomRequest {
    pub title: String,
    pub cover_image_url: Option<String>,
    pub category_id: String,
    #[serde(default)]
    pub is_private: bool,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateRoomResponse {
    pub room_id: u64,
    pub title: String,
    pub rtc_token: String,
    pub rtc_channel_id: String,
    pub ws_url: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SeatInfo {
    pub slot: u8,
    pub occupant_id: u64,
    pub nickname: String,
    pub avatar_url: String,
    pub is_muted: bool,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct JoinRoomResponse {
    pub room_id: u64,
    pub title: String,
    pub host_id: u64,
    pub host_nickname: String,
    pub rtc_token: String,
    pub viewers_count: u64,
    pub active_seats: Vec<SeatInfo>,
    pub ws_url: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SeatRequest {
    pub slot: u8,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SeatActionRequest {
    pub target_user_id: u64,
    pub action: String, // "approve", "reject", "kick", "mute_mic"
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ModerateRequest {
    pub target_user_id: u64,
    pub action: String, // "mute_chat", "kick_room", "ban_room"
    pub duration_minutes: Option<u32>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CloseRoomResponse {
    pub duration_secs: u64,
    pub peak_viewers: u64,
    pub diamonds_earned: i64,
}

// ---------------------------------------------------------------------------
// Gifts & Mini-Games
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct GiftItem {
    pub gift_id: String,
    pub name: String,
    pub price_coins: i64,
    pub tier: String, // "standard", "animated", "luxury"
    pub icon_url: String,
    pub animation_url: Option<String>,
    pub is_broadcast: bool,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SendGiftRequest {
    pub room_id: u64,
    pub recipient_id: u64,
    pub gift_id: String,
    pub count: u32,
    pub combo_seq: u32,
    pub idempotency_key: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SendGiftResponse {
    pub success: bool,
    pub remaining_coins: i64,
    pub recipient_diamonds: i64,
    pub broadcast_triggered: bool,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RedPacketDropRequest {
    #[serde(default)]
    pub room_id: u64,
    pub total_coins: i64,
    pub split_count: u32,
    pub idempotency_key: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RedPacketDropResponse {
    pub packet_id: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RedPacketClaimResponse {
    pub coins_claimed: i64,
    pub remaining_splits: u32,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SpinWheelRequest {
    pub room_id: u64,
    pub wheel_id: String,
    pub coins_bet: i64,
    pub idempotency_key: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SpinWheelResponse {
    pub target_sector: u32,
    pub target_angle_deg: f32,
    pub multiplier: u32,
    pub reward_coins: i64,
    pub actor_diamonds: i64,
    pub remaining_coins: i64,
    pub tier: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ShovelDigRequest {
    pub room_id: u64,
    pub shovel_type: String, // "bronze" (10 coins), "silver" (50 coins), "gold" (200 coins)
    pub slot_index: u8,      // 0..8
    pub idempotency_key: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ShovelDigResponse {
    pub prize_name: String,
    pub coins_won: i64,
    pub diamonds_won: i64,
    pub is_jackpot: bool,
    pub remaining_coins: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct MagicLampWishRequest {
    pub room_id: u64,
    pub wishes_count: u32, // 1, 10, or 100
    pub idempotency_key: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MagicLampWishResponse {
    pub total_reward_coins: i64,
    pub multiplier_hit: u32,
    pub is_super_wish: bool,
    pub remaining_coins: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct FuncTagRequest {
    #[serde(rename = "FuncTag")]
    pub func_tag: u64,
    #[serde(rename = "userId", default)]
    pub user_id: Option<u64>,
    #[serde(rename = "token", default)]
    pub token: Option<String>,
    #[serde(rename = "rewardId", default)]
    pub reward_id: Option<u64>,
    #[serde(rename = "propId", default)]
    pub prop_id: Option<u64>,
    #[serde(rename = "carId", default)]
    pub car_id: Option<u64>,
    #[serde(rename = "months", default)]
    pub months: Option<u32>,
    #[serde(rename = "toUserId", default)]
    pub to_user_id: Option<u64>,
    #[serde(rename = "dateUnit", default)]
    pub date_unit: Option<u32>,
    #[serde(rename = "periodOfValidity", default)]
    pub period_of_validity: Option<u32>,
    #[serde(rename = "perPageCount", default)]
    pub per_page_count: Option<u32>,
    #[serde(rename = "curPage", default)]
    pub cur_page: Option<u32>,
    #[serde(rename = "msgType", default)]
    pub msg_type: Option<u32>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FuncTagResponse {
    #[serde(rename = "TagCode")]
    pub tag_code: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<serde_json::Value>,
}

// ---------------------------------------------------------------------------
// PK Battles
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct PkMatchRequest {
    pub room_id: u64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PkInviteRequest {
    pub from_room_id: u64,
    pub to_host_id: u64,
    pub duration_secs: u32,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PkRespondRequest {
    pub action: String, // "accept", "decline"
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PkStatusResponse {
    pub pk_id: String,
    pub host_a_id: u64,
    pub host_b_id: u64,
    pub host_a_points: u64,
    pub host_b_points: u64,
    pub time_remaining_secs: u32,
    pub status: String, // "matching", "in_battle", "punishing", "ended"
}

// ---------------------------------------------------------------------------
// Social & Feed
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FeedRoomItem {
    pub room_id: u64,
    pub title: String,
    pub cover_image_url: String,
    pub host_id: u64,
    pub host_nickname: String,
    pub viewers_count: u64,
    pub is_pk: bool,
    pub category: String,
    // Card chrome for the Live grid. `None` means the card renders without
    // that chip rather than with an empty one.
    pub rank_tag: Option<String>,
    pub host_badge: Option<String>,
    pub pk_opponent: Option<String>,
}

/// A promoted slide above the Live grid.
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LiveBanner {
    pub banner_id: String,
    pub title: String,
    pub subtitle: String,
    pub image_url: String,
    pub action_url: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LiveBannerPage {
    pub banners: Vec<LiveBanner>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MomentItem {
    pub moment_id: String,
    pub author_id: u64,
    pub author_nickname: String,
    pub media_url: String,
    pub caption: String,
    pub duration_secs: u32,
    pub likes_count: u64,
    pub created_at: DateTime<Utc>,
    // Engagement counters the feed action rail renders. Defaults keep payloads
    // written before these fields existed deserialisable.
    #[serde(default)]
    pub gift_count: u64,
    #[serde(default)]
    pub comment_count: u64,
    #[serde(default)]
    pub share_count: u64,
    #[serde(default)]
    pub avatar_url: String,
    /// Poster frame for the feed tile; empty means "no poster yet".
    #[serde(default)]
    pub cover_url: String,
    // Promotional overlay copy shown above the video. None means no banner.
    #[serde(default)]
    pub promo_text: Option<String>,
    /// Hashtags, stored without the leading '#'.
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateMomentRequest {
    pub media_url: String,
    pub caption: String,
    pub duration_secs: u32,
    #[serde(default)]
    pub promo_text: Option<String>,
    /// Poster frame shown before the video decodes. Without this the feed
    /// shows a blank rectangle on every swipe.
    #[serde(default)]
    pub cover_url: Option<String>,
    #[serde(default)]
    pub tags: Option<Vec<String>>,
}

/// One post in the feed response.
///
/// Deliberately separate from the stored [MomentItem]: `liked_by_me` and
/// `author_avatar_url` are relative to the caller and the request, so they
/// cannot live on the stored record.
#[derive(Debug, Serialize)]
pub struct FeedPost {
    pub moment_id: String,
    pub author_id: u64,
    pub author_nickname: String,
    pub author_avatar_url: String,
    pub media_url: String,
    pub cover_url: String,
    pub caption: String,
    pub duration_secs: u32,
    pub likes_count: u64,
    pub comment_count: u64,
    pub share_count: u64,
    pub gift_count: u64,
    pub promo_text: Option<String>,
    pub tags: Vec<String>,
    pub liked_by_me: bool,
    pub created_at: DateTime<Utc>,
}

/// Cursor-paginated feed page, as specified in docs/API_SPECIFICATION.md.
#[derive(Debug, Serialize)]
pub struct FeedPage {
    pub posts: Vec<FeedPost>,
    pub next_cursor: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MomentComment {
    pub comment_id: String,
    pub moment_id: String,
    /// `None` for a top-level comment; otherwise the comment being replied to.
    /// One level deep, matching how Instagram and YouTube render threads.
    pub parent_id: Option<String>,
    pub author_id: u64,
    pub author_nickname: String,
    pub body: String,
    #[serde(default)]
    pub likes_count: u64,
    #[serde(default)]
    pub liked_by_me: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateCommentRequest {
    pub body: String,
    /// Set to reply to an existing comment. Must belong to the same moment.
    #[serde(default)]
    pub parent_id: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct CommentList {
    pub comments: Vec<MomentComment>,
}

/// Tab-filtered, page-paginated room feed (docs/API_SPECIFICATION.md).
#[derive(Debug, Serialize)]
pub struct RoomPage {
    pub rooms: Vec<FeedRoomItem>,
    pub page: u32,
    pub total: u32,
    pub total_pages: u32,
}

#[derive(Debug, Deserialize, Default)]
pub struct FeedRoomsQuery {
    /// `hot` | `nearby` | `pk` | `new_stars`; defaults to `hot`.
    pub tab: Option<String>,
    pub page: Option<u32>,
    pub page_size: Option<u32>,
}

#[derive(Debug, Deserialize, Default)]
pub struct FeedMomentsQuery {
    /// Opaque cursor from a previous page's `next_cursor`.
    pub cursor: Option<String>,
    pub limit: Option<u32>,
    pub followed_only: Option<bool>,
}

// ---------------------------------------------------------------------------
// Gamification, VIP & Tasks
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LeaderboardItem {
    pub rank: u32,
    pub user_id: u64,
    pub nickname: String,
    pub avatar_url: String,
    pub score: i64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DailyTaskItem {
    pub task_id: String,
    pub title: String,
    pub progress: u32,
    pub target: u32,
    pub reward_coins: i64,
    pub is_claimed: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct VipTierItem {
    pub tier_id: String,
    pub name: String,
    pub price_inr: u32,
    pub privileges: Vec<String>,
}

// ---------------------------------------------------------------------------
// KYC & Creator Payouts
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct KycRecord {
    pub user_id: u64,
    pub payer_name: String,
    pub payer_address: String,
    pub pan_number: String,
    pub aadhaar_last4: String,
    pub bank_account_number: String,
    pub ifsc_code: String,
    pub status: String, // "pending", "approved", "rejected"
    pub rejection_reason: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct KycSubmitRequest {
    pub payer_name: String,
    pub payer_address: String,
    pub pan_number: String,
    pub aadhaar_last4: String,
    pub bank_account_number: String,
    pub ifsc_code: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PayoutPreviewResponse {
    pub diamonds_amount: i64,
    pub gross_inr: i64,
    pub tds_194q_inr: i64, // 1%
    pub net_payout_inr: i64,
    pub exchange_rate_diamonds_per_inr: u32,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PayoutRequest {
    pub diamonds_amount: i64,
    pub idempotency_key: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PayoutRecord {
    pub payout_id: String,
    pub user_id: u64,
    pub diamonds: i64,
    pub gross_inr: i64,
    pub net_inr: i64,
    pub status: String, // "processing", "settled", "rejected"
    pub created_at: DateTime<Utc>,
}

// ---------------------------------------------------------------------------
// App Config & Beauty Assets
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize)]
pub struct AppConfigResponse {
    pub min_version: String,
    pub is_cashout_enabled: bool,
    pub rtc_app_id: String,
    pub live_gateway: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct BeautyCategory {
    pub name: String,
    pub items: Vec<BeautyAssetItem>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct BeautyAssetItem {
    pub id: String,
    pub name: String,
    pub asset_url: String,
    pub default_intensity: f32,
}

// ---------------------------------------------------------------------------
// WebSocket Realtime Events
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(tag = "type", content = "payload")]
pub enum WsClientMessage {
    Join { user_id: u64, room_id: u64 },
    Chat { text: String },
    SeatSignal { is_muted: bool },
    /// WebRTC signalling: opaque SDP offer/answer or ICE candidate JSON,
    /// relayed verbatim to the room. `kind` is "offer" | "answer" | "ice".
    RtcSignal {
        target_user_id: u64,
        kind: String,
        sdp: String,
    },
    Ping,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(tag = "type", content = "payload")]
pub enum WsServerMessage {
    RoomState {
        viewers_count: u64,
        seats: Vec<SeatInfo>,
    },
    ChatBroadcast {
        user_id: u64,
        nickname: String,
        text: String,
    },
    SeatUpdated {
        slot: u8,
        occupant_id: u64,
        is_muted: bool,
    },
    GiftBroadcast {
        sender_id: u64,
        sender_nickname: String,
        gift_id: String,
        count: u32,
    },
    WheelSpinBroadcast {
        user_id: u64,
        nickname: String,
        sector: u32,
        multiplier: u32,
        prize: i64,
    },
    MiniGameJackpotBroadcast {
        game_name: String,
        winner_id: u64,
        winner_nickname: String,
        prize_description: String,
    },
    MarqueeBroadcast {
        message: String,
    },
    PkStateChanged {
        pk_id: String,
        points_a: u64,
        points_b: u64,
    },
    /// Delta-free viewer count update (join / leave / WS open / close / kick).
    ViewerCount {
        viewers_count: u64,
    },
    /// The room was closed by the host; clients should leave the socket.
    RoomClosed,
    /// The target user was kicked or banned at room level. `banned` also
    /// blocks rejoining; the client should close its own socket after acting.
    KickedFromRoom {
        user_id: u64,
        banned: bool,
    },
    /// WebRTC signalling relay: clients filter on `target_user_id` and feed
    /// `sdp` to their `PeerConnection`. The server never parses it.
    RtcSignalRelay {
        from_user_id: u64,
        target_user_id: u64,
        kind: String,
        sdp: String,
    },
    Pong,
}

// ---------------------------------------------------------------------------
// Inbox
// ---------------------------------------------------------------------------

/// A top-level inbox filter (Likes, Mentions, Followers, Comments, Gift).
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct InboxCategory {
    pub key: String,
    pub label: String,
    /// Unread count shown as a badge; 0 means no badge.
    pub unread: u64,
}

/// One row in the message list.
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct InboxMessage {
    pub message_id: String,
    pub category: String,
    pub sender_id: u64,
    pub sender_nickname: String,
    pub sender_avatar_url: String,
    /// Short preview line, e.g. "good morning prisha".
    pub preview: String,
    /// RFC3339; the client formats it.
    pub created_at: DateTime<Utc>,
    pub unread: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct InboxCategoryPage {
    pub categories: Vec<InboxCategory>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct InboxMessagePage {
    pub messages: Vec<InboxMessage>,
}

// ---------------------------------------------------------------------------
// Me dashboard
// ---------------------------------------------------------------------------

/// The four tiles under the DATA tab. Kept as explicit fields so the client
/// renders a stable 2x2 grid without inventing labels.
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MeStats {
    pub videos_watched: u64,
    pub lives_watched: u64,
    pub time_spent_minutes: u64,
    pub active_days: u64,
}

/// Everything the Me tab renders in one round-trip.
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MeDashboard {
    pub user_id: u64,
    pub nickname: String,
    pub avatar_url: String,
    pub bio: String,
    /// Short public handle shown next to the name, e.g. "Nakhri".
    pub display_id: String,
    pub age: u8,
    pub gender: String,
    pub level: u32,
    pub vip_level: String,
    pub following_count: u64,
    pub followers_count: u64,
    pub likes_count: u64,
    pub diamonds: f64,
    pub stats: MeStats,
}

/// A system notice shown on the Inbox "Official" tab.
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct OfficialNotice {
    pub notice_id: String,
    /// Sub-tab: official | event | picked | task.
    pub section: String,
    pub title: String,
    /// Body copy. Tokens in [brackets] are rendered as highlight chips.
    pub body: String,
    /// Optional illustration; empty renders a text-only card.
    pub image_url: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct OfficialSection {
    pub key: String,
    pub label: String,
    pub unread: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct OfficialNoticePage {
    pub sections: Vec<OfficialSection>,
    pub notices: Vec<OfficialNotice>,
}
