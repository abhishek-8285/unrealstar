use axum::{
    extract::{Path, Query, State},
    http::HeaderMap,
    Json,
};
use chrono::Utc;
use std::collections::HashSet;
use uuid::Uuid;

use crate::{
    error::ApiError,
    models::{
        CommentList, CreateCommentRequest, CreateMomentRequest, FeedMomentsQuery, FeedPage,
        FeedPost, FeedRoomItem, FeedRoomsQuery, LiveBanner, LiveBannerPage, MomentComment,
        MomentItem, RoomPage,
    },
    state::AppState,
};

pub async fn follow_user(
    headers: HeaderMap,
    Path(target_user_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut follows = state.follows.write().await;
    if target_user_id == user_id {
        return Err(ApiError::BadRequest("Cannot follow yourself".into()));
    }
    let user_follows = follows.entry(user_id).or_insert_with(HashSet::new);
    if !user_follows.insert(target_user_id) {
        // Already following — idempotent, do not double-count.
        return Ok(Json(serde_json::json!({ "is_following": true })));
    }

    // Increment follower count
    let mut users = state.users.write().await;
    if let Some(target) = users.get_mut(&target_user_id) {
        target.followers_count += 1;
    }
    if let Some(me) = users.get_mut(&user_id) {
        me.following_count += 1;
    }

    Ok(Json(serde_json::json!({ "is_following": true })))
}

pub async fn unfollow_user(
    headers: HeaderMap,
    Path(target_user_id): Path<u64>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let mut follows = state.follows.write().await;
    let was_following = follows
        .get_mut(&user_id)
        .map(|s| s.remove(&target_user_id))
        .unwrap_or(false);
    if !was_following {
        // Idempotent: not following — do not decrement counters.
        return Ok(Json(serde_json::json!({ "is_following": false })));
    }

    let mut users = state.users.write().await;
    if let Some(target) = users.get_mut(&target_user_id) {
        if target.followers_count > 0 {
            target.followers_count -= 1;
        }
    }
    if let Some(me) = users.get_mut(&user_id) {
        if me.following_count > 0 {
            me.following_count -= 1;
        }
    }

    Ok(Json(serde_json::json!({ "is_following": false })))
}

/// Resolves the caller from the bearer token. Optional because a few feed
/// reads are worth serving to anonymous callers.
async fn optional_user(state: &AppState, headers: &HeaderMap) -> Option<u64> {
    let raw = headers.get("Authorization").and_then(|h| h.to_str().ok());
    state.authenticate_header(raw).await
}

pub async fn feed_rooms(
    State(state): State<AppState>,
    Query(q): Query<FeedRoomsQuery>,
) -> Json<RoomPage> {
    let rooms = state.rooms.read().await;
    let users = state.users.read().await;

    let mut items: Vec<FeedRoomItem> = rooms
        .values()
        .filter(|r| r.is_active)
        .map(|r| {
            let host_nick = users
                .get(&r.host_id)
                .map(|u| u.nickname.clone())
                .unwrap_or_else(|| format!("Host_{}", r.host_id));

            FeedRoomItem {
                room_id: r.room_id,
                title: r.title.clone(),
                cover_image_url: r.cover_image_url.clone(),
                host_id: r.host_id,
                host_nickname: host_nick,
                viewers_count: r.roster.viewer_count(),
                is_pk: r.is_pk,
                category: r.category.clone(),
                rank_tag: r.rank_tag.clone(),
                host_badge: r.host_badge.clone(),
                pk_opponent: r.pk_opponent.clone(),
            }
        })
        .collect();

    // The tab filter the spec always described but the handler previously
    // ignored: `hot` ranks by viewers, the rest select or order.
    let tab = q.tab.as_deref().unwrap_or("hot");
    match tab {
        "pk" => items.retain(|r| r.is_pk),
        "nearby" => items.retain(|r| r.category == "nearby"),
        "party" => items.retain(|r| r.category == "party"),
        "new_stars" => items.sort_by_key(|a| std::cmp::Reverse(a.room_id)),
        // "hot" and anything unrecognised fall back to viewer ranking.
        _ => items.sort_by_key(|a| std::cmp::Reverse(a.viewers_count)),
    }

    let page_size = q.page_size.unwrap_or(20).clamp(1, 100) as usize;
    let page = q.page.unwrap_or(0);
    let total = items.len();
    let total_pages = total.div_ceil(page_size).max(1) as u32;
    let start = (page as usize) * page_size;
    let paged: Vec<FeedRoomItem> = items.into_iter().skip(start).take(page_size).collect();

    Json(RoomPage {
        rooms: paged,
        page,
        total: total as u32,
        total_pages,
    })
}

pub async fn feed_live_banners() -> Json<LiveBannerPage> {
    // Promoted slides above the Live grid. Static for now: promotion
    // scheduling is not modelled yet, but the response shape is final so
    // the client carousel does not change when it lands.
    Json(LiveBannerPage {
        banners: vec![
            LiveBanner {
                banner_id: "promo_welcome".into(),
                title: "Welcome Bonus".into(),
                subtitle: "Claim free coins on your first live room".into(),
                image_url: format!("{}/assets/banners/1.jpg", crate::public_origin()),
                action_url: "camstar://live/banners/welcome".into(),
            },
            LiveBanner {
                banner_id: "promo_pk".into(),
                title: "PK Showdown".into(),
                subtitle: "Top hosts battle for the crown tonight".into(),
                image_url: format!("{}/assets/banners/2.jpg", crate::public_origin()),
                action_url: "camstar://live/banners/pk".into(),
            },
            LiveBanner {
                banner_id: "promo_hourly".into(),
                title: "Hourly TOP!".into(),
                subtitle: "Hourly leaderboard with double diamonds".into(),
                image_url: format!("{}/assets/banners/3.jpg", crate::public_origin()),
                action_url: "camstar://live/banners/hourly".into(),
            },
        ],
    })
}

pub async fn feed_moments(
    headers: HeaderMap,
    State(state): State<AppState>,
    Query(q): Query<FeedMomentsQuery>,
) -> Json<FeedPage> {
    let viewer = optional_user(&state, &headers).await;
    let moments = state.moments.read().await;
    let users = state.users.read().await;
    let likes = state.moment_likes.read().await;

    let follows = state.follows.read().await;
    let followed_set = if q.followed_only.unwrap_or(false) {
        viewer.and_then(|uid| follows.get(&uid).cloned())
    } else {
        None
    };

    // Newest first; if followed_only is requested, limit to authors in followed_set.
    let mut ordered: Vec<&MomentItem> = if q.followed_only.unwrap_or(false) {
        match &followed_set {
            Some(set) => moments.iter().filter(|m| set.contains(&m.author_id)).collect(),
            None => Vec::new(),
        }
    } else {
        moments.iter().collect()
    };
    ordered.reverse();

    let limit = q.limit.unwrap_or(10).clamp(1, 50) as usize;
    let start: usize = q
        .cursor
        .as_deref()
        .and_then(|c| c.parse::<usize>().ok())
        .unwrap_or_default();
    if start >= ordered.len() {
        return Json(FeedPage {
            posts: Vec::new(),
            next_cursor: None,
        });
    }

    let end = (start + limit).min(ordered.len());
    let posts: Vec<FeedPost> = ordered[start..end]
        .iter()
        .map(|m| {
            let profile = users.get(&m.author_id);
            let liked = match viewer {
                Some(uid) => likes
                    .get(&m.moment_id)
                    .map(|s| s.contains(&uid))
                    .unwrap_or(false),
                None => false,
            };

            FeedPost {
                moment_id: m.moment_id.clone(),
                author_id: m.author_id,
                author_nickname: profile
                    .map(|p| p.nickname.clone())
                    .unwrap_or_else(|| m.author_nickname.clone()),
                author_avatar_url: profile
                    .map(|p| p.avatar_url.clone())
                    .filter(|u| !u.is_empty())
                    .unwrap_or_else(|| m.avatar_url.clone()),
                media_url: m.media_url.clone(),
                cover_url: m.cover_url.clone(),
                caption: m.caption.clone(),
                duration_secs: m.duration_secs,
                likes_count: m.likes_count,
                comment_count: m.comment_count,
                share_count: m.share_count,
                gift_count: m.gift_count,
                promo_text: m.promo_text.clone(),
                tags: m.tags.clone(),
                liked_by_me: liked,
                created_at: m.created_at,
            }
        })
        .collect();

    let next_cursor = if end < ordered.len() {
        Some(end.to_string())
    } else {
        None
    };

    Json(FeedPage { posts, next_cursor })
}

pub async fn create_moment(
    headers: HeaderMap,
    State(state): State<AppState>,
    Json(req): Json<CreateMomentRequest>,
) -> Result<Json<MomentItem>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let user_id = state
        .authenticate_header(auth_header)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))?;

    let author_nickname = {
        let users = state.users.read().await;
        users
            .get(&user_id)
            .map(|u| u.nickname.clone())
            .unwrap_or_else(|| format!("User_{user_id}"))
    };

    let moment = MomentItem {
        moment_id: format!("moment_{}", Uuid::new_v4()),
        author_id: user_id,
        author_nickname,
        media_url: req.media_url,
        caption: req.caption,
        duration_secs: req.duration_secs,
        likes_count: 0,
        created_at: Utc::now(),
        gift_count: 0,
        comment_count: 0,
        share_count: 0,
        avatar_url: String::new(),
        cover_url: req.cover_url.unwrap_or_default(),
        promo_text: req.promo_text,
        tags: normalise_tags(req.tags),
    };

    let mut moments = state.moments.write().await;
    moments.push(moment.clone());

    Ok(Json(moment))
}

