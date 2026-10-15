use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
};
use tokio::sync::{broadcast, RwLock};

use camstar_ledger::{AccountId, AccountKind, Ledger, Money};
use camstar_roles::{Authorizer, CreatorStatus, RoomRoster, Tier, VipLevel};
use chrono::Utc;
use uuid::Uuid;

use crate::models::{
    BeautyAssetItem, BeautyCategory, CoinPackage, DailyTaskItem, GiftItem, KycRecord,
    MomentComment, MomentItem, PayoutRecord, PkStatusResponse, UserProfile, VipTierItem, WsServerMessage,
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

#[derive(Debug, Clone)]
pub struct OtpSession {
    pub phone: String,
    pub code: String,
    pub created_at: chrono::DateTime<Utc>,
}

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
}

#[derive(Clone)]
pub struct AppState {
    pub ledger: Arc<RwLock<Ledger>>,
    pub authorizer: Arc<RwLock<Authorizer>>,
    pub users: Arc<RwLock<HashMap<u64, UserProfile>>>,
    pub phone_to_user: Arc<RwLock<HashMap<String, u64>>>,
    pub tokens_to_user: Arc<RwLock<HashMap<String, u64>>>,
    pub otp_sessions: Arc<RwLock<HashMap<String, OtpSession>>>,
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
    pub ws_rooms: Arc<RwLock<HashMap<u64, broadcast::Sender<WsServerMessage>>>>,
    pub beauty_manifest: Arc<Vec<BeautyCategory>>,
    pub vip_tiers: Arc<Vec<VipTierItem>>,
}

impl Default for AppState {
    fn default() -> Self {
        Self::new()
    }
}

impl AppState {
    pub fn new() -> Self {
        let mut ledger = Ledger::new();
        // Register clearing account as External so it can supply value (go negative)
        ledger.register_account(system_clearing_account(), AccountKind::External);

        let authorizer = Authorizer::new();

        // Standard gift catalog
        let gifts = vec![
            GiftItem {
                gift_id: "gift_rose".into(),
                name: "Rose".into(),
                price_coins: 1,
                tier: "standard".into(),
                icon_url: "https://cdn.camstar.live/gifts/rose.png".into(),
                animation_url: None,
                is_broadcast: false,
            },
            GiftItem {
                gift_id: "gift_chai".into(),
                name: "Chai".into(),
                price_coins: 10,
                tier: "standard".into(),
                icon_url: "https://cdn.camstar.live/gifts/chai.png".into(),
                animation_url: None,
                is_broadcast: false,
            },
            GiftItem {
                gift_id: "gift_sparkler".into(),
                name: "Diwali Sparkler".into(),
                price_coins: 99,
                tier: "animated".into(),
                icon_url: "https://cdn.camstar.live/gifts/sparkler.png".into(),
                animation_url: Some("https://cdn.camstar.live/gifts/sparkler.pag".into()),
                is_broadcast: false,
            },
            GiftItem {
                gift_id: "gift_fireworks".into(),
                name: "Diwali Fireworks".into(),
                price_coins: 99,
                tier: "animated".into(),
                icon_url: "https://cdn.camstar.live/gifts/sparkler.png".into(),
                animation_url: Some("https://cdn.camstar.live/gifts/sparkler.pag".into()),
                is_broadcast: false,
            },
            GiftItem {
                gift_id: "gift_supercar".into(),
                name: "Golden Supercar".into(),
                price_coins: 999,
                tier: "luxury".into(),
                icon_url: "https://cdn.camstar.live/gifts/supercar.png".into(),
                animation_url: Some("https://cdn.camstar.live/gifts/supercar.pag".into()),
                is_broadcast: true,
            },
            GiftItem {
                gift_id: "gift_castle".into(),
                name: "Taj Palace".into(),
                price_coins: 5000,
                tier: "luxury".into(),
                icon_url: "https://cdn.camstar.live/gifts/palace.png".into(),
                animation_url: Some("https://cdn.camstar.live/gifts/palace.pag".into()),
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
                        asset_url: format!("https://cdn.camstar.live/makeup/eyebrows/matte_{i}.png"),
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
                        asset_url: format!("https://cdn.camstar.live/makeup/eyelashes/matte_{i}.png"),
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
                        asset_url: format!("https://cdn.camstar.live/makeup/eyeshadow/matte_{i}.png"),
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
                        asset_url: format!("https://cdn.camstar.live/makeup/lipstick/matte_{i}.png"),
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

        AppState {
            ledger: Arc::new(RwLock::new(ledger)),
            authorizer: Arc::new(RwLock::new(authorizer)),
            users: Arc::new(RwLock::new(HashMap::new())),
            phone_to_user: Arc::new(RwLock::new(HashMap::new())),
            tokens_to_user: Arc::new(RwLock::new(HashMap::new())),
            otp_sessions: Arc::new(RwLock::new(HashMap::new())),
            rooms: Arc::new(RwLock::new(HashMap::new())),
            gifts: Arc::new(gifts),
            coin_packages: Arc::new(coin_packages),
            moments: Arc::new(RwLock::new(Vec::new())),
            daily_tasks: Arc::new(RwLock::new(HashMap::new())),
            kyc_records: Arc::new(RwLock::new(HashMap::new())),
            payouts: Arc::new(RwLock::new(Vec::new())),
            follows: Arc::new(RwLock::new(HashMap::new())),
            moment_likes: Arc::new(RwLock::new(HashMap::new())),
            moment_comments: Arc::new(RwLock::new(HashMap::new())),
            comment_likes: Arc::new(RwLock::new(HashMap::new())),
            pks: Arc::new(RwLock::new(HashMap::new())),
            ws_rooms: Arc::new(RwLock::new(HashMap::new())),
            beauty_manifest: Arc::new(beauty_manifest),
            vip_tiers: Arc::new(vip_tiers),
        }
    }

    /// Seed a demo user with optional coins for testing
    pub async fn create_user(&self, phone: &str, initial_coins: i64) -> u64 {
        let mut users = self.users.write().await;
        let mut phone_map = self.phone_to_user.write().await;
        let mut tokens_map = self.tokens_to_user.write().await;
        let mut ledger = self.ledger.write().await;

        let user_id = (users.len() as u64) + 1;
        let coins_acct = user_coins_account(user_id);
        let diamonds_acct = user_diamonds_account(user_id);

        ledger.register_account(coins_acct, AccountKind::Owned);
        ledger.register_account(diamonds_acct, AccountKind::Owned);

        if initial_coins > 0 {
            if let Ok(amount) = Money::from_minor(initial_coins) {
                let _ = ledger.post(
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
                );
            }
        }

        let profile = UserProfile {
            user_id,
            phone: phone.to_string(),
            nickname: format!("Star_{user_id}"),
            avatar_url: format!("https://cdn.camstar.live/avatars/{user_id}.png"),
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
        tokens_map.insert(token, user_id);

        user_id
    }

    pub async fn authenticate_header(&self, auth_header: Option<&str>) -> Option<u64> {
        let header = auth_header?;
        let token = header.strip_prefix("Bearer ")?;
        let tokens = self.tokens_to_user.read().await;
        if let Some(&uid) = tokens.get(token) {
            return Some(uid);
        }
        if let Some(rest) = token.strip_prefix("guest_token_") {
            if let Ok(uid) = rest.parse::<u64>() {
                return Some(uid);
            }
        }
        None
    }
}
