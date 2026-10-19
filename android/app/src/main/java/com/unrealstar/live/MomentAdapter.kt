package com.unrealstar.live

import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.graphics.Color
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.toColorInt
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.unrealstar.live.databinding.ItemMomentBinding
import com.unrealstar.live.models.FeedPost
import com.unrealstar.live.models.MomentEngagement
import java.util.Locale

enum class MomentAction { LIKE, UNLIKE, COMMENT, SHARE, GIFT, FOLLOW }

/**
 * One moment per screen. The ExoPlayer instance is owned by HomeFragment and
 * handed to whichever ViewHolder is currently snapped, so only one surface ever
 * decodes.
 */
class MomentAdapter : RecyclerView.Adapter<MomentAdapter.MomentViewHolder>() {

    private val items = mutableListOf<FeedPost>()

    var player: ExoPlayer? = null
    var onAction: ((FeedPost, MomentAction) -> Unit)? = null
    var onOpenComments: ((FeedPost) -> Unit)? = null
    var onOpenGift: ((FeedPost) -> Unit)? = null
    var onOpenProfile: ((FeedPost) -> Unit)? = null
    var onBannerClick: (() -> Unit)? = null

    /**
     * Distance from the top of the feed to the bottom of the fixed header.
     * The promotional overlay is inset by this so it never collides with the
     * header, which the video deliberately runs underneath.
     */
    var bannerTopOffsetPx: Int = 0

    private var activePosition = RecyclerView.NO_POSITION
    private var activeHolder: MomentViewHolder? = null

    /**
     * Comment/share/gift the viewer has performed this session. The server only
     * reports like state (`liked_by_me`), so the rest is tracked locally and is
     * deliberately not persisted.
     */
    private val acted = HashSet<String>()

    fun markActed(momentId: String, action: MomentAction) {
        val key = "$momentId:${action.name}"
        if (!acted.add(key)) return
        val index = items.indexOfFirst { it.moment_id == momentId }
        if (index != -1) notifyItemChanged(index)
    }

    /**
     * Following is per author, not per moment: every visible post by that
     * author loses its "+" badge, exactly like the server does on reload.
     */
    fun markAuthorFollowed(authorId: Long) {
        if (!acted.add("author:$authorId:FOLLOW")) return
        items.forEachIndexed { index, post ->
            if (post.author_id == authorId) notifyItemChanged(index)
        }
    }

    /** Muted is a player-wide setting, not a per-moment one. */
    var isMuted: Boolean = false
        set(value) {
            field = value
            player?.volume = if (value) 0f else 1f
            activeHolder?.syncMuteIcon()
        }

    fun submit(next: List<FeedPost>) {
        val oldSize = items.size
        acted.clear()
        items.clear()
        items.addAll(next)
        activePosition = RecyclerView.NO_POSITION
        activeHolder = null
        notifyListReplaced(oldSize, items.size)
    }

    /** Appends the next page without disturbing what is already on screen. */
    fun append(next: List<FeedPost>) {
        if (next.isEmpty()) return
        val start = items.size
        items.addAll(next)
        notifyItemRangeInserted(start, next.size)
    }

    /** Called by the pager whenever the snapped page changes. */
    fun setActive(position: Int) {
        if (position == activePosition) return
        val previous = activePosition
        activePosition = position
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
        if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
    }

    /** Reconciles every rail counter from one server response. */
    fun applyEngagement(engagement: MomentEngagement, likedByMe: Boolean? = null) {
        val index = items.indexOfFirst { it.moment_id == engagement.moment_id }
        if (index == -1) return
        val current = items[index]
        items[index] = current.copy(
            likes_count = engagement.likes_count,
            comment_count = engagement.comment_count,
            share_count = engagement.share_count,
            gift_count = engagement.gift_count,
            liked_by_me = likedByMe ?: current.liked_by_me
        )
        notifyItemChanged(index)
    }

