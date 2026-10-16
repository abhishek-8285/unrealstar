package com.unrealstar.live.models

data class BeautyCategory(
    val category_id: Int,
    val name: String,
    val items: List<BeautyItem>
)

data class BeautyItem(
    val id: String,
    val name: String,
    val tag: String,
    val blend_mode: String,
    val default_intensity: Float = 0.6f
)

// Mirrors backend models::FeedPost. Distinct from the stored MomentItem on the
// server because `liked_by_me` and the resolved avatar are per-request.
data class FeedPost(
    val moment_id: String,
    val author_id: Long,
    val author_nickname: String,
    val author_avatar_url: String = "",
    val media_url: String,
    val cover_url: String = "",
    val caption: String,
    val duration_secs: Int = 0,
    val likes_count: Long = 0,
    val comment_count: Long = 0,
    val share_count: Long = 0,
    val gift_count: Long = 0,
    val promo_text: String? = null,
    val tags: List<String> = emptyList(),
    val liked_by_me: Boolean = false,
    val created_at: String = ""
)

/** Cursor-paginated page from GET /feed/moments. */
data class FeedPage(
    val posts: List<FeedPost> = emptyList(),
    val next_cursor: String? = null
)

data class CreateMomentRequest(
    val media_url: String,
    val caption: String,
    val duration_secs: Int = 15,
    val promo_text: String? = null,
    val cover_url: String? = null,
    val tags: List<String> = emptyList()
)

data class MomentComment(
    val comment_id: String,
    val moment_id: String = "",
    /** null for a top-level comment, otherwise the comment being replied to. */
    val parent_id: String? = null,
    val author_id: Long = 0,
    val author_nickname: String = "",
    val body: String = "",
    val likes_count: Long = 0,
    val liked_by_me: Boolean = false,
    val created_at: String = ""
)

data class CommentLikeResponse(
    val comment_id: String,
    val likes_count: Long = 0
)

/** A comment plus how it should be rendered (reply or not). */
data class CommentRow(
    val comment: MomentComment,
    val isReply: Boolean,
    val replyCount: Int = 0
)

data class CommentList(
    val comments: List<MomentComment> = emptyList()
)

/** Tab-filtered, page-paginated room feed from GET /feed/rooms. */
data class RoomPage(
    val rooms: List<FeedRoomItem> = emptyList(),
    val page: Int = 0,
    val total: Int = 0,
    val total_pages: Int = 1
)

data class LikeMomentResponse(
    val moment_id: String,
    val likes_count: Long
)

/** Full engagement snapshot returned by every moment action. */
data class MomentEngagement(
    val moment_id: String,
    val likes_count: Long = 0,
    val comment_count: Long = 0,
    val share_count: Long = 0,
    val gift_count: Long = 0
)

data class WalletBalance(
    val coins: Long,
    val diamonds: Long
)

// Mirrors backend models::FeedRoomItem (backend/crates/api/src/models.rs:374).
// Snake_case on purpose: Gson maps JSON keys straight onto property names,
// which is the convention every other model in this file already follows.
data class FeedRoomItem(
    val room_id: Long,
    val title: String,
    val cover_image_url: String,
    val host_id: Long,
    val host_nickname: String,
    val viewers_count: Long,
    val is_pk: Boolean,
    val category: String,
    // Card chrome for the Live grid. Absent means the card renders without it.
    val rank_tag: String? = null,
    val host_badge: String? = null,
    val pk_opponent: String? = null
)

/** Mirrors backend models::LiveBanner. */
data class LiveBanner(
    val banner_id: String,
    val title: String,
    val subtitle: String,
    val image_url: String,
    val action_url: String
)

data class LiveBannerPage(
    val banners: List<LiveBanner> = emptyList()
)

data class ChatMessage(
    val senderId: Long,
    val nickname: String,
    val message: String,
    val familyBadge: String? = null,
    val vipLevel: Int = 0
)

data class SendGiftRequest(
    val room_id: Long,
    val recipient_id: Long,
    val gift_id: String,
    val count: Int,
    val combo_seq: Int,
    val idempotency_key: String
)

data class SendGiftResponse(
    val success: Boolean,
    val remaining_coins: Long,
    val recipient_diamonds: Long,
    val broadcast_triggered: Boolean
)

