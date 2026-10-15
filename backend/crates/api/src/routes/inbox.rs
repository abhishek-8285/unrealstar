use axum::{
    extract::{Query, State},
    http::HeaderMap,
    Json,
};
use chrono::{Duration, Utc};

use crate::{
    models::{
        InboxCategory, InboxCategoryPage, InboxMessage, InboxMessagePage, OfficialNotice,
        OfficialNoticePage, OfficialSection,
    },
    state::AppState,
};

/// The five tiles across the top of the Inbox, in display order.
pub const CATEGORIES: [(&str, &str); 5] = [
    ("likes", "Likes"),
    ("mentions", "Mentions"),
    ("followers", "Followers"),
    ("comments", "Comments"),
    ("gift", "Gift"),
];

struct Seed {
    key: &'static str,
    nickname: &'static str,
    avatar: &'static str,
    preview: &'static str,
    minutes_ago: i64,
    unread: bool,
}

/// Seed rows mirror the product's real inbox: a mix of chat lines, Hindi and
/// emoji display names, and system-style "[Live] Shared a live room" entries.
fn seeds() -> Vec<Seed> {
    vec![
        Seed { key: "comments", nickname: "..", avatar: "a1", preview: "isme recharge karoo", minutes_ago: 60 * 24 * 27, unread: false },
        Seed { key: "mentions", nickname: "\u{1F64F}SATYA \u{3299}\u{FE0F} RAJPUT\u{1F64F}", avatar: "a2", preview: "good morning prisha", minutes_ago: 60 * 24 * 18, unread: false },
        Seed { key: "comments", nickname: "\u{0930}\u{0942} \u{092A}\u{094D}\u{0930}\u{0924}\u{093E}\u{092A} \u{0938}\u{093F}\u{0902}\u{0939}", avatar: "a3", preview: "kol kro na", minutes_ago: 60 * 24 * 18, unread: true },
        Seed { key: "likes", nickname: "Kundan rangdar \u{1F3A3}\u{274C}", avatar: "a4", preview: "hi cutie friends \u{1F618}", minutes_ago: 60 * 24 * 19, unread: false },
        Seed { key: "mentions", nickname: "desi jatt", avatar: "a5", preview: "[Live] Shared a live room", minutes_ago: 60 * 24 * 20, unread: false },
        Seed { key: "gift", nickname: "Mubarak Alam", avatar: "a6", preview: "[Live] Shared a live room", minutes_ago: 60 * 24 * 20, unread: false },
        Seed { key: "followers", nickname: "Gurjant Singh", avatar: "a7", preview: "[Live] Shared a live room", minutes_ago: 60 * 24 * 20, unread: false },
        Seed { key: "comments", nickname: "Ranjeet Kumar", avatar: "a8", preview: "nice stream bro", minutes_ago: 95, unread: true },
        Seed { key: "likes", nickname: "\u{0938}\u{094D}\u{0924}\u{093E}\u{0930}\u{0940} \u{0915}\u{0941}\u{091C}", avatar: "a9", preview: "liked your video", minutes_ago: 40, unread: true },
        Seed { key: "gift", nickname: "Anjali Verma", avatar: "a10", preview: "sent you a Rose", minutes_ago: 150, unread: true },
    ]
}

pub async fn inbox_categories(State(state): State<AppState>) -> Json<InboxCategoryPage> {
    let origin = crate::public_origin();
    let all = seeds();

    let categories = CATEGORIES
        .iter()
        .map(|(key, label)| {
            let unread = all.iter().filter(|s| s.key == *key && s.unread).count() as u64;
            Json(InboxCategory {
                key: (*key).to_string(),
                label: (*label).to_string(),
                unread,
            })
        })
        .map(|wrapped: Json<InboxCategory>| wrapped.0)
        .collect();

    let _ = (state, origin);
    Json(InboxCategoryPage { categories })
}

pub async fn inbox_messages(
    headers: HeaderMap,
    State(state): State<AppState>,
    Query(q): Query<InboxQuery>,
) -> Json<InboxMessagePage> {
    // Optional: a guest can still read the seeded inbox, matching the feed.
    let _ = headers;
    let _ = state;
    let origin = crate::public_origin();
    let now = Utc::now();

    let mut messages: Vec<InboxMessage> = seeds()
        .into_iter()
        .filter(|s| q.category.as_deref().map(|c| c == s.key).unwrap_or(true))
        .map(|s| InboxMessage {
            message_id: format!("inbox_{}_{}", s.key, s.minutes_ago),
            category: s.key.to_string(),
            sender_id: 0,
            sender_nickname: s.nickname.to_string(),
            sender_avatar_url: format!("{origin}/assets/avatars/{}.jpg", s.avatar),
            preview: s.preview.to_string(),
            created_at: now - Duration::minutes(s.minutes_ago),
            unread: s.unread,
        })
        .collect();

    // Newest first, the order the list renders in.
    messages.sort_by_key(|a| std::cmp::Reverse(a.created_at));

    Json(InboxMessagePage { messages })
}

#[derive(Debug, serde::Deserialize)]
pub struct InboxQuery {
    pub category: Option<String>,
}
/// Sub-tabs under Inbox -> Official.
pub const OFFICIAL_SECTIONS: [(&str, &str); 4] = [
    ("official", "Officially"),
    ("event", "Event"),
    ("picked", "Picked for you"),
    ("task", "Task"),
];

impl OfficialSeed {
    fn key_eq(&self, k: &str) -> bool {
        self.section == k
    }
}

struct OfficialSeed {
    section: &'static str,
    title: &'static str,
    body: &'static str,
    image: &'static str,
    minutes_ago: i64,
    unread: bool,
}

fn official_seeds() -> Vec<OfficialSeed> {
    vec![
        OfficialSeed {
            section: "official",
            title: "Status Alert!",
            body: "Spend [1] more coins to keep your [L1-Rookie] next month.",
            image: "",
            minutes_ago: 60 * 24 * 17,
            unread: true,
        },
        OfficialSeed {
            section: "official",
            title: "Level Secured!",
            body: "Your [L1-Rookie] status is locked for next month.",
            image: "vip-badge",
            minutes_ago: 60 * 24 * 13,
            unread: true,
        },
        OfficialSeed {
            section: "official",
            title: "Level Updated!",
            body: "Grades for this month is [L1-Rookie]. Check it out!",
            image: "vip-badge",
            minutes_ago: 60 * 24 * 12,
            unread: false,
        },
        OfficialSeed {
            section: "event",
            title: "Weekend Rush",
            body: "Double diamonds on all PK rooms this [Weekend].",
            image: "vip-badge",
            minutes_ago: 60 * 6,
            unread: true,
        },
        OfficialSeed {
            section: "event",
            title: "Creator Fest",
            body: "Join [2000+] hosts in the festival lobby.",
            image: "",
            minutes_ago: 60 * 30,
            unread: false,
        },
        OfficialSeed {
            section: "picked",
            title: "Picked For You",
            body: "We think you will like [Bollywood Nights] tonight.",
            image: "vip-badge",
            minutes_ago: 60 * 4,
            unread: true,
        },
        OfficialSeed {
            section: "task",
            title: "Daily Task",
            body: "Watch [10] minutes of live rooms to earn coins.",
            image: "",
            minutes_ago: 90,
            unread: true,
        },
        OfficialSeed {
            section: "task",
            title: "Task Complete",
            body: "You finished [3/3] daily tasks. Reward added.",
            image: "",
            minutes_ago: 200,
            unread: false,
        },
    ]
}

pub async fn inbox_official(
    headers: HeaderMap,
    State(state): State<AppState>,
    Query(q): Query<OfficialQuery>,
) -> Json<OfficialNoticePage> {
    let _ = (headers, state);
    let origin = crate::public_origin();
    let now = Utc::now();
    let seeds = official_seeds();

    let sections = OFFICIAL_SECTIONS
        .iter()
        .map(|(key, label)| OfficialSection {
            key: (*key).to_string(),
            label: (*label).to_string(),
            unread: seeds.iter().filter(|s| s.key_eq(key) && s.unread).count() as u64,
        })
        .collect();

    let notices = seeds
        .into_iter()
        .filter(|s| q.section.as_deref().map(|c| c == s.section).unwrap_or(true))
        .map(|s| OfficialNotice {
            notice_id: format!("official_{}_{}", s.section, s.minutes_ago),
            section: s.section.to_string(),
            title: s.title.to_string(),
            body: s.body.to_string(),
            image_url: if s.image.is_empty() {
                String::new()
            } else {
                format!("{origin}/assets/banners/2.jpg")
            },
            created_at: now - Duration::minutes(s.minutes_ago),
        })
        .collect();

    Json(OfficialNoticePage { sections, notices })
}

#[derive(Debug, serde::Deserialize)]
pub struct OfficialQuery {
    pub section: Option<String>,
}
