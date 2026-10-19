use axum::{
    extract::{Path, Query, State},
    http::HeaderMap,
    Json,
};
use chrono::{Duration, Utc};
use std::collections::{HashMap, HashSet};
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{
        ConversationPage, ConversationSummary, DirectMessage, DirectMessageThread, InboxCategory,
        InboxCategoryPage, InboxMessage, InboxMessagePage, OfficialActor, OfficialFollowRequests,
        OfficialGroup, OfficialNotice, OfficialNoticePage, OfficialRow, OfficialSection,
        OfficialSuggested, OfficialSuggestion, OfficialTrailing, SendDirectMessageRequest,
    },
    state::{AppState, FollowRequestOutcome},
};

/// The five tiles across the top of the Inbox, in display order.
pub const CATEGORIES: [(&str, &str); 5] = [
    ("likes", "Likes"),
    ("mentions", "Mentions"),
    ("followers", "Followers"),
    ("comments", "Comments"),
    ("gift", "Gift"),
];

/// One member of the inbox cast.
struct Peer {
    id: u64,
    nickname: &'static str,
    avatar: &'static str,
}

/// The inbox cast. Notification rows, the conversation list and DM threads
/// all resolve names, avatars and ids through this one table, so tapping a
/// row can never open a different person than the row named.
const PEERS: [Peer; 10] = [
    Peer { id: 1002, nickname: "\u{1F64F}SATYA \u{3299}\u{FE0F} RAJPUT\u{1F64F}", avatar: "a2" },
    Peer { id: 1003, nickname: "Anjali Verma", avatar: "a10" },
    Peer { id: 1004, nickname: "desi jatt", avatar: "a5" },
    Peer { id: 1005, nickname: "Kundan rangdar \u{1F3A3}\u{274C}", avatar: "a4" },
    Peer { id: 1006, nickname: "Mubarak Alam", avatar: "a6" },
    Peer { id: 1007, nickname: "Gurjant Singh", avatar: "a7" },
    Peer { id: 1008, nickname: "Ranjeet Kumar", avatar: "a8" },
    Peer { id: 1009, nickname: "\u{0938}\u{094D}\u{0924}\u{093E}\u{0930}\u{0940} \u{0915}\u{0941}\u{091C}", avatar: "a9" },
    Peer { id: 1010, nickname: "\u{0930}\u{0942} \u{092A}\u{094D}\u{0930}\u{0924}\u{093E}\u{092A} \u{0938}\u{093F}\u{0902}\u{0939}", avatar: "a3" },
    Peer { id: 1011, nickname: "..", avatar: "a1" },
];

fn peer_profile(peer_id: u64) -> Option<(&'static str, &'static str)> {
    PEERS
        .iter()
        .find(|p| p.id == peer_id)
        .map(|p| (p.nickname, p.avatar))
}

struct Seed {
    key: &'static str,
    /// The person the row is about; always a member of [`PEERS`].
    peer_id: u64,
    preview: &'static str,
    minutes_ago: i64,
    unread: bool,
}