data class GiftCatalogItem(
    val gift_id: String = "",
    val name: String = "",
    val price_coins: Long = 0,
    val tier: String = "standard",
    val icon_url: String? = null,
    val animation_url: String? = null,
    val is_broadcast: Boolean = false
)


data class SpinWheelRequest(
    val room_id: Long,
    val wheel_id: String,
    val coins_bet: Long,
    val idempotency_key: String? = null
)

data class SpinWheelResponse(
    val target_sector: Int,
    val target_angle_deg: Float,
    val multiplier: Int,
    val reward_coins: Long,
    val actor_diamonds: Long,
    val remaining_coins: Long,
    val tier: String
)

data class ShovelDigRequest(
    val room_id: Long,
    val shovel_type: String,
    val slot_index: Int,
    val idempotency_key: String
)

data class ShovelDigResponse(
    val prize_name: String,
    val coins_won: Long,
    val diamonds_won: Long,
    val is_jackpot: Boolean,
    val remaining_coins: Long
)

data class MagicLampWishRequest(
    val room_id: Long,
    val wish_tier: String,
    val idempotency_key: String
)

data class MagicLampWishResponse(
    val outcome_text: String,
    val multiplier_achieved: Int,
    val coins_won: Long,
    val remaining_coins: Long
)


/** Mirrors backend models::InboxCategory. */
data class InboxCategory(
    val key: String,
    val label: String,
    val unread: Long = 0
)

data class InboxCategoryPage(
    val categories: List<InboxCategory> = emptyList()
)

/** Mirrors backend models::InboxMessage. */
data class InboxMessage(
    val message_id: String,
    val category: String,
    val sender_nickname: String,
    val sender_avatar_url: String,
    val preview: String,
    val created_at: String,
    val unread: Boolean = false
)

data class InboxMessagePage(
    val messages: List<InboxMessage> = emptyList()
)

/** Mirrors backend models::MeStats. */
data class MeStats(
    val videos_watched: Long = 0,
    val lives_watched: Long = 0,
    val time_spent_minutes: Long = 0,
    val active_days: Long = 0
)

/** Mirrors backend models::MeDashboard. */
data class MeDashboard(
    val user_id: Long = 0,
    val nickname: String = "",
    val avatar_url: String = "",
    val bio: String = "",
    val display_id: String = "",
    val age: Int = 0,
    val gender: String = "",
    val level: Int = 0,
    val vip_level: String = "None",
    val following_count: Long = 0,
    val followers_count: Long = 0,
    val likes_count: Long = 0,
    val diamonds: Double = 0.0,
    val stats: MeStats = MeStats()
)

/** Mirrors backend models::OfficialNotice. */
data class OfficialNotice(
    val notice_id: String,
    val section: String,
    val title: String,
    val body: String,
    val image_url: String = "",
    val created_at: String = ""
)

data class OfficialSection(
    val key: String,
    val label: String,
    val unread: Long = 0
)

data class OfficialNoticePage(
    val sections: List<OfficialSection> = emptyList(),
    val notices: List<OfficialNotice> = emptyList()
)

/** OTP login: POST /auth/otp/send|verify. */
data class OtpSendResponse(
    val otp_session_id: String = "",
    val retry_after_secs: Int = 0,
    val debug_otp: String? = null
)

data class AuthTokensResponse(
    val access_token: String = "",
    val refresh_token: String = "",
    val user_id: Long = 0,
    val is_new_user: Boolean = false
)

/** POST /rooms/{id}/join → viewer admitted to a live room. */
data class JoinRoomResponse(
    val room_id: Long = 0,
    val title: String = "",
    val host_id: Long = 0,
    val host_nickname: String = "",
    val rtc_token: String = "",
    val viewers_count: Long = 0,
    val ws_url: String = ""
)

/** POST /rooms/create → host room for the Go-Live flow. */
data class CreateRoomResponse(
    val room_id: Long = 0,
    val title: String = "",
    val rtc_token: String = "",
    val rtc_channel_id: String = "",
    val ws_url: String = ""
)

/** GET /leaderboards */
data class LeaderboardItem(
    val rank: Long = 0,
    val user_id: Long = 0,
    val nickname: String = "",
    val avatar_url: String = "",
    val score: Long = 0
)

/** GET /tasks/daily */
data class DailyTaskItem(
    val task_id: String = "",
    val title: String = "",
    val progress: Int = 0,
    val target: Int = 0,
    val reward_coins: Long = 0,
    val is_claimed: Boolean = false
)

/** GET /vip/tiers */
data class VipTierItem(
    val tier_id: String = "",
    val name: String = "",
    val price_inr: Int = 0,
    val privileges: List<String> = emptyList()
)

/** GET /kyc/status */
data class KycStatus(
    val status: String = "not_submitted",
    val payer_name: String? = null,
    val rejection_reason: String? = null
)

/** GET /payouts/preview (Trendo Earnings / Cashout) */
data class PayoutPreviewResponse(
    val diamonds_amount: Long = 0,
    val gross_inr: Long = 0,
    val tds_194q_inr: Long = 0,
    val net_payout_inr: Long = 0,
    val exchange_rate_diamonds_per_inr: Int = 100
)

/** GET /family/leaderboard */
data class FamilyItem(
    val family_id: String = "",
    val name: String = "",
    val badge: String = "",
    val level: Int = 1,
    val members_count: Int = 0,
    val leader_name: String = "",
    val exp: Long = 0,
    val rank: Int = 0
)

/** GET /family/my */
data class MyFamilyResponse(
    val family: FamilyItem? = null,
    val my_role: String = "none",
    val contribution_score: Long = 0
)

/** PK Battle status (Trendo Live PK) */
data class PkStatusResponse(
    val pk_id: String = "",
    val host_a_id: Long = 0,
    val host_b_id: Long = 0,
    val host_a_points: Long = 0,
    val host_b_points: Long = 0,
    val time_remaining_secs: Int = 0,
    val status: String = "matching"
)

data class SocialFollowResponse(
    val is_following: Boolean = false
)

/** GET /api/v1/search (Trendo SearchActivity) */
data class SearchUserItem(
    val user_id: Long = 0,
    val nickname: String = "",
    val avatar_url: String = "",
    val bio: String = "",
    val level: Int = 1,
    val followers_count: Long = 0,
    val is_following: Boolean = false,
    val is_live: Boolean = false,
    val room_id: Long? = null
)

data class SearchTopicItem(
    val tag: String = "",
    val posts_count: Long = 0
)

data class SearchResponse(
    val query: String = "",
    val users: List<SearchUserItem> = emptyList(),
    val rooms: List<FeedRoomItem> = emptyList(),
    val topics: List<SearchTopicItem> = emptyList(),
    val trending_tags: List<String> = emptyList()
)

/** GET /api/v1/users/me/bag (Trendo MyBagActivity & StoreBagActivity) */
data class CosmeticItem(
    val item_id: String = "",
    val name: String = "",
    val category: String = "avatar_frame",
    val icon_url: String = "",
    val preview_asset: String = "",
    val description: String = "",
    var is_equipped: Boolean = false,
    val is_owned: Boolean = false,
    val price_coins: Long = 0,
    val expires_in_days: Int? = null
)

data class UserBagResponse(
    val user_id: Long = 0,
    val equipped_frame: String? = null,
    val equipped_ride: String? = null,
    val equipped_bubble: String? = null,
    val items: List<CosmeticItem> = emptyList()
)

/** GET /api/v1/users/me/level & /badges (Trendo MyLevelActivity & BadgeWallActivity) */
data class LevelPrivilegeItem(
    val level: Int = 1,
    val title: String = "",
    val icon: String = "",
    val description: String = "",
    val is_unlocked: Boolean = false
)

data class BadgeItem(
    val badge_id: String = "",
    val name: String = "",
    val icon: String = "",
    val description: String = "",
    val is_unlocked: Boolean = false,
    val unlocked_at: String? = null
)

data class UserLevelResponse(
    val user_id: Long = 0,
    val current_level: Int = 1,
    val current_exp: Long = 0,
    val next_level_exp: Long = 1000,
    val progress_pct: Int = 0,
    val wealth_title: String = "Bronze Citizen",
    val privileges: List<LevelPrivilegeItem> = emptyList(),
    val badges: List<BadgeItem> = emptyList()
)

data class UserBadgesResponse(
    val user_id: Long = 0,
    val total_badges: Int = 0,
    val unlocked_count: Int = 0,
    val badges: List<BadgeItem> = emptyList()
)



