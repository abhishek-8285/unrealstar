use axum::{
    extract::{Path, Query, State},
    http::HeaderMap,
    Json,
};
use chrono::{Duration, Utc};
use uuid::Uuid;

use crate::{
    models::{
        DirectMessage, DirectMessageThread, InboxCategory, InboxCategoryPage, InboxMessage,
        InboxMessagePage, OfficialNotice, OfficialNoticePage, OfficialSection,
        SendDirectMessageRequest,
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

fn conversation_key(u1: u64, u2: u64) -> (u64, u64) {
    if u1 < u2 {
        (u1, u2)
    } else {
        (u2, u1)
    }
}

pub async fn get_conversation(
    Path(target_user_id): Path<u64>,
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<DirectMessageThread>, crate::error::ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let current_user_id = state.authenticate_header(auth_header).await.unwrap_or(1001);

    let origin = crate::public_origin();
    let users = state.users.read().await;
    let (target_nickname, target_avatar) = if let Some(u) = users.get(&target_user_id) {
        (u.nickname.clone(), u.avatar_url.clone())
    } else {
        let name = match target_user_id {
            1002 => "Satya Rajput",
            1003 => "Anjali Verma",
            1004 => "Desi Jatt",
            _ => "Friend",
        };
        (name.to_string(), format!("{origin}/assets/avatars/a2.jpg"))
    };

    let key = conversation_key(current_user_id, target_user_id);
    let dm_map = state.direct_messages.read().await;

    let mut messages: Vec<DirectMessage> = if let Some(stored) = dm_map.get(&key) {
        stored
            .iter()
            .map(|m| DirectMessage {
                message_id: m.message_id.clone(),
                sender_id: m.sender_id,
                recipient_id: m.recipient_id,
                text: m.text.clone(),
                created_at: m.created_at,
                is_self: m.sender_id == current_user_id,
            })
            .collect()
    } else {
        let now = Utc::now();
        vec![
            DirectMessage {
                message_id: format!("init_1_{target_user_id}"),
                sender_id: target_user_id,
                recipient_id: current_user_id,
                text: "Hey there! Glad we connected on CamStar ✨".to_string(),
                created_at: now - Duration::minutes(15),
                is_self: false,
            },
            DirectMessage {
                message_id: format!("init_2_{target_user_id}"),
                sender_id: target_user_id,
                recipient_id: current_user_id,
                text: "Will you be joining the live stream party tonight? 🎤".to_string(),
                created_at: now - Duration::minutes(12),
                is_self: false,
            },
        ]
    };

    messages.sort_by_key(|m| m.created_at);

    Ok(Json(DirectMessageThread {
        recipient_id: target_user_id,
        recipient_nickname: target_nickname,
        recipient_avatar_url: target_avatar,
        messages,
    }))
}

pub async fn send_direct_message(
    Path(target_user_id): Path<u64>,
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<SendDirectMessageRequest>,
) -> Result<Json<DirectMessage>, crate::error::ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let current_user_id = state.authenticate_header(auth_header).await.unwrap_or(1001);

    let text = req.text.trim();
    if text.is_empty() {
        return Err(crate::error::ApiError::BadRequest(
            "Message text cannot be empty".into(),
        ));
    }

    let key = conversation_key(current_user_id, target_user_id);
    let now = Utc::now();
    let msg_id = format!("dm_{}_{}", current_user_id, Uuid::new_v4());

    let record = crate::state::DirectMessageRecord {
        message_id: msg_id.clone(),
        sender_id: current_user_id,
        recipient_id: target_user_id,
        text: text.to_string(),
        created_at: now,
    };

    let mut dm_map = state.direct_messages.write().await;
    let list = dm_map.entry(key).or_insert_with(Vec::new);
    list.push(record.clone());

    Ok(Json(DirectMessage {
        message_id: record.message_id,
        sender_id: record.sender_id,
        recipient_id: record.recipient_id,
        text: record.text,
        created_at: record.created_at,
        is_self: true,
    }))
}