    /** Refresh just the comment counter, e.g. after posting from the sheet. */
    fun updateCommentCount(momentId: String, count: Long) {
        val index = items.indexOfFirst { it.moment_id == momentId }
        if (index == -1) return
        items[index] = items[index].copy(comment_count = count)
        notifyItemChanged(index)
    }

    /** Called on a timer by the fragment to drive the scrubber. */
    fun updateProgress() {
        val holder = activeHolder ?: return
        val p = player ?: return
        val duration = p.duration
        holder.binding.pbProgress.progress = if (duration <= 0L) {
            0
        } else {
            ((p.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MomentViewHolder {
        val binding = ItemMomentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MomentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MomentViewHolder, position: Int) {
        val isActive = position == activePosition
        holder.bind(items[position], isActive)
        if (isActive) activeHolder = holder else if (activeHolder === holder) activeHolder = null
    }

    override fun onViewRecycled(holder: MomentViewHolder) {
        super.onViewRecycled(holder)
        if (activeHolder === holder) activeHolder = null
        holder.releasePlayer()
    }

    override fun getItemCount() = items.size

    inner class MomentViewHolder(
        val binding: ItemMomentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var bound: FeedPost? = null

        fun bind(moment: FeedPost, isActive: Boolean) {
            bound = moment
            binding.banner.updatePadding(top = bannerTopOffsetPx)

            binding.tvAuthor.text = moment.author_nickname
            binding.tvCaption.text = highlightTags(moment.caption, moment.tags)
            binding.tvSuggestedName.text = moment.author_nickname
            binding.tvTimer.text = binding.root.context.getString(R.string.moment_duration_secs, moment.duration_secs)

            syncCounts(moment)
            syncLiked(moment)
            syncActionStates(moment)
            syncMuteIcon()
            loadArtwork(moment)

            val promo = moment.promo_text
            if (promo.isNullOrBlank()) {
                binding.banner.visibility = View.GONE
            } else {
                binding.banner.visibility = View.VISIBLE
                binding.tvBannerText.text = promo
            }

            binding.tvBannerText.setOnClickListener { onBannerClick?.invoke() }
            binding.tvPaidBadge.setOnClickListener { onBannerClick?.invoke() }
            binding.tvBannerPill.setOnClickListener { onBannerClick?.invoke() }
            binding.ivBannerClose.setOnClickListener { binding.banner.visibility = View.GONE }

            val ctx = binding.root.context
            binding.btnComment.contentDescription =
                ctx.getString(R.string.comments_with_count, compactCount(moment.comment_count))
            binding.btnShare.contentDescription =
                ctx.getString(R.string.share_with_count, compactCount(moment.share_count))
            binding.btnGift.contentDescription =
                ctx.getString(R.string.gift_with_count, compactCount(moment.gift_count))

            binding.btnLike.setOnClickListener {
                pop(it)
                onAction?.invoke(
                    moment,
                    if (moment.liked_by_me) MomentAction.UNLIKE else MomentAction.LIKE
                )
            }
            // The comment rail opens the thread rather than posting blind.
            binding.btnComment.setOnClickListener {
                pop(it)
                onOpenComments?.invoke(moment)
            }
            binding.btnShare.setOnClickListener {
                pop(it)
                onAction?.invoke(moment, MomentAction.SHARE)
            }
            binding.btnGift.setOnClickListener {
                pop(it)
                if (onOpenGift != null) {
                    onOpenGift?.invoke(moment)
                } else {
                    onAction?.invoke(moment, MomentAction.GIFT)
                }
            }
            binding.btnMute.setOnClickListener {
                isMuted = !isMuted
                syncMuteIcon()
            }

            val followed = moment.author_is_following ||
                acted.contains("author:${moment.author_id}:FOLLOW")
            binding.ivFollowBadge.visibility = if (followed) View.GONE else View.VISIBLE
            // The caption row repeats the same Follow affordance; leaving it
            // visible after following is exactly the not-synced look.
            binding.suggestedRow.visibility = if (followed) View.GONE else View.VISIBLE
            val followClick = View.OnClickListener {
                pop(binding.ivFollowBadge)
                onAction?.invoke(moment, MomentAction.FOLLOW)
            }
            binding.ivFollowBadge.setOnClickListener(followClick)
            binding.ivCreatorAvatar.setOnClickListener(followClick)

            val profileClick = View.OnClickListener {
                onOpenProfile?.invoke(moment)
            }
            binding.ivAuthorAvatar.setOnClickListener(profileClick)
            binding.tvAuthor.setOnClickListener(profileClick)
            binding.tvSuggestedName.setOnClickListener(profileClick)
            binding.suggestedRow.setOnClickListener(followClick)


            attachTapGestures()


            if (isActive && moment.media_url.isNotBlank()) {
                attachPlayer(moment.media_url)
            } else {
                releasePlayer()
            }
        }

        /** cover_url and author_avatar_url, both supplied by the feed API. */
        private fun loadArtwork(moment: FeedPost) {
            if (moment.cover_url.isNotBlank()) {
                Glide.with(binding.ivCover).load(moment.cover_url).into(binding.ivCover)
            } else {
                Glide.with(binding.ivCover).clear(binding.ivCover)
            }

            if (moment.author_avatar_url.isNotBlank()) {
                Glide.with(binding.ivCreatorAvatar)
                    .load(moment.author_avatar_url)
                    .placeholder(R.drawable.bg_feed_avatar_ring)
                    .circleCrop()
                    .into(binding.ivCreatorAvatar)
                Glide.with(binding.ivAuthorAvatar)
                    .load(moment.author_avatar_url)
                    .placeholder(R.drawable.bg_feed_avatar_ring)
                    .circleCrop()
                    .into(binding.ivAuthorAvatar)
            } else {
                Glide.with(binding.ivCreatorAvatar).clear(binding.ivCreatorAvatar)
                Glide.with(binding.ivAuthorAvatar).clear(binding.ivAuthorAvatar)
            }
        }

        private fun syncCounts(moment: FeedPost) {
            binding.tvLikeCount.text = compactCount(moment.likes_count)
            binding.tvSmallLikeCount.text = compactCount(moment.likes_count)
            binding.tvSmallGiftCount.text = compactCount(moment.gift_count)
            binding.tvGiftCount.text = compactCount(moment.gift_count)
            binding.tvCommentCount.text = compactCount(moment.comment_count)
            binding.tvShareCount.text = compactCount(moment.share_count)
        }

        /** Applies the activated fill/tint for one rail action. */
        private fun tintAction(view: ImageView, active: Boolean, activeColor: Int) {
            view.imageTintList = ColorStateList.valueOf(
                if (active) activeColor else Color.WHITE
            )
            view.alpha = if (active) 1f else 0.92f
        }

        /** Tactile pop so a tap is acknowledged even if the count is unchanged. */
        private fun pop(view: View) {
            view.animate().cancel()
            view.scaleX = 0.82f
            view.scaleY = 0.82f
            view.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }

        private fun syncLiked(moment: FeedPost) {
            val ctx = binding.root.context
            val likeColor = if (moment.liked_by_me) primary else Color.WHITE
            binding.btnLike.setImageResource(
                if (moment.liked_by_me) R.drawable.ic_feed_heart_filled
                else R.drawable.ic_feed_heart
            )
            binding.btnLike.imageTintList = ColorStateList.valueOf(likeColor)
            binding.btnLike.contentDescription = ctx.getString(
                if (moment.liked_by_me) R.string.unlike
                else R.string.like_with_count,
                *arrayOf<Any>(compactCount(moment.likes_count))
            )

            binding.ivSmallHeart.setImageResource(
                if (moment.liked_by_me) R.drawable.ic_feed_heart_filled
                else R.drawable.ic_feed_heart
            )
            binding.ivSmallHeart.imageTintList = ColorStateList.valueOf(likeColor)
        }

        private fun syncActionStates(moment: FeedPost) {
            val id = moment.moment_id
            tintAction(binding.btnComment, acted.contains("$id:COMMENT"), primary)
            tintAction(binding.btnShare, acted.contains("$id:SHARE"), primary)
            tintAction(binding.btnGift, acted.contains("$id:GIFT"), gold)
        }

        fun syncMuteIcon() {
            binding.btnMute.setImageResource(
                if (isMuted) R.drawable.ic_feed_volume_off else R.drawable.ic_feed_volume_on
            )
            binding.btnMute.contentDescription = binding.root.context.getString(
                if (isMuted) R.string.unmute else R.string.mute
            )
        }

        private fun attachTapGestures() {
            val detector = GestureDetector(
                binding.root.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        togglePlayback()
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        val moment = bound
                        if (moment != null && !moment.liked_by_me) {
                            onAction?.invoke(moment, MomentAction.LIKE)
                        }
                        showHeartBurst()
                        return true
                    }
                }
            )
            binding.tapSurface.setOnTouchListener { view, event ->
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) view.performClick()
                true
            }
        }

        private fun togglePlayback() {
            val p = player ?: return
            if (p.isPlaying) {
                p.playWhenReady = false
                showPlayOverlay(true)
            } else {
                p.playWhenReady = true
                showPlayOverlay(false)
            }
        }

        private fun showPlayOverlay(visible: Boolean) {
            val overlay = binding.ivPlayOverlay
            overlay.animate().cancel()
            if (visible) {
                overlay.alpha = 0.85f
                overlay.visibility = View.VISIBLE
            } else {
                overlay.animate().alpha(0.85f).setDuration(80).withEndAction {
                    overlay.animate().alpha(0f).setDuration(320).withEndAction {
                        overlay.visibility = View.GONE
                    }.start()
                }.start()
            }
        }

        private fun showHeartBurst() {
            val view = binding.ivHeartBurst
            view.animate().cancel()
            view.scaleX = 0.3f
            view.scaleY = 0.3f
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(220)
                .withEndAction {
                    view.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setDuration(320)
                        .withEndAction { view.visibility = View.GONE }
                        .start()
                }.start()
        }

        private fun attachPlayer(url: String) {
            val active = player ?: return
            binding.playerView.player = active
            if (active.currentMediaItem?.localConfiguration?.uri?.toString() != url) {
                active.setMediaItem(MediaItem.fromUri(url))
                active.prepare()
            }
            active.playWhenReady = true
        }

        fun releasePlayer() {
            // Detaching the surface does NOT stop playback, so without the
            // pause below the previous clip keeps decoding and its audio runs
            // on under a still frame. Guarded on actually owning the player so
            // a recycled holder can never pause the newly active one.
            val owned = binding.playerView.player != null
            binding.playerView.player = null
            if (owned) player?.playWhenReady = false
        }
    }

    companion object {
        private val primary = "#FF0055".toColorInt()
        private val gold = "#FFD700".toColorInt()
        private val tagColor = "#25A8F0".toColorInt()

        /**
         * Colours `#hashtag` runs inside the caption, the way Instagram and
         * YouTube do. Falls back to detecting `#word` directly when the server
         * sent no explicit tag list, so hand-written captions still highlight.
         */
        fun highlightTags(caption: String, tags: List<String>): CharSequence {
            val needles = if (tags.isNotEmpty()) {
                tags.map { "#$it" }
            } else {
                HASHTAG.findAll(caption).map { it.value }.toList()
            }
            if (needles.isEmpty()) return caption

            val spanned = SpannableString(caption)
            needles.forEach { needle ->
                var from = 0
                while (true) {
                    val at = caption.indexOf(needle, from, ignoreCase = true)
                    if (at == -1) break
                    spanned.setSpan(
                        ForegroundColorSpan(tagColor), at, at + needle.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    from = at + needle.length
                }
            }
            return spanned
        }

        private val HASHTAG = Regex("#\\w+")

        /** Matches the reference, where the rail shows values like "1.6k". */
        private fun compactCount(value: Long): String = when {
            // Locale.US so an Arabic/Hindi locale cannot substitute
            // Arabic-Indic or Devanagari digits into the rail.
            value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
            value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
            else -> value.toString()
        }
    }
}