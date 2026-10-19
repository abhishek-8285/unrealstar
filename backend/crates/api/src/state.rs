use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
};
use tokio::sync::{broadcast, RwLock};

use camstar_ledger::{
    AccountId, AccountKind, Entry, EntryId, EntryKind, Ledger, LedgerError, Line, Money,
};
use camstar_roles::{Authorizer, CreatorStatus, RoomRoster, Tier, VipLevel};
use chrono::{DateTime, Utc};
use uuid::Uuid;

use crate::models::{
    BeautyAssetItem, BeautyCategory, CoinPackage, DailyTaskItem, FamilyItem, GiftItem, KycRecord,
    MomentComment, MomentItem, PayoutRecord, PkStatusResponse, UserProfile, VipTierItem,
    WsServerMessage,
};

// Account IDs convention in Ledger:
// Clearing account: 0
// User Coins account: user_id * 2
// User Diamonds account: user_id * 2 + 1
pub fn user_coins_account(user_id: u64) -> AccountId {
    AccountId(user_id as i64 * 2)
}

pub fn user_diamonds_account(user_id: u64) -> AccountId {
    AccountId(user_id as i64 * 2 + 1)
}

pub fn system_clearing_account() -> AccountId {
    AccountId(0)
}

/// Dispatches ledger operations to either the in-memory `Ledger` or the
/// Postgres-backed store. Routes never touch the inner variants directly;
/// they call the async methods on this enum so a `DATABASE_URL` swap at
/// startup is invisible to every call site.
#[derive(Clone)]
pub enum LedgerBackend {
    Memory(Arc<RwLock<Ledger>>),
    Postgres(camstar_ledger::PostgresLedger),
}

impl std::fmt::Debug for LedgerBackend {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Memory(_) => f.debug_tuple("Memory").finish_non_exhaustive(),
            Self::Postgres(_) => f.debug_tuple("Postgres").finish_non_exhaustive(),
        }
    }
}

impl LedgerBackend {
    /// Create an in-memory ledger with the clearing account registered.
    pub fn memory() -> Self {
        let mut ledger = Ledger::new();
        // Clearing account is External so it can supply value (go negative).
        ledger.register_account(system_clearing_account(), AccountKind::External);
        Self::Memory(Arc::new(RwLock::new(ledger)))
    }

    /// Connect to Postgres, run migrations, and register the clearing account.
    pub async fn postgres(database_url: &str) -> Result<Self, LedgerError> {
        let ledger = camstar_ledger::PostgresLedger::connect_and_migrate(database_url).await?;
        ledger
            .register_account(system_clearing_account(), AccountKind::External)
            .await?;
        Ok(Self::Postgres(ledger))
    }

    /// Register an account. Idempotent on both backends.
    pub async fn register_account(
        &self,
        account: AccountId,
        kind: AccountKind,
    ) -> Result<(), LedgerError> {
        match self {
            Self::Memory(l) => {
                let mut ledger = l.write().await;
                ledger.register_account(account, kind);
                Ok(())
            }
            Self::Postgres(p) => p.register_account(account, kind).await,
        }
    }

    /// Post a double-entry. Idempotent on both backends: a duplicate
    /// `idempotency_key` returns the existing entry id rather than an error,
    /// so webhook retries and client retries succeed without a second debit.
    pub async fn post(
        &self,
        kind: EntryKind,
        idempotency_key: &str,
        lines: Vec<Line>,
    ) -> Result<EntryId, LedgerError> {
        match self {
            Self::Memory(l) => {
                let mut ledger = l.write().await;
                ledger.post_idempotent(kind, idempotency_key, lines)
            }
            Self::Postgres(p) => p.post(idempotency_key, kind, &lines).await,
        }
    }

    /// Current balance of one account.
    pub async fn balance(&self, account: AccountId) -> Result<Money, LedgerError> {
        match self {
            Self::Memory(l) => {
                let ledger = l.read().await;
                Ok(ledger.balance(account))
            }
            Self::Postgres(p) => p.balance_of(account).await,
        }
    }

    /// All entries. On Postgres a failed query yields an empty list (logged
    /// by the postgres layer) so a transient DB blip does not 500 reads.
    pub async fn entries(&self) -> Vec<Entry> {
        match self {
            Self::Memory(l) => {
                let ledger = l.read().await;
                ledger.entries().to_vec()
            }
            Self::Postgres(p) => p.entries().await.unwrap_or_default(),
        }
    }

    /// Look up an entry by its idempotency key.
    pub async fn find_by_idempotency_key(&self, key: &str) -> Option<EntryId> {
        match self {
            Self::Memory(l) => {
                let ledger = l.read().await;
                ledger.find_by_idempotency_key(key)
            }
            Self::Postgres(p) => p.find_by_idempotency_key(key).await.ok().flatten(),
        }
    }

    /// Sum of every account balance; zero for a healthy double-entry ledger.
    pub async fn total_of_all_accounts(&self) -> Result<Money, LedgerError> {
        match self {
            Self::Memory(l) => {
                let ledger = l.read().await;
                Ok(ledger.total_of_all_accounts())
            }
            Self::Postgres(p) => p.total_of_all_accounts().await,
        }
    }
}

#[derive(Debug, Clone)]
pub struct OtpSession {
    pub phone: String,
    pub code: String,
    pub created_at: chrono::DateTime<Utc>,
    /// Failed verify attempts against this session; dropped after
    /// `OTP_MAX_ATTEMPTS` so a code cannot be brute forced.
    pub attempts: u32,
}

/// Access tokens authenticate API requests; refresh tokens only mint new
/// pairs and are never accepted by `authenticate_header`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenKind {
    Access,
    Refresh,
}

#[derive(Debug, Clone)]
pub struct TokenRecord {
    pub user_id: u64,
    /// Every token minted under one login session shares this id so logout
    /// and refresh rotation can revoke the whole session at once.
    pub session_id: Uuid,
    pub kind: TokenKind,
    pub expires_at: chrono::DateTime<Utc>,
}

pub fn access_token_ttl_secs() -> u64 {
    std::env::var("ACCESS_TOKEN_TTL_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(900)
}

pub fn refresh_token_ttl_secs() -> u64 {
    std::env::var("REFRESH_TOKEN_TTL_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(30 * 24 * 60 * 60)
}

/// Unauthenticated `Bearer guest_token_N` tokens are a development
/// convenience only. They are REJECTED unless `CAMSTAR_ALLOW_GUEST_TOKEN`
/// is explicitly set to `1`/`true`/`yes` (local dev sets it; production
/// must leave it unset so forged guest tokens are rejected).
pub fn guest_tokens_enabled() -> bool {
    matches!(
        std::env::var("CAMSTAR_ALLOW_GUEST_TOKEN").as_deref(),
        Ok("1") | Ok("true") | Ok("TRUE") | Ok("yes") | Ok("YES")
    )
}

/// A coin top-up order that has been created but not yet confirmed by the
/// payment provider's webhook. Coins are credited only when the webhook fires.
#[derive(Debug, Clone)]
pub struct PendingOrder {
    pub user_id: u64,
    pub coins: i64,
    pub created_at: chrono::DateTime<Utc>,
}

/// Optional shared secret for the payment webhook. When set, the webhook
/// handler requires a matching `X-Webhook-Secret` header. When unset, the
/// webhook accepts any payload (dev convenience; production must set it).
pub fn webhook_secret() -> Option<String> {
    std::env::var("CAMSTAR_WEBHOOK_SECRET")
        .ok()
        .filter(|s| !s.is_empty())
}

/// An active red-packet drop. The dropper's coins were already debited into
/// the clearing account at drop time; claims only redistribute the escrowed
/// amount, never mint new coins.
#[derive(Debug, Clone)]
pub struct RedPacketDrop {
    pub packet_id: String,
    pub room_id: u64,
    pub dropper_user_id: u64,
    pub total_coins: i64,
    pub split_count: u32,
    /// Users who have already claimed this packet; a user may claim only once.
    pub claimants: HashSet<u64>,
    pub expires_at: chrono::DateTime<Utc>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DirectMessageRecord {
    pub message_id: String,
    pub sender_id: u64,
    pub recipient_id: u64,
    pub text: String,
    pub created_at: chrono::DateTime<Utc>,
}

/// The 1:1 conversation key: the two participant ids, ordered low-high.
pub type ConversationKey = (u64, u64);

/// In-memory direct-message store: conversation key -> lines, oldest first.
pub type DirectMessageStore = HashMap<ConversationKey, Vec<DirectMessageRecord>>;

/// Threads a reader has opened: `(reader_id, peer_id)`. Unread badges clear
/// against this set.
pub type ReadConversationSet = HashSet<ConversationKey>;

/// How a reader resolved a follow request. Confirmed requests turn into
/// "started following you" rows; deleted ones leave the feed entirely.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FollowRequestOutcome {
    Confirmed,
    Deleted,
}

/// reader_id -> requester_id -> outcome. Resolution lives server-side so a
/// refresh can never resurrect a request the user already handled.
pub type FollowRequestResolutions = HashMap<u64, HashMap<u64, FollowRequestOutcome>>;

#[derive(Debug, Clone)]
pub struct RoomSession {
    pub room_id: u64,
    pub title: String,
    pub host_id: u64,
    pub category: String,
    pub cover_image_url: String,
    pub roster: RoomRoster,
    pub is_active: bool,
    pub peak_viewers: u64,
    pub diamonds_earned: i64,
    /// Host is in a PK duel; mirrors `AppState::pks` so the Live grid can
    /// badge the card without a second lookup.
    pub is_pk: bool,
    pub pk_opponent: Option<String>,
    /// Promotional chip shown top-left on the room card, e.g. "Hourly TOP!".
    pub rank_tag: Option<String>,
    /// Decorative host marker shown beside the nickname, e.g. "👑".
    pub host_badge: Option<String>,
    /// Non-host users currently present in the room (HTTP join or WS open).
    pub present: HashSet<u64>,
    /// Viewer count attributed to seeds/demo data, unaffected by presence
    /// tracking. Reported count = viewers_base + present.len().
    pub viewers_base: u64,
    /// Wall-clock open time; close_room reports `now - started_at` instead
    /// of a constant so the ended screen shows the real stream length.
    pub started_at: chrono::DateTime<Utc>,
}

/// Hard cap on tracked viewer presence per room. Without it one viral room
/// grows presence (and its broadcast fan-out) without bound; HTTP join and the
/// WS upgrade path both enforce it. The host is never counted and always admits.
pub const MAX_ROOM_VIEWERS: u64 = 500;

impl RoomSession {
    /// Reported viewer count: seeded/demo viewers plus tracked presence.
    pub fn viewers_count(&self) -> u64 {
        self.viewers_base + self.present.len() as u64
    }

    /// Whether tracked presence is at the hard cap. Only REAL presence counts:
    /// `viewers_base` is display-only demo data that cannot open sockets, so
    /// it must not consume the join/fan-out budget.
    pub fn at_viewer_capacity(&self) -> bool {
        self.present.len() as u64 >= MAX_ROOM_VIEWERS
    }

    /// Insert a viewer into presence. The host is never tracked (they are not
    /// a viewer). Returns true when the set changed, so callers know whether
    /// to broadcast a ViewerCount update.
    pub fn mark_present(&mut self, user_id: u64) -> bool {
        if user_id == self.host_id {
            return false;
        }
        let changed = self.present.insert(user_id);
        if changed {
            self.sync_viewer_count();
        }
        changed
    }

    /// Remove a viewer from presence (leave / WS close / kick). Returns true
    /// when the set changed.
    pub fn mark_absent(&mut self, user_id: u64) -> bool {
        let changed = self.present.remove(&user_id);
        if changed {
            self.sync_viewer_count();
        }
        changed
    }

    /// Clear all presence (room closed) and sync the roster one final time.
    pub fn clear_present(&mut self) {
        self.present.clear();
        self.sync_viewer_count();
    }

    /// Push the computed count into the roster (used by social/PK routes) and
    /// keep peak_viewers monotonic.
    fn sync_viewer_count(&mut self) {
        let viewers = self.viewers_count();
        self.roster.set_viewer_count(viewers);
        if viewers > self.peak_viewers {
            self.peak_viewers = viewers;
        }
    }
}

#[derive(Clone)]
pub struct AppState {
    pub ledger: LedgerBackend,
    pub authorizer: Arc<RwLock<Authorizer>>,
    pub users: Arc<RwLock<HashMap<u64, UserProfile>>>,
    pub phone_to_user: Arc<RwLock<HashMap<String, u64>>>,
    pub tokens_to_user: Arc<RwLock<HashMap<String, TokenRecord>>>,
    pub otp_sessions: Arc<RwLock<HashMap<String, OtpSession>>>,
    /// Coin top-up orders awaiting provider webhook confirmation.
    pub pending_orders: Arc<RwLock<HashMap<String, PendingOrder>>>,
    /// Idempotency key -> order_id for pay-in creation retries.
    pub order_keys: Arc<RwLock<HashMap<String, String>>>,
    /// room_id -> banned user_ids. Banned users cannot (re)join.
    pub room_bans: Arc<RwLock<HashMap<u64, HashSet<u64>>>>,
    /// room_id -> chat-muted user_ids. Enforced by the WS chat relay, unlike
    /// the decorative Moderator seat role.
    pub chat_mutes: Arc<RwLock<HashMap<u64, HashSet<u64>>>>,
    /// Active red-packet drops, keyed by packet_id.
    pub red_packets: Arc<RwLock<HashMap<String, RedPacketDrop>>>,
    pub rooms: Arc<RwLock<HashMap<u64, RoomSession>>>,
    pub gifts: Arc<Vec<GiftItem>>,
    pub coin_packages: Arc<Vec<CoinPackage>>,
    pub moments: Arc<RwLock<Vec<MomentItem>>>,
    pub daily_tasks: Arc<RwLock<HashMap<u64, Vec<DailyTaskItem>>>>,
    pub kyc_records: Arc<RwLock<HashMap<u64, KycRecord>>>,
    pub payouts: Arc<RwLock<Vec<PayoutRecord>>>,
    pub follows: Arc<RwLock<HashMap<u64, HashSet<u64>>>>, // user_id -> set of followed user_ids
    /// moment_id -> set of user_ids who liked it. Lets us answer `liked_by_me`
    /// and refuse a duplicate like instead of blindly incrementing a counter.
    pub moment_likes: Arc<RwLock<HashMap<String, HashSet<u64>>>>,
    /// moment_id -> comments, oldest first, flat with `parent_id` links.
    pub moment_comments: Arc<RwLock<HashMap<String, Vec<MomentComment>>>>,
    /// comment_id -> set of user_ids who liked it.
    pub comment_likes: Arc<RwLock<HashMap<String, HashSet<u64>>>>,
    pub pks: Arc<RwLock<HashMap<String, PkStatusResponse>>>,
    /// user_id -> FCM device tokens (cap 10). Delivery via FCM HTTP v1 lands
    /// once a service-account key is provisioned; see routes::push.
    pub push_tokens: Arc<RwLock<HashMap<u64, HashSet<String>>>>,
    /// Client IP -> recent OTP send timestamps (sliding-window rate limit).
    pub otp_ip_log: Arc<RwLock<HashMap<std::net::IpAddr, Vec<DateTime<Utc>>>>>,
    pub ws_rooms: Arc<RwLock<HashMap<u64, broadcast::Sender<WsServerMessage>>>>,
    pub beauty_manifest: Arc<Vec<BeautyCategory>>,
    pub vip_tiers: Arc<Vec<VipTierItem>>,
    pub families: Arc<RwLock<Vec<FamilyItem>>>,
    pub user_family: Arc<RwLock<HashMap<u64, String>>>,
    pub direct_messages: Arc<RwLock<DirectMessageStore>>,
    /// (reader_id, peer_id) pairs whose thread has been opened. The message
    /// inbox clears its unread badge here, so the seeded list and "read"
    /// state live in one place instead of drifting.
    pub read_conversations: Arc<RwLock<ReadConversationSet>>,
    /// Follow requests the reader has confirmed or deleted; drives both the
    /// "Follow requests" summary row and the inline request rows.
    pub follow_request_resolutions: Arc<RwLock<FollowRequestResolutions>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self::new()
    }
}

impl AppState {
    pub fn new() -> Self {
        Self::with_ledger(LedgerBackend::memory())
    }

    /// Build AppState backed by a Postgres ledger. Called from `main` when
    /// `DATABASE_URL` is set; migrations run inside `LedgerBackend::postgres`.
    pub async fn with_postgres(database_url: &str) -> Result<Self, LedgerError> {
        let ledger = LedgerBackend::postgres(database_url).await?;
        Ok(Self::with_ledger(ledger))
    }

    /// Build AppState with a caller-selected ledger backend. All catalog
    /// setup is shared so `DATABASE_URL` selection at startup is the only
    /// difference between in-memory and Postgres deployments.
    fn with_ledger(ledger: LedgerBackend) -> Self {
        let authorizer = Authorizer::new();

        // Gift media resolves under PUBLIC_ORIGIN/assets like every other
        // seeded asset (banners, covers, cosmetics). The `.pag` animation
        // files are authored separately — clients treat a miss as "no
        // animation" until they land in MEDIA_ROOT.
        let origin = crate::public_origin();

        // Standard gift catalog
        let gifts = vec![
            GiftItem {
                gift_id: "gift_rose".into(),
                name: "Rose".into(),
                price_coins: 1,
                tier: "standard".into(),
                icon_url: format!("{origin}/assets/gifts/rose.png"),
                animation_url: None,
                is_broadcast: false,
            },
            GiftItem {
                gift_id: "gift_chai".into(),
                name: "Chai".into(),
                price_coins: 10,
                tier: "standard".into(),
                icon_url: format!("{origin}/assets/gifts/chai.png"),
                animation_url: None,
                is_broadcast: false,
            },
            GiftItem {
                gift_id: "gift_sparkler".into(),
                name: "Diwali Sparkler".into(),
                price_coins: 99,
                tier: "animated".into(),
                icon_url: format!("{origin}/assets/gifts/sparkler.png"),
                animation_url: Some(format!("{origin}/assets/gifts/sparkler.pag")),
                is_broadcast: false,
            },
            GiftItem {
                gift_id: "gift_fireworks".into(),
                name: "Diwali Fireworks".into(),
                price_coins: 99,
                tier: "animated".into(),
                icon_url: format!("{origin}/assets/gifts/sparkler.png"),
                animation_url: Some(format!("{origin}/assets/gifts/sparkler.pag")),
                is_broadcast: false,
            },
            GiftItem {
                gift_id: "gift_supercar".into(),
                name: "Golden Supercar".into(),
                price_coins: 999,
                tier: "luxury".into(),
                icon_url: format!("{origin}/assets/gifts/supercar.png"),
                animation_url: Some(format!("{origin}/assets/gifts/supercar.pag")),
                is_broadcast: true,
            },
            GiftItem {
                gift_id: "gift_castle".into(),
                name: "Taj Palace".into(),
                price_coins: 5000,
                tier: "luxury".into(),
                icon_url: format!("{origin}/assets/gifts/palace.png"),
                animation_url: Some(format!("{origin}/assets/gifts/palace.pag")),
                is_broadcast: true,
            },
        ];

        // Standard coin packages for India top-up
        let coin_packages = vec![
            CoinPackage {
                package_id: "pkg_70".into(),
                coins: 70,
                bonus_coins: 0,
                price_inr: 49,
            },
            CoinPackage {
                package_id: "pkg_150".into(),
                coins: 150,
                bonus_coins: 15,
                price_inr: 99,
            },
            CoinPackage {
                package_id: "pkg_500".into(),
                coins: 500,
                bonus_coins: 75,
                price_inr: 299,
            },
            CoinPackage {
                package_id: "pkg_2000".into(),
                coins: 2000,
                bonus_coins: 400,
                price_inr: 999,
            },
        ];

        // 104 beauty assets across 5 categories from the reverse engineering
        let beauty_manifest = vec![
            BeautyCategory {
                name: "Blush".into(),
                items: (1..=10)
                    .map(|i| BeautyAssetItem {
                        id: format!("blush_{i}"),
                        name: format!("Blush Matte #{i}"),
                        asset_url: format!("https://cdn.camstar.live/makeup/blush/matte_{i}.png"),
                        default_intensity: 0.6,
                    })
                    .collect(),
            },
            BeautyCategory {
                name: "Eyebrows".into(),
                items: (1..=10)
                    .map(|i| BeautyAssetItem {
                        id: format!("eyebrows_{i}"),
                        name: format!("Eyebrow Matte #{i}"),
                        asset_url: format!(
                            "https://cdn.camstar.live/makeup/eyebrows/matte_{i}.png"
                        ),
                        default_intensity: 0.5,
                    })
                    .collect(),
            },
            BeautyCategory {
                name: "Eyelashes".into(),
                items: (1..=10)
                    .map(|i| BeautyAssetItem {
                        id: format!("eyelashes_{i}"),
                        name: format!("Eyelash Matte #{i}"),
                        asset_url: format!(
                            "https://cdn.camstar.live/makeup/eyelashes/matte_{i}.png"
                        ),
                        default_intensity: 0.7,
                    })
                    .collect(),
            },
            BeautyCategory {
                name: "Eyeshadow".into(),
                items: (1..=10)
                    .map(|i| BeautyAssetItem {
                        id: format!("eyeshadow_{i}"),
                        name: format!("Eyeshadow Matte #{i}"),
                        asset_url: format!(
                            "https://cdn.camstar.live/makeup/eyeshadow/matte_{i}.png"
                        ),
                        default_intensity: 0.55,
                    })
                    .collect(),
            },
            BeautyCategory {
                name: "Lipstick".into(),
                items: (1..=10)
                    .map(|i| BeautyAssetItem {
                        id: format!("lipstick_{i}"),
                        name: format!("Lipstick Matte #{i}"),
                        asset_url: format!(
                            "https://cdn.camstar.live/makeup/lipstick/matte_{i}.png"
                        ),
                        default_intensity: 0.8,
                    })
                    .collect(),
            },
        ];

        let vip_tiers = vec![
            VipTierItem {
                tier_id: "vip_silver".into(),
                name: "Silver VIP".into(),
                price_inr: 499,
                privileges: vec![
                    "Silver avatar badge".into(),
                    "Exclusive chat bubble".into(),
                    "Room entry announcement".into(),
                ],
            },
            VipTierItem {
                tier_id: "vip_gold".into(),
                name: "Gold VIP".into(),
                price_inr: 1499,
                privileges: vec![
                    "Gold avatar frame".into(),
                    "Animated luxury sports car entry".into(),
                    "Immunity to moderator kick".into(),
                    "Custom Danmu bullet color".into(),
                ],
            },
        ];

        let initial_families = vec![
            FamilyItem {
                family_id: "fam_lions".into(),
                name: "Royal Lions".into(),
                badge: "🦁 LION".into(),
                level: 15,
                members_count: 88,
                leader_name: "Kabir_King".into(),
                exp: 250000,
                rank: 1,
            },
            FamilyItem {
                family_id: "fam_warriors".into(),
                name: "Desi Warriors".into(),
                badge: "⚔️ WARRIOR".into(),
                level: 12,
                members_count: 75,
                leader_name: "Rohit_Live".into(),
                exp: 180000,
                rank: 2,
            },
            FamilyItem {
                family_id: "fam_beats".into(),
                name: "Bollywood Beats".into(),
                badge: "🎵 BEATS".into(),
                level: 10,
                members_count: 60,
                leader_name: "Priya_Sharma".into(),
                exp: 120000,
                rank: 3,
            },
            FamilyItem {
                family_id: "fam_star".into(),
                name: "Star Clan".into(),
                badge: "⭐ STAR".into(),
                level: 8,
                members_count: 45,
                leader_name: "Ananya_Star".into(),
                exp: 95000,
                rank: 4,
            },
            FamilyItem {
                family_id: "fam_kings".into(),
                name: "Punjab Kings".into(),
                badge: "👑 KINGS".into(),
                level: 7,
                members_count: 38,
                leader_name: "Gurjant_Singh".into(),
                exp: 72000,
                rank: 5,
            },
        ];

        AppState {
            ledger,
            authorizer: Arc::new(RwLock::new(authorizer)),
            users: Arc::new(RwLock::new(HashMap::new())),
            phone_to_user: Arc::new(RwLock::new(HashMap::new())),
            tokens_to_user: Arc::new(RwLock::new(HashMap::new())),
            otp_sessions: Arc::new(RwLock::new(HashMap::new())),
            pending_orders: Arc::new(RwLock::new(HashMap::new())),
            order_keys: Arc::new(RwLock::new(HashMap::new())),
            room_bans: Arc::new(RwLock::new(HashMap::new())),
            chat_mutes: Arc::new(RwLock::new(HashMap::new())),
            red_packets: Arc::new(RwLock::new(HashMap::new())),
            rooms: Arc::new(RwLock::new(HashMap::new())),
            gifts: Arc::new(gifts),
            coin_packages: Arc::new(coin_packages),
            moments: Arc::new(RwLock::new(Vec::new())),
            daily_tasks: Arc::new(RwLock::new(HashMap::new())),
            kyc_records: Arc::new(RwLock::new(HashMap::new())),
            payouts: Arc::new(RwLock::new(Vec::new())),
            follows: Arc::new(RwLock::new(HashMap::from([(
                1001u64,
                // Demo user 1001 already follows one member of the inbox cast,
                // so the reference's "Following" pill state is visible on a
                // fresh account, exactly like the pasted notifications page.
                HashSet::from([1002u64]),
            )]))),
            moment_likes: Arc::new(RwLock::new(HashMap::new())),
            moment_comments: Arc::new(RwLock::new(HashMap::new())),
            comment_likes: Arc::new(RwLock::new(HashMap::new())),
            pks: Arc::new(RwLock::new(HashMap::new())),
            push_tokens: Arc::new(RwLock::new(HashMap::new())),
            otp_ip_log: Arc::new(RwLock::new(HashMap::new())),
            ws_rooms: Arc::new(RwLock::new(HashMap::new())),
            beauty_manifest: Arc::new(beauty_manifest),
            vip_tiers: Arc::new(vip_tiers),
            families: Arc::new(RwLock::new(initial_families)),
            user_family: Arc::new(RwLock::new(HashMap::new())),
            direct_messages: Arc::new(RwLock::new(HashMap::new())),
            read_conversations: Arc::new(RwLock::new(HashSet::new())),
            follow_request_resolutions: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Seed a demo user with optional coins for testing
    pub async fn create_user(&self, phone: &str, initial_coins: i64) -> u64 {
        let mut users = self.users.write().await;
        let mut phone_map = self.phone_to_user.write().await;
        let mut tokens_map = self.tokens_to_user.write().await;

        // Highest existing id, not count: seeded demo users (1001, 9999)
        // must not collide with the next registered account.
        let user_id = users.keys().max().copied().unwrap_or(0) + 1;
        let coins_acct = user_coins_account(user_id);
        let diamonds_acct = user_diamonds_account(user_id);

        let _ = self
            .ledger
            .register_account(coins_acct, AccountKind::Owned)
            .await;
        let _ = self
            .ledger
            .register_account(diamonds_acct, AccountKind::Owned)
            .await;

        if initial_coins > 0 {
            if let Ok(amount) = Money::from_minor(initial_coins) {
                let _ = self
                    .ledger
                    .post(
                        camstar_ledger::EntryKind::TopUp,
                        &format!("seed_{user_id}"),
                        vec![
                            camstar_ledger::Line {
                                account: system_clearing_account(),
                                direction: camstar_ledger::Direction::Debit,
                                amount,
                            },
                            camstar_ledger::Line {
                                account: coins_acct,
                                direction: camstar_ledger::Direction::Credit,
                                amount,
                            },
                        ],
                    )
                    .await;
            }
        }

        let profile = UserProfile {
            user_id,
            phone: phone.to_string(),
            nickname: format!("Star_{user_id}"),
            avatar_url: format!(
                "{}/assets/avatars/a{}.jpg",
                crate::public_origin(),
                (user_id % 10) + 1
            ),
            bio: "Welcome to my CamStar stream!".into(),
            gender: "unspecified".into(),
            city: "Mumbai".into(),
            age: 22,
            can_publish: true,
            creator_status: CreatorStatus::Viewer,
            wealth_tier: Tier::Newcomer,
            vip_level: VipLevel::None,
            followers_count: 0,
            following_count: 0,
            is_live: false,
            live_room_id: None,
            likes_received: 0,
        };

        users.insert(user_id, profile);
        phone_map.insert(phone.to_string(), user_id);

        let token = format!("token_{user_id}_{}", Uuid::new_v4());
        tokens_map.insert(
            token,
            TokenRecord {
                user_id,
                session_id: Uuid::new_v4(),
                kind: TokenKind::Access,
                expires_at: Utc::now() + chrono::Duration::seconds(access_token_ttl_secs() as i64),
            },
        );

        user_id
    }

    /// Mint an access token for `user_id`. Used by integration tests that
    /// need a bearer token without going through the OTP flow.
    pub async fn grant_test_token(&self, token: &str, user_id: u64) {
        let mut tokens = self.tokens_to_user.write().await;
        tokens.insert(
            token.to_string(),
            TokenRecord {
                user_id,
                session_id: Uuid::new_v4(),
                kind: TokenKind::Access,
                expires_at: Utc::now() + chrono::Duration::seconds(access_token_ttl_secs() as i64),
            },
        );
    }

    pub async fn authenticate_header(&self, auth_header: Option<&str>) -> Option<u64> {
        let header = auth_header?;
        let token = header.strip_prefix("Bearer ")?;
        {
            let tokens = self.tokens_to_user.read().await;
            if let Some(rec) = tokens.get(token) {
                // Only unexpired access tokens authenticate. Refresh tokens
                // presented as bearer tokens are rejected outright.
                if rec.kind == TokenKind::Access && rec.expires_at > Utc::now() {
                    return Some(rec.user_id);
                }
                return None;
            }
        }
        if guest_tokens_enabled() {
            if let Some(rest) = token.strip_prefix("guest_token_") {
                if let Ok(uid) = rest.parse::<u64>() {
                    return Some(uid);
                }
            }
        }
        None
    }
}