/// The four counters the feed action rail renders.
#[derive(Clone, Copy)]
enum Engagement {
    Like,
    Comment,
    Share,
    Gift,
}

/// Increments one counter and returns the full engagement snapshot, so the
/// client can reconcile every rail number from a single response.
async fn bump_moment(
    state: &AppState,
    moment_id: &str,
    kind: Engagement,
) -> Result<serde_json::Value, ApiError> {
    let mut moments = state.moments.write().await;
    let moment = moments
        .iter_mut()
        .find(|m| m.moment_id == moment_id)
        .ok_or_else(|| ApiError::NotFound("Moment not found".into()))?;

    match kind {
        Engagement::Like => moment.likes_count += 1,
        Engagement::Comment => moment.comment_count += 1,
        Engagement::Share => moment.share_count += 1,
        Engagement::Gift => moment.gift_count += 1,
    }

    Ok(serde_json::json!({
        "moment_id": moment.moment_id,
        "likes_count": moment.likes_count,
        "comment_count": moment.comment_count,
        "share_count": moment.share_count,
        "gift_count": moment.gift_count,
    }))
}

/// Like is per-user and idempotent: a second like from the same account must
/// not inflate the count, and a like must be retractable.
pub async fn like_moment(
    headers: HeaderMap,
    Path(moment_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user_id = require_user(&state, &headers).await?;

    {
        let moments = state.moments.read().await;
        if !moments.iter().any(|m| m.moment_id == moment_id) {
            return Err(ApiError::NotFound("Moment not found".into()));
        }
    }

    let already = {
        let likes = state.moment_likes.read().await;
        likes
            .get(&moment_id)
            .map(|s| s.contains(&user_id))
            .unwrap_or(false)
    };

    if !already {
        state
            .moment_likes
            .write()
            .await
            .entry(moment_id.clone())
            .or_default()
            .insert(user_id);
        bump_moment(&state, &moment_id, Engagement::Like).await?;
    }

    engagement_snapshot(&state, &moment_id).await
}

pub async fn unlike_moment(
    headers: HeaderMap,
    Path(moment_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user_id = require_user(&state, &headers).await?;

    let removed = state
        .moment_likes
        .write()
        .await
        .get_mut(&moment_id)
        .map(|s| s.remove(&user_id))
        .unwrap_or(false);

    if removed {
        let mut moments = state.moments.write().await;
        if let Some(m) = moments.iter_mut().find(|m| m.moment_id == moment_id) {
            m.likes_count = m.likes_count.saturating_sub(1);
        }
    }

    engagement_snapshot(&state, &moment_id).await
}

async fn require_user(state: &AppState, headers: &HeaderMap) -> Result<u64, ApiError> {
    let raw = headers.get("Authorization").and_then(|h| h.to_str().ok());
    state
        .authenticate_header(raw)
        .await
        .ok_or_else(|| ApiError::Unauthorized("Missing or invalid token".into()))
}

async fn engagement_snapshot(
    state: &AppState,
    moment_id: &str,
) -> Result<Json<serde_json::Value>, ApiError> {
    let moments = state.moments.read().await;
    let moment = moments
        .iter()
        .find(|m| m.moment_id == moment_id)
        .ok_or_else(|| ApiError::NotFound("Moment not found".into()))?;
    Ok(Json(serde_json::json!({
        "moment_id": moment.moment_id,
        "likes_count": moment.likes_count,
        "comment_count": moment.comment_count,
        "share_count": moment.share_count,
        "gift_count": moment.gift_count,
    })))
}

pub async fn comment_moment(
    headers: HeaderMap,
    Path(moment_id): Path<String>,
    State(state): State<AppState>,
    Json(req): Json<CreateCommentRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user_id = require_user(&state, &headers).await?;
    let body = req.body.trim();
    if body.is_empty() {
        return Err(ApiError::BadRequest("Comment body cannot be empty".into()));
    }

    let nickname = {
        let users = state.users.read().await;
        users
            .get(&user_id)
            .map(|u| u.nickname.clone())
            .unwrap_or_else(|| format!("User_{user_id}"))
    };

    {
        let moments = state.moments.read().await;
        if !moments.iter().any(|m| m.moment_id == moment_id) {
            return Err(ApiError::NotFound("Moment not found".into()));
        }
    }

    // A reply must point at a comment on the same moment, and we keep threads
    // one level deep so replies can never nest without bound.
    if let Some(parent_id) = req.parent_id.as_deref() {
        let comments = state.moment_comments.read().await;
        match comments
            .get(&moment_id)
            .and_then(|list| list.iter().find(|c| c.comment_id == parent_id))
        {
            Some(parent) if parent.parent_id.is_none() => {}
            Some(_) => {
                return Err(ApiError::BadRequest(
                    "Replies are limited to one level".into(),
                ))
            }
            None => return Err(ApiError::NotFound("Parent comment not found".into())),
        }
    }

    let origin = crate::public_origin();
    let comment = MomentComment {
        comment_id: format!("comment_{}", Uuid::new_v4()),
        moment_id: moment_id.clone(),
        parent_id: req.parent_id.clone(),
        author_id: user_id,
        author_nickname: nickname,
        author_avatar_url: Some(format!("{origin}/assets/avatars/{}.png", (user_id % 6) + 1)),
        body: body.to_string(),
        likes_count: 0,
        liked_by_me: false,
        created_at: Utc::now(),
    };
    let comment_id = comment.comment_id.clone();
    state
        .moment_comments
        .write()
        .await
        .entry(moment_id.clone())
        .or_default()
        .push(comment);

    // Only top-level comments count toward the moment's comment_count; replies
    // hang off a parent and are counted in reply_count instead.
    if req.parent_id.is_none() {
        bump_moment(&state, &moment_id, Engagement::Comment).await?;
    }

    let snapshot = engagement_snapshot(&state, &moment_id)
        .await?
        .as_object()
        .cloned()
        .unwrap_or_default();
    let mut payload = snapshot;
    payload.insert(
        "comment_id".to_string(),
        serde_json::Value::String(comment_id),
    );
    Ok(Json(serde_json::Value::Object(payload)))
}

pub async fn list_comments(
    headers: HeaderMap,
    Path(moment_id): Path<String>,
    State(state): State<AppState>,
) -> Json<CommentList> {
    let viewer = optional_user(&state, &headers).await;
    let comments = state.moment_comments.read().await;
    let likes = state.comment_likes.read().await;

    let list: Vec<MomentComment> = comments
        .get(&moment_id)
        .map(|raw| {
            raw.iter()
                .map(|c| {
                    let mut c = c.clone();
                    c.liked_by_me = match viewer {
                        Some(uid) => likes
                            .get(&c.comment_id)
                            .map(|s| s.contains(&uid))
                            .unwrap_or(false),
                        None => false,
                    };
                    c
                })
                .collect()
        })
        .unwrap_or_default();

    Json(CommentList { comments: list })
}

pub async fn like_comment(
    headers: HeaderMap,
    Path(comment_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user_id = require_user(&state, &headers).await?;
    let moment_id = {
        let comments = state.moment_comments.read().await;
        comments
            .iter()
            .find_map(|(moment_id, list)| {
                list.iter()
                    .find(|c| c.comment_id == comment_id)
                    .map(|_| moment_id.clone())
            })
            .ok_or_else(|| ApiError::NotFound("Comment not found".into()))?
    };

    let already = {
        let likes = state.comment_likes.read().await;
        likes
            .get(&comment_id)
            .map(|s| s.contains(&user_id))
            .unwrap_or(false)
    };

    if !already {
        state
            .comment_likes
            .write()
            .await
            .entry(comment_id.clone())
            .or_default()
            .insert(user_id);
        let mut comments = state.moment_comments.write().await;
        if let Some(c) = comments
            .get_mut(&moment_id)
            .and_then(|list| list.iter_mut().find(|c| c.comment_id == comment_id))
        {
            c.likes_count += 1;
        }
    }

    comment_like_snapshot(&state, &moment_id, &comment_id).await
}

pub async fn unlike_comment(
    headers: HeaderMap,
    Path(comment_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user_id = require_user(&state, &headers).await?;
    let moment_id = {
        let comments = state.moment_comments.read().await;
        comments
            .iter()
            .find_map(|(moment_id, list)| {
                list.iter()
                    .find(|c| c.comment_id == comment_id)
                    .map(|_| moment_id.clone())
            })
            .ok_or_else(|| ApiError::NotFound("Comment not found".into()))?
    };

    let removed = state
        .comment_likes
        .write()
        .await
        .get_mut(&comment_id)
        .map(|s| s.remove(&user_id))
        .unwrap_or(false);

    if removed {
        let mut comments = state.moment_comments.write().await;
        if let Some(c) = comments
            .get_mut(&moment_id)
            .and_then(|list| list.iter_mut().find(|c| c.comment_id == comment_id))
        {
            c.likes_count = c.likes_count.saturating_sub(1);
        }
    }

    comment_like_snapshot(&state, &moment_id, &comment_id).await
}

async fn comment_like_snapshot(
    state: &AppState,
    moment_id: &str,
    comment_id: &str,
) -> Result<Json<serde_json::Value>, ApiError> {
    let comments = state.moment_comments.read().await;
    let comment = comments
        .get(moment_id)
        .and_then(|list| list.iter().find(|c| c.comment_id == comment_id))
        .ok_or_else(|| ApiError::NotFound("Comment not found".into()))?;
    Ok(Json(serde_json::json!({
        "comment_id": comment.comment_id,
        "likes_count": comment.likes_count,
    })))
}

/// Lowercases, strips '#', drops empties and de-duplicates, preserving order.
fn normalise_tags(tags: Option<Vec<String>>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for raw in tags.unwrap_or_default() {
        let cleaned = raw.trim().trim_start_matches('#').trim().to_lowercase();
        if cleaned.is_empty() || out.contains(&cleaned) {
            continue;
        }
        out.push(cleaned);
    }
    out.truncate(10);
    out
}

pub async fn share_moment(
    headers: HeaderMap,
    Path(moment_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let _user_id = require_user(&state, &headers).await?;
    let snapshot = bump_moment(&state, &moment_id, Engagement::Share).await?;
    Ok(Json(snapshot))
}

pub async fn gift_moment(
    headers: HeaderMap,
    Path(moment_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let _user_id = require_user(&state, &headers).await?;
    let snapshot = bump_moment(&state, &moment_id, Engagement::Gift).await?;
    Ok(Json(snapshot))
}

// ---------------------------------------------------------------------------
// Search (Trendo SearchActivity & SearchByUserActivity)
// ---------------------------------------------------------------------------

pub async fn search(
    headers: HeaderMap,
    Query(params): Query<crate::models::SearchQuery>,
    State(state): State<AppState>,
) -> Result<Json<crate::models::SearchResponse>, ApiError> {
    let auth_header = headers.get("Authorization").and_then(|h| h.to_str().ok());
    let current_user_id = state.authenticate_header(auth_header).await;

    let q = params.q.trim().to_lowercase();
    let filter = params.filter.as_deref().unwrap_or("all");

    let mut users_result = Vec::new();
    let mut rooms_result = Vec::new();
    let mut topics_result = Vec::new();

    let trending_tags = vec![
        "#Party".to_string(),
        "#Trending".to_string(),
        "#Dance".to_string(),
        "#Music".to_string(),
        "#Live".to_string(),
        "#Bollywood".to_string(),
        "#RisingStar".to_string(),
        "#Mushaira".to_string(),
    ];

    // Search Users
    if filter == "all" || filter == "users" {
        let users = state.users.read().await;
        let follows = state.follows.read().await;
        let my_follows = current_user_id
            .and_then(|uid| follows.get(&uid))
            .cloned()
            .unwrap_or_default();

        for user in users.values() {
            let matches = q.is_empty()
                || user.nickname.to_lowercase().contains(&q)
                || user.bio.to_lowercase().contains(&q)
                || user.user_id.to_string().contains(&q);

            if matches {
                users_result.push(crate::models::SearchUserItem {
                    user_id: user.user_id,
                    nickname: user.nickname.clone(),
                    avatar_url: user.avatar_url.clone(),
                    bio: user.bio.clone(),
                    level: 1,
                    followers_count: user.followers_count,
                    is_following: my_follows.contains(&user.user_id),
                    is_live: user.is_live,
                    room_id: user.live_room_id,
                });
            }
            if users_result.len() >= 20 {
                break;
            }
        }
    }

    // Search Rooms
    if filter == "all" || filter == "rooms" {
        let rooms = state.rooms.read().await;
        let users = state.users.read().await;

        for room in rooms.values() {
            if !room.is_active {
                continue;
            }
            let host_nick = users
                .get(&room.host_id)
                .map(|u| u.nickname.clone())
                .unwrap_or_else(|| format!("Host_{}", room.host_id));

            let matches = q.is_empty()
                || room.title.to_lowercase().contains(&q)
                || host_nick.to_lowercase().contains(&q)
                || room.category.to_lowercase().contains(&q)
                || room.room_id.to_string().contains(&q);

            if matches {
                rooms_result.push(crate::models::FeedRoomItem {
                    room_id: room.room_id,
                    title: room.title.clone(),
                    category: room.category.clone(),
                    cover_image_url: room.cover_image_url.clone(),
                    host_id: room.host_id,
                    host_nickname: host_nick,
                    viewers_count: room.viewers_count(),
                    is_pk: room.is_pk,
                    pk_opponent: room.pk_opponent.clone(),
                    rank_tag: room.rank_tag.clone(),
                    host_badge: room.host_badge.clone(),
                });
            }
            if rooms_result.len() >= 20 {
                break;
            }
        }
    }

    // Search Topics
    if filter == "all" || filter == "topics" {
        let moments = state.moments.read().await;
        let mut topic_counts: std::collections::HashMap<String, u64> =
            std::collections::HashMap::new();

        for tag in &trending_tags {
            topic_counts.insert(tag.clone(), 12);
        }

        for moment in moments.iter() {
            for tag in &moment.tags {
                let formatted = if tag.starts_with('#') {
                    tag.clone()
                } else {
                    format!("#{tag}")
                };
                *topic_counts.entry(formatted).or_insert(0) += 1;
            }
        }

        for (tag, count) in topic_counts {
            if q.is_empty() || tag.to_lowercase().contains(&q) {
                topics_result.push(crate::models::SearchTopicItem {
                    tag,
                    posts_count: count,
                });
            }
        }
        topics_result.sort_by(|a, b| b.posts_count.cmp(&a.posts_count));
        topics_result.truncate(15);
    }

    Ok(Json(crate::models::SearchResponse {
        query: params.q,
        users: users_result,
        rooms: rooms_result,
        topics: topics_result,
        trending_tags,
    }))
}