/// Seed rows mirror the product's real inbox: a mix of chat lines, Hindi and
/// emoji display names, and system-style "[Live] Shared a live room" entries.
fn seeds() -> Vec<Seed> {
    vec![
        Seed {
            key: "comments",
            peer_id: 1011,
            preview: "isme recharge karoo",
            minutes_ago: 60 * 24 * 27,
            unread: false,
        },
        Seed {
            key: "mentions",
            peer_id: 1002,
            preview: "good morning prisha",
            minutes_ago: 60 * 24 * 18,
            unread: false,
        },
        Seed {
            key: "comments",
            peer_id: 1010,
            preview: "kol kro na",
            minutes_ago: 60 * 24 * 18,
            unread: true,
        },
        Seed {
            key: "likes",
            peer_id: 1005,
            preview: "hi cutie friends \u{1F618}",
            minutes_ago: 60 * 24 * 19,
            unread: false,
        },
        Seed {
            key: "mentions",
            peer_id: 1004,
            preview: "[Live] Shared a live room",
            minutes_ago: 60 * 24 * 20,
            unread: false,
        },
        Seed {
            key: "gift",
            peer_id: 1006,
            preview: "[Live] Shared a live room",
            minutes_ago: 60 * 24 * 20,
            unread: false,
        },
        Seed {
            key: "followers",
            peer_id: 1007,
            preview: "[Live] Shared a live room",
            minutes_ago: 60 * 24 * 20,
            unread: false,
        },
        Seed {
            key: "comments",
            peer_id: 1008,
            preview: "nice stream bro",
            minutes_ago: 95,
            unread: true,
        },
        Seed {
            key: "likes",
            peer_id: 1009,
            preview: "liked your video",
            minutes_ago: 40,
            unread: true,
        },
        Seed {
            key: "gift",
            peer_id: 1003,
            preview: "sent you a Rose",
            minutes_ago: 150,
            unread: true,
        },
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
        .map(|s| {
            let (nickname, avatar) = peer_profile(s.peer_id).unwrap_or(("Friend", "a2"));
            InboxMessage {
                message_id: format!("inbox_{}_{}", s.key, s.minutes_ago),
                category: s.key.to_string(),
                sender_id: s.peer_id,
                sender_nickname: nickname.to_string(),
                sender_avatar_url: format!("{origin}/assets/avatars/{avatar}.jpg"),
                preview: s.preview.to_string(),
                created_at: now - Duration::minutes(s.minutes_ago),
                unread: s.unread,
            }
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

/// Time buckets of the reference notifications anatomy, in display order.
pub const OFFICIAL_GROUPS: [(&str, &str); 3] = [
    ("week", "This week"),
    ("month", "This month"),
    ("earlier", "Earlier"),
];

/// Peers whose follow requests are pending on a fresh account.
const FOLLOW_REQUEST_PEERS: [u64; 2] = [1007, 1008];

/// Suggested-for-you peers: (peer id, social proof line). The first one also
/// has a "who you might know" feed row, so its Follow pill and suggestion
/// state are exercised together.
const SUGGESTED_PEERS: [(u64, &str); 3] = [
    (1003, "Followed by desi jatt + 1 more"),
    (1010, "Suggested for you"),
    (1006, "Followed by Ranjeet Kumar + 2 more"),
];

/// One seeded row of the reference notifications anatomy. `body` carries
/// `{actor}` / `{actor2}` tokens (the row's people, rendered as tappable
/// names), `[tokens]` (highlight chips) and `@mentions` (gold).
struct ActivitySeed {
    section: &'static str,
    actors: &'static [u64],
    body: &'static str,
    /// notice | activity
    kind: &'static str,
    /// app | info (notice rows only).
    icon: &'static str,
    /// none | thumbnail | follow | confirm_delete
    trailing: &'static str,
    /// Follow/confirm target; 0 when the trailing is none/thumbnail.
    trailing_peer: u64,
    /// Media thumbnail file stem under /assets/covers, e.g. "3".
    image: &'static str,
    minutes_ago: i64,
}

/// The reference page's activity rows: who-might-you-know + Follow, replies
/// and mentions with a media thumbnail, stacked "A and B liked…" rows,
/// follow requests with Confirm/Delete, and system notices.
fn official_activity_seeds() -> Vec<ActivitySeed> {
    vec![
        // --- This week ---
        ActivitySeed {
            section: "official",
            actors: &[],
            body: "You're now on CamStar Accounts. Your coins and badges came along. \u{1F389}",
            kind: "notice",
            icon: "app",
            trailing: "none",
            trailing_peer: 0,
            image: "",
            minutes_ago: 60 * 24 * 4,
        },
        ActivitySeed {
            section: "official",
            actors: &[1003],
            body: "{actor}, who you might know, is on CamStar.",
            kind: "activity",
            icon: "",
            trailing: "follow",
            trailing_peer: 1003,
            image: "",
            minutes_ago: 60 * 24 * 2,
        },
        ActivitySeed {
            section: "official",
            actors: &[1004],
            body: "{actor} replied to your comment: @prisha \u{1F44F}",
            kind: "activity",
            icon: "",
            trailing: "thumbnail",
            trailing_peer: 0,
            image: "3",
            minutes_ago: 60 * 30,
        },
        ActivitySeed {
            section: "official",
            actors: &[1005, 1006],
            body: "{actor} and {actor2} liked your comment: @prisha",
            kind: "activity",
            icon: "",
            trailing: "thumbnail",
            trailing_peer: 0,
            image: "5",
            minutes_ago: 60 * 24 * 5,
        },
        // --- This month ---
        ActivitySeed {
            section: "official",
            actors: &[1008],
            body: "{actor} mentioned you in a comment: @prisha Nice! Check your DMs! \u{1F622}",
            kind: "activity",
            icon: "",
            trailing: "thumbnail",
            trailing_peer: 0,
            image: "7",
            minutes_ago: 60 * 24 * 12,
        },
        ActivitySeed {
            section: "official",
            actors: &[1009, 1010],
            body: "{actor}, {actor2} and 40 others liked your post.",
            kind: "activity",
            icon: "",
            trailing: "thumbnail",
            trailing_peer: 0,
            image: "9",
            minutes_ago: 60 * 24 * 15,
        },
        ActivitySeed {
            section: "official",
            actors: &[],
            body: "Your support request from 24 August was just updated.",
            kind: "notice",
            icon: "info",
            trailing: "none",
            trailing_peer: 0,
            image: "",
            minutes_ago: 60 * 24 * 20,
        },
        ActivitySeed {
            section: "official",
            actors: &[1007],
            body: "{actor} requested to follow you.",
            kind: "activity",
            icon: "",
            trailing: "confirm_delete",
            trailing_peer: 1007,
            image: "",
            minutes_ago: 60 * 24 * 25,
        },
        // --- Earlier ---
        ActivitySeed {
            section: "official",
            actors: &[1002],
            body: "{actor} started following you.",
            kind: "activity",
            icon: "",
            trailing: "follow",
            trailing_peer: 1002,
            image: "",
            minutes_ago: 60 * 24 * 45,
        },
        ActivitySeed {
            section: "official",
            actors: &[1008],
            body: "{actor} requested to follow you.",
            kind: "activity",
            icon: "",
            trailing: "confirm_delete",
            trailing_peer: 1008,
            image: "",
            minutes_ago: 60 * 24 * 45,
        },
        ActivitySeed {
            section: "official",
            actors: &[],
            body: "Reminder: Review CamStar's Terms of Use and Privacy Policy.",
            kind: "notice",
            icon: "info",
            trailing: "none",
            trailing_peer: 0,
            image: "",
            minutes_ago: 60 * 24 * 60,
        },
        ActivitySeed {
            section: "official",
            actors: &[1005],
            body: "{actor}, who you might know, is on CamStar.",
            kind: "activity",
            icon: "",
            trailing: "follow",
            trailing_peer: 1005,
            image: "",
            minutes_ago: 60 * 24 * 70,
        },
        ActivitySeed {
            section: "official",
            actors: &[1009, 1010],
            body: "{actor}, {actor2} and 1 other liked your story.",
            kind: "activity",
            icon: "",
            trailing: "thumbnail",
            trailing_peer: 0,
            image: "11",
            minutes_ago: 60 * 24 * 75,
        },
    ]
}

/// Which time bucket a row of `minutes_ago` age lands in: the reference
/// page shows "This week", then "This month", then "Earlier".
fn group_index(minutes_ago: i64) -> usize {
    const WEEK: i64 = 60 * 24 * 7;
    const MONTH: i64 = 60 * 24 * 30;
    if minutes_ago <= WEEK {
        0
    } else if minutes_ago <= MONTH {
        1
    } else {
        2
    }
}

/// Assembles the Official tab: follow-request summary, time buckets and the
/// suggested block. With a `section` filter (a sub-tab chip) only that
/// section's rows remain and the summary/suggested blocks stay empty.
async fn build_official_page(
    state: &AppState,
    reader: Option<u64>,
    section: Option<&str>,
) -> OfficialNoticePage {
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

    // Per-reader state, so pills survive reloads and resolved requests never
    // come back. Unauthenticated readers see the defaults (nothing followed).
    let following: HashSet<u64> = {
        let follows = state.follows.read().await;
        reader
            .and_then(|r| follows.get(&r).cloned())
            .unwrap_or_default()
    };
    let resolved: HashMap<u64, FollowRequestOutcome> = {
        let resolutions = state.follow_request_resolutions.read().await;
        reader
            .and_then(|r| resolutions.get(&r).cloned())
            .unwrap_or_default()
    };

    let actor = |peer_id: u64| {
        peer_profile(peer_id).map(|(nickname, avatar)| OfficialActor {
            user_id: peer_id,
            nickname: nickname.to_string(),
            avatar_url: format!("{origin}/assets/avatars/{avatar}.jpg"),
        })
    };

    let mut buckets = [Vec::new(), Vec::new(), Vec::new()];

    // Legacy notice seeds become `notice` rows inside the same buckets, so the
    // existing Officially / Event / Picked / Task copy keeps its place.
    for s in seeds.iter() {
        buckets[group_index(s.minutes_ago)].push(OfficialRow {
            row_id: format!("official_{}_{}", s.section, s.minutes_ago),
            kind: "notice".to_string(),
            section: s.section.to_string(),
            icon: "app".to_string(),
            body: format!("{} {}", s.title, s.body),
            actors: Vec::new(),
            trailing: OfficialTrailing {
                kind: "none".to_string(),
                ..Default::default()
            },
            created_at: now - Duration::minutes(s.minutes_ago),
        });
    }

    for (index, s) in official_activity_seeds().into_iter().enumerate() {
        let mut body = s.body.to_string();
        let mut trailing = s.trailing;
        if s.trailing == "confirm_delete" {
            match resolved.get(&s.trailing_peer) {
                // Deleted requests leave the feed entirely.
                Some(FollowRequestOutcome::Deleted) => continue,
                // Confirming turns the request into "started following you".
                Some(FollowRequestOutcome::Confirmed) => {
                    body = "{actor} started following you.".to_string();
                    trailing = "follow";
                }
                None => {}
            }
        }
        let actors: Vec<OfficialActor> = s.actors.iter().filter_map(|p| actor(*p)).collect();
        let target = if trailing == "follow" || trailing == "confirm_delete" {
            s.trailing_peer
        } else {
            0
        };
        buckets[group_index(s.minutes_ago)].push(OfficialRow {
            row_id: format!("official_activity_{index}"),
            kind: s.kind.to_string(),
            section: s.section.to_string(),
            icon: s.icon.to_string(),
            body,
            actors,
            trailing: OfficialTrailing {
                kind: trailing.to_string(),
                user_id: target,
                is_following: target != 0 && following.contains(&target),
                image_url: if s.image.is_empty() {
                    String::new()
                } else {
                    format!("{origin}/assets/covers/{}.jpg", s.image)
                },
            },
            created_at: now - Duration::minutes(s.minutes_ago),
        });
    }

    // Newest first inside each bucket, exactly like the reference page.
    for rows in buckets.iter_mut() {
        rows.sort_by_key(|r| std::cmp::Reverse(r.created_at));
    }

    let filtered = section.is_some();
    let groups: Vec<OfficialGroup> = OFFICIAL_GROUPS
        .iter()
        .zip(buckets)
        .filter_map(|((key, label), rows)| {
            let mut rows = rows;
            if let Some(wanted) = section {
                rows.retain(|r| r.section == wanted);
            }
            if rows.is_empty() {
                return None;
            }
            Some(OfficialGroup {
                key: (*key).to_string(),
                label: (*label).to_string(),
                rows,
            })
        })
        .collect();

    let pending: Vec<OfficialActor> = if filtered {
        Vec::new()
    } else {
        FOLLOW_REQUEST_PEERS
            .iter()
            .filter(|p| !resolved.contains_key(p))
            .filter_map(|p| actor(*p))
            .collect()
    };
    let subtitle = match pending.len() {
        0 => String::new(),
        1 => pending[0].nickname.clone(),
        n => format!(
            "{} + {} other{}",
            pending[0].nickname,
            n - 1,
            if n > 2 { "s" } else { "" }
        ),
    };
    let follow_requests = OfficialFollowRequests {
        title: "Follow requests".to_string(),
        subtitle,
        avatars: pending
            .iter()
            .take(2)
            .map(|a| a.avatar_url.clone())
            .collect(),
        pending,
    };

    let suggested = OfficialSuggested {
        label: "Suggested for you".to_string(),
        see_all_label: "See all suggestions".to_string(),
        see_all_target: "search".to_string(),
        users: if filtered {
            Vec::new()
        } else {
            SUGGESTED_PEERS
                .iter()
                .filter_map(|(peer_id, proof)| {
                    actor(*peer_id).map(|a| OfficialSuggestion {
                        user_id: a.user_id,
                        nickname: a.nickname,
                        avatar_url: a.avatar_url,
                        social_proof: (*proof).to_string(),
                        is_following: following.contains(peer_id),
                    })
                })
                .collect()
        },
    };

    // The legacy card list stays byte-identical so a rolled-back APK keeps
    // rendering. Its image path now points at a file that actually exists.
    let notices = seeds
        .into_iter()
        .filter(|s| section.map(|c| c == s.section).unwrap_or(true))
        .map(|s| OfficialNotice {
            notice_id: format!("official_{}_{}", s.section, s.minutes_ago),
            section: s.section.to_string(),
            title: s.title.to_string(),
            body: s.body.to_string(),
            image_url: if s.image.is_empty() {
                String::new()
            } else {
                format!("{origin}/assets/covers/2.jpg")
            },
            created_at: now - Duration::minutes(s.minutes_ago),
        })
        .collect();

    OfficialNoticePage {
        sections,
        notices,
        groups,
        follow_requests,
        suggested,
    }
}

pub async fn inbox_official(
    headers: HeaderMap,
    State(state): State<AppState>,
    Query(q): Query<OfficialQuery>,
) -> Json<OfficialNoticePage> {
    let reader = state
        .authenticate_header(headers.get("Authorization").and_then(|h| h.to_str().ok()))
        .await;
    Json(build_official_page(&state, reader, q.section.as_deref()).await)
}

/// POST /api/v1/inbox/follow-requests/{peer_id}/confirm — the request becomes
/// a "started following you" row. Returns the refreshed feed so the client
/// needs one round-trip, exactly like the message inbox's mark-read.
pub async fn confirm_follow_request(
    headers: HeaderMap,
    Path(peer_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<OfficialNoticePage>, ApiError> {
    resolve_follow_request(headers, peer_id, state, FollowRequestOutcome::Confirmed).await
}

/// POST /api/v1/inbox/follow-requests/{peer_id}/delete — the request leaves
/// the feed for good.
pub async fn delete_follow_request(
    headers: HeaderMap,
    Path(peer_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<OfficialNoticePage>, ApiError> {
    resolve_follow_request(headers, peer_id, state, FollowRequestOutcome::Deleted).await
}

async fn resolve_follow_request(
    headers: HeaderMap,
    peer_id: u64,
    state: AppState,
    outcome: FollowRequestOutcome,
) -> Result<Json<OfficialNoticePage>, ApiError> {
    let reader = state
        .authenticate_header(headers.get("Authorization").and_then(|h| h.to_str().ok()))
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;
    if !FOLLOW_REQUEST_PEERS.contains(&peer_id) {
        return Err(ApiError::BadRequest("Not a pending follow request".into()));
    }
    state
        .follow_request_resolutions
        .write()
        .await
        .entry(reader)
        .or_default()
        .insert(peer_id, outcome);
    Ok(Json(build_official_page(&state, Some(reader), None).await))
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

/// One seeded conversation. Filled in above the live (in-memory) threads so
/// a fresh account already has an inbox, exactly like the reference list.
struct ConversationSeed {
    peer_id: u64,
    /// Oldest-to-newest canned history. Opening a seeded row shows this
    /// thread, and `unread` of its lines are the peer's unread messages, so
    /// the badge, the preview and the thread always agree.
    starter: &'static [&'static str],
    minutes_ago: i64,
    unread: u64,
}

fn conversation_seeds() -> Vec<ConversationSeed> {
    vec![
        ConversationSeed {
            peer_id: 1002,
            starter: &[
                "Hey! Welcome to CamStar \u{2728}",
                "prisha tum online ho?",
                "aaj live aane wale ho?",
                "good morning prisha",
            ],
            minutes_ago: 60 * 30,
            unread: 4,
        },
        ConversationSeed {
            peer_id: 1003,
            starter: &[
                "hi \u{1F60A}",
                "kal ka stream mast tha",
                "tumne jo gift bheja woh cute tha",
                "aaj phir aana \u{1F3A4}",
            ],
            minutes_ago: 60 * 30,
            unread: 4,
        },
        ConversationSeed {
            peer_id: 1004,
            starter: &[
                "bhai",
                "stream dekh liya?",
                "gift wala scene samajh gaya",
                "reply kar jaldi",
            ],
            minutes_ago: 60 * 30,
            unread: 4,
        },
        ConversationSeed {
            peer_id: 1005,
            starter: &["hi cutie friends \u{1F618}"],
            minutes_ago: 60 * 60,
            unread: 1,
        },
        ConversationSeed {
            peer_id: 1006,
            starter: &["bhai room join kar na"],
            minutes_ago: 60 * 60,
            unread: 1,
        },
        ConversationSeed {
            peer_id: 1007,
            starter: &["see you tonight \u{1F3A4}"],
            minutes_ago: 60 * 132,
            unread: 0,
        },
        ConversationSeed {
            peer_id: 1008,
            starter: &["nice stream bro \u{1F44F}"],
            minutes_ago: 60 * 24 * 10,
            unread: 0,
        },
        ConversationSeed {
            peer_id: 1009,
            starter: &["bahut acchi performance thi \u{1F64F}"],
            minutes_ago: 60 * 24 * 18,
            unread: 0,
        },
        ConversationSeed {
            peer_id: 1010,
            starter: &["kol kro na"],
            minutes_ago: 60 * 24 * 18,
            unread: 0,
        },
        ConversationSeed {
            peer_id: 1011,
            starter: &["isme recharge karoo"],
            minutes_ago: 60 * 24 * 18,
            unread: 0,
        },
    ]
}

fn conversation_seed(peer_id: u64) -> Option<ConversationSeed> {
    conversation_seeds()
        .into_iter()
        .find(|s| s.peer_id == peer_id)
}

/// The message inbox for `reader_id`: one row per conversation, newest first,
/// with the live in-memory thread overriding the seed when one exists.
async fn build_conversation_page(state: &AppState, reader_id: u64) -> ConversationPage {
    let origin = crate::public_origin();
    let now = Utc::now();

    let users = state.users.read().await;
    let stored = state.direct_messages.read().await;
    let read = state.read_conversations.read().await;

    let profile = |peer_id: u64| -> (String, String) {
        if let Some(u) = users.get(&peer_id) {
            (u.nickname.clone(), u.avatar_url.clone())
        } else if let Some((nickname, avatar)) = peer_profile(peer_id) {
            (
                nickname.to_string(),
                format!("{origin}/assets/avatars/{avatar}.jpg"),
            )
        } else {
            (
                "Friend".to_string(),
                format!("{origin}/assets/avatars/a2.jpg"),
            )
        }
    };

    let mut conversations: Vec<ConversationSummary> = Vec::new();

    for seed in conversation_seeds() {
        let (peer_nickname, peer_avatar_url) = profile(seed.peer_id);

        let mut preview = seed.starter.last().copied().unwrap_or_default().to_string();
        let mut last_message_at = now - Duration::minutes(seed.minutes_ago);
        let mut unread_count = seed.unread;

        // A live thread wins over the seed: real lines, real timestamp, and
        // nothing unread (the reader has obviously seen their own thread).
        if let Some(newest) = stored
            .get(&conversation_key(reader_id, seed.peer_id))
            .and_then(|lines| lines.last())
        {
            preview = if newest.sender_id == reader_id {
                format!("You: {}", newest.text)
            } else {
                newest.text.clone()
            };
            last_message_at = newest.created_at;
            unread_count = 0;
        }

        if read.contains(&(reader_id, seed.peer_id)) {
            unread_count = 0;
        }

        conversations.push(ConversationSummary {
            peer_id: seed.peer_id,
            peer_nickname,
            peer_avatar_url,
            preview,
            last_message_at,
            unread_count,
        });
    }

    // Threads that exist only in memory (e.g. a freshly registered friend)
    // still make the list.
    for ((a, b), lines) in stored.iter() {
        let peer_id = if *a == reader_id {
            *b
        } else if *b == reader_id {
            *a
        } else {
            continue;
        };
        if conversations.iter().any(|c| c.peer_id == peer_id) {
            continue;
        }
        let Some(newest) = lines.last() else {
            continue;
        };
        let (peer_nickname, peer_avatar_url) = profile(peer_id);
        conversations.push(ConversationSummary {
            peer_id,
            peer_nickname,
            peer_avatar_url,
            preview: if newest.sender_id == reader_id {
                format!("You: {}", newest.text)
            } else {
                newest.text.clone()
            },
            last_message_at: newest.created_at,
            unread_count: 0,
        });
    }

    conversations.sort_by(|a, b| b.last_message_at.cmp(&a.last_message_at));

    ConversationPage { conversations }
}

/// `GET /api/v1/inbox/conversations` — the message inbox list.
pub async fn list_conversations(
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<ConversationPage>, crate::error::ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let reader_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| crate::error::ApiError::Unauthorized("Sign in required".into()))?;

    Ok(Json(build_conversation_page(&state, reader_id).await))
}

/// `POST /api/v1/conversations/{user_id}/read` — opening a thread clears its
/// unread badge. Returns the refreshed list so the client stays in sync in
/// one round-trip.
pub async fn mark_conversation_read(
    Path(peer_id): Path<u64>,
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<ConversationPage>, crate::error::ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let reader_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| crate::error::ApiError::Unauthorized("Sign in required".into()))?;

    {
        let mut read = state.read_conversations.write().await;
        read.insert((reader_id, peer_id));
    }

    Ok(Json(build_conversation_page(&state, reader_id).await))
}

pub async fn get_conversation(
    Path(target_user_id): Path<u64>,
    headers: HeaderMap,
    State(state): State<AppState>,
) -> Result<Json<DirectMessageThread>, crate::error::ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let current_user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| crate::error::ApiError::Unauthorized("Sign in required".into()))?;

    let origin = crate::public_origin();
    let users = state.users.read().await;
    let (target_nickname, target_avatar) = if let Some(u) = users.get(&target_user_id) {
        (u.nickname.clone(), u.avatar_url.clone())
    } else if let Some((nickname, avatar)) = peer_profile(target_user_id) {
        (
            nickname.to_string(),
            format!("{origin}/assets/avatars/{avatar}.jpg"),
        )
    } else {
        (
            "Friend".to_string(),
            format!("{origin}/assets/avatars/a2.jpg"),
        )
    };

    let seed = conversation_seed(target_user_id);
    let starter: &[&str] = seed.as_ref().map(|s| s.starter).unwrap_or(&[]);
    let seed_minutes = seed.as_ref().map(|s| s.minutes_ago).unwrap_or(15);

    let key = conversation_key(current_user_id, target_user_id);
    let dm_map = state.direct_messages.read().await;

    let mut messages: Vec<DirectMessage> = if let Some(stored) = dm_map.get(&key) {
        // The seeded history stays readable after the first live message;
        // stored lines follow it, oldest first.
        let base = stored
            .first()
            .map(|m| m.created_at)
            .unwrap_or_else(Utc::now);
        let mut msgs: Vec<DirectMessage> = starter
            .iter()
            .enumerate()
            .map(|(i, line)| DirectMessage {
                message_id: format!("init_{target_user_id}_{i}"),
                sender_id: target_user_id,
                recipient_id: current_user_id,
                text: (*line).to_string(),
                created_at: base - Duration::minutes(4 * (starter.len() - i) as i64),
                is_self: false,
            })
            .collect();
        msgs.extend(stored.iter().map(|m| DirectMessage {
            message_id: m.message_id.clone(),
            sender_id: m.sender_id,
            recipient_id: m.recipient_id,
            text: m.text.clone(),
            created_at: m.created_at,
            is_self: m.sender_id == current_user_id,
        }));
        msgs
    } else if !starter.is_empty() {
        let now = Utc::now();
        let last = starter.len() as i64 - 1;
        starter
            .iter()
            .enumerate()
            .map(|(i, line)| DirectMessage {
                message_id: format!("init_{target_user_id}_{i}"),
                sender_id: target_user_id,
                recipient_id: current_user_id,
                text: (*line).to_string(),
                // The newest line lands on the list row's timestamp, so the
                // inbox row and the opened thread always agree.
                created_at: now - Duration::minutes(seed_minutes + (last - i as i64) * 4),
                is_self: false,
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
    let current_user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| crate::error::ApiError::Unauthorized("Sign in required".into()))?;

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
