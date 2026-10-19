package com.unrealstar.live

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.FragmentInboxBinding
import com.unrealstar.live.databinding.ItemConversationBinding
import com.unrealstar.live.databinding.ItemInboxCategoryBinding
import com.unrealstar.live.databinding.ItemInboxFollowRequestsBinding
import com.unrealstar.live.databinding.ItemInboxMessageBinding
import com.unrealstar.live.databinding.ItemInboxOfficialNoticeBinding
import com.unrealstar.live.databinding.ItemInboxOfficialRowBinding
import com.unrealstar.live.databinding.ItemInboxSectionHeaderBinding
import com.unrealstar.live.databinding.ItemInboxSeeAllBinding
import com.unrealstar.live.databinding.ItemInboxSuggestionBinding
import com.unrealstar.live.models.ConversationSummary
import com.unrealstar.live.models.InboxCategory
import com.unrealstar.live.models.InboxMessage
import com.unrealstar.live.models.OfficialActor
import com.unrealstar.live.models.OfficialFollowRequests
import com.unrealstar.live.models.OfficialNoticePage
import com.unrealstar.live.models.OfficialRow
import com.unrealstar.live.models.OfficialSection
import com.unrealstar.live.models.OfficialSuggestion
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Inbox with two top-level views:
 *  - Official: system notices under Officially / Event / Picked / Task.
 *  - Information: the message inbox under the five category tiles. With no
 *    tile selected the list is the conversation list; a selected tile filters
 *    the notification rows for that category.
 */
class InboxFragment : Fragment() {

    private var _binding: FragmentInboxBinding? = null
    private val binding get() = _binding!!

    private val categoryBinder = CategoryAdapter { selectCategory(it) }
    private val messageAdapter = MessageAdapter { msg ->
        // A notification row carries its actor's real peer id, so tapping it
        // opens that person's thread — never a fallback user.
        if (msg.sender_id > 0) {
            DirectMessageBottomSheet(
                targetUserId = msg.sender_id,
                targetNickname = msg.sender_nickname,
                targetAvatarUrl = msg.sender_avatar_url,
                onConversationChanged = { refreshConversations() }
            ).show(childFragmentManager, DirectMessageBottomSheet.TAG)
        }
    }
    private val conversationAdapter = ConversationAdapter { conversation ->
        openConversation(conversation)
    }
    private val officialAdapter = OfficialFeedAdapter(object : OfficialFeedAdapter.Callbacks {
        override fun onOpenPerson(actor: OfficialActor) {
            openPerson(actor)
        }

        override fun onOpenNotice(row: OfficialRow) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage("${row.body}\n\n${officialDate(row.created_at)}")
                .setPositiveButton("OK", null)
                .show()
        }

        override fun onOpenThumbnail(row: OfficialRow) {
            val url = UnrealStarApiClient.localise(row.trailing.image_url) ?: return
            val image = ImageView(requireContext()).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (220 * resources.displayMetrics.density).toInt(),
                )
            }
            Glide.with(this@InboxFragment).load(url).into(image)
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setView(image)
                .setPositiveButton("OK", null)
                .show()
        }

        override fun onFollow(peerId: Long, follow: Boolean) {
            toggleFollowFromFeed(peerId, follow)
        }

        override fun onConfirm(peerId: Long) {
            resolveFollowRequest(peerId, confirm = true)
        }

        override fun onDelete(peerId: Long) {
            resolveFollowRequest(peerId, confirm = false)
        }

        override fun onOpenRequests(summary: OfficialFollowRequests) {
            FollowRequestsBottomSheet(summary.pending) { refreshOfficial() }
                .show(childFragmentManager, FollowRequestsBottomSheet.TAG)
        }

        override fun onSeeAll() {
            SearchBottomSheet().show(childFragmentManager, SearchBottomSheet.TAG)
        }
    })

    private var showingOfficial = true
    private var selectedCategory: String? = null
    private var officialSection: String? = null
    private var officialHasData = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.inboxHeader.applyTopSystemInset()

        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = messageAdapter
        binding.rvOfficial.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOfficial.adapter = officialAdapter

        binding.tabOfficial.setOnClickListener { setTab(official = true) }
        binding.tabInformation.setOnClickListener { setTab(official = false) }
        binding.btnPin.setOnClickListener {
            messageAdapter.markAllRead()
            // Unread badges live server-side, so the pin clears them there too.
            viewLifecycleOwner.lifecycleScope.launch {
                conversationAdapter.unreadPeerIds().forEach { peerId ->
                    UnrealStarApiClient.markConversationRead(peerId).onSuccess { page ->
                        conversationAdapter.submit(page.conversations)
                    }
                }
                // Optimistic, like messageAdapter.markAllRead() above.
                (activity as? MainActivity)?.updateInboxBadge(0)
            }
            Toast.makeText(requireContext(), getString(R.string.inbox_marked_read), Toast.LENGTH_SHORT).show()
        }
        binding.btnInboxSettings.setOnClickListener {
            SettingsBottomSheet().show(childFragmentManager, SettingsBottomSheet.TAG)
        }
        binding.btnOfficialRetry.setOnClickListener { loadOfficial() }

        // Pull-to-refresh reloads whichever Information list is showing.
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            if (selectedCategory == null) refreshConversations() else loadMessages()
        }

        // The Official feed pulls the same way as the messages list.
        binding.swipeOfficial.setColorSchemeResources(R.color.primary)
        binding.swipeOfficial.setOnRefreshListener { refreshOfficial() }

        loadCategories()
        setTab(official = true)
    }


    /** Swaps between the Official notice feed and the Information thread list. */
    private fun setTab(official: Boolean) {
        showingOfficial = official
        val current = _binding ?: return

        val active = ContextCompat.getColor(requireContext(), R.color.text_white)
        val idle = ContextCompat.getColor(requireContext(), R.color.text_muted)
        current.tabOfficial.setTextColor(if (official) active else idle)
        current.tabInformation.setTextColor(if (official) idle else active)
        current.tabOfficial.setTypeface(null, if (official) Typeface.BOLD else Typeface.NORMAL)
        current.tabInformation.setTypeface(null, if (official) Typeface.NORMAL else Typeface.BOLD)

        current.officialTabs.isVisible = official
        current.categoryScroll.isVisible = !official
        current.categoryDivider.isVisible = !official
        current.swipeOfficial.isVisible = official && officialHasData
        current.rvMessages.isVisible = !official
        if (!official) {
            current.officialLoading.isVisible = false
            current.officialError.isVisible = false
        }

        if (official) {
            showEmptyMessage(null)
            loadOfficial()
        } else {
            showInformationList()
        }
    }

    // ---- Official ----------------------------------------------------------

    private fun loadOfficial() {
        val current = _binding ?: return
        current.swipeOfficial.isVisible = false
        current.officialError.isVisible = false
        current.officialLoading.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = UnrealStarApiClient.fetchOfficialNotices(officialSection)
            val b = _binding ?: return@launch
            b.officialLoading.isVisible = false
            result.fold(
                onSuccess = { page ->
                    officialHasData = page.groups.isNotEmpty()
                    officialAdapter.submit(page)
                    buildOfficialTabs(page.sections)
                    if (showingOfficial) {
                        b.swipeOfficial.isVisible = officialHasData
                        b.tvOfficialError.text = getString(R.string.inbox_empty)
                        b.btnOfficialRetry.isVisible = false
                        b.officialError.isVisible = !officialHasData
                    }
                },
                onFailure = {
                    officialHasData = false
                    if (showingOfficial) {
                        b.swipeOfficial.isVisible = false
                        b.tvOfficialError.text = getString(R.string.inbox_load_failed)
                        b.btnOfficialRetry.isVisible = true
                        b.officialError.isVisible = true
                    }
                },
            )
        }
    }

    /** Tapping an actor opens that person's thread — never a fallback user. */
    private fun openPerson(actor: OfficialActor) {
        DirectMessageBottomSheet(
            targetUserId = actor.user_id,
            targetNickname = actor.nickname,
            targetAvatarUrl = actor.avatar_url,
            onConversationChanged = { refreshConversations() }
        ).show(childFragmentManager, DirectMessageBottomSheet.TAG)
    }

    /** Optimistic pill flip; the server call reverts it on failure. */
    private fun toggleFollowFromFeed(peerId: Long, follow: Boolean) {
        officialAdapter.applyFollow(peerId, follow)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (follow) UnrealStarApiClient.followUser(peerId)
            else UnrealStarApiClient.unfollowUser(peerId)
            result.onFailure {
                officialAdapter.applyFollow(peerId, !follow)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.inbox_follow_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Confirm/Delete return the refreshed feed in one round-trip. */
    private fun resolveFollowRequest(peerId: Long, confirm: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (confirm) UnrealStarApiClient.confirmFollowRequest(peerId)
            else UnrealStarApiClient.deleteFollowRequest(peerId)
            val b = _binding ?: return@launch
            result.fold(
                onSuccess = { page ->
                    officialHasData = page.groups.isNotEmpty()
                    officialAdapter.submit(page)
                    buildOfficialTabs(page.sections)
                    b.swipeOfficial.isVisible = showingOfficial && officialHasData
                },
                onFailure = {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.inbox_follow_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                },
            )
        }
    }

    /** Silent refresh, so a sheet resolution never blanks the list behind it. */
    private fun refreshOfficial() {
        val current = _binding ?: return
        current.swipeOfficial.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = UnrealStarApiClient.fetchOfficialNotices(officialSection)
            val b = _binding ?: return@launch
            b.swipeOfficial.isRefreshing = false
            result.onSuccess { page ->
                officialHasData = page.groups.isNotEmpty()
                officialAdapter.submit(page)
                buildOfficialTabs(page.sections)
                if (showingOfficial) b.swipeOfficial.isVisible = officialHasData
            }
        }
    }

    private fun buildOfficialTabs(sections: List<OfficialSection>) {
        val current = _binding ?: return
        current.officialTabs.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()

        sections.forEach { section ->
            val column = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setPadding(pad, 4, pad, 6)
            }
            val isActive = section.key == officialSection

            val label = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = section.label
                maxLines = 1
                textSize = 11f
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (isActive) R.color.text_white else R.color.text_muted
                    )
                )
                setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            }
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (5 * resources.displayMetrics.density).toInt(),
                    (5 * resources.displayMetrics.density).toInt()
                ).apply { topMargin = (3 * resources.displayMetrics.density).toInt() }
                setBackgroundResource(R.drawable.bg_inbox_red_dot)
                // Reserve the slot so labels stay aligned whether or not unread.
                visibility = if (section.unread > 0) View.VISIBLE else View.INVISIBLE
            }
            val indicator = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (22 * resources.displayMetrics.density).toInt(),
                    (2 * resources.displayMetrics.density).toInt()
                ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
                setBackgroundResource(
                    if (isActive) R.drawable.bg_inbox_subtab_indicator
                    else android.R.color.transparent
                )
            }

            column.addView(label)
            column.addView(dot)
            column.addView(indicator)
            column.setOnClickListener {
                officialSection = if (officialSection == section.key) null else section.key
                loadOfficial()
            }
            current.officialTabs.addView(
                column,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // ---- Information -------------------------------------------------------

    private fun loadCategories() {
        viewLifecycleOwner.lifecycleScope.launch {
            val page = UnrealStarApiClient.fetchInboxCategories()
            val current = _binding ?: return@launch
            current.categoryRow.removeAllViews()
            page.categories.forEach { category ->
                val tile = ItemInboxCategoryBinding.inflate(
                    LayoutInflater.from(requireContext()), current.categoryRow, false
                )
                categoryBinder.bind(tile, category, category.key == selectedCategory)
                current.categoryRow.addView(tile.root)
            }
        }
    }

    /** Tapping the active tile clears the filter and returns to the message inbox. */
    private fun selectCategory(key: String) {
        selectedCategory = if (selectedCategory == key) null else key
        loadCategories()
        showInformationList()
    }

    /**
     * Information tab content: with no tile selected the list is the message
     * inbox (conversations); a selected tile filters the notification rows.
     */
    private fun showInformationList() {
        val current = _binding ?: return
        showEmptyMessage(null)
        if (selectedCategory == null) {
            current.rvMessages.adapter = conversationAdapter
            refreshConversations()
        } else {
            current.rvMessages.adapter = messageAdapter
            loadMessages()
        }
    }

    /**
     * Reloads the message inbox. Identity, read state and previews all live on
     * the server, so every open/send round-trips and the list always matches
     * the thread that was just shown.
     */
    private fun refreshConversations() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = UnrealStarApiClient.fetchConversations()
            val current = _binding ?: return@launch
            current.swipeRefresh.isRefreshing = false
            result.fold(
                onSuccess = { page ->
                    conversationAdapter.submit(page.conversations)
                    (activity as? MainActivity)?.updateInboxBadge(
                        page.conversations.sumOf { it.unread_count }
                    )
                    if (current.rvMessages.adapter === conversationAdapter) {
                        showEmptyMessage(
                            if (page.conversations.isEmpty()) {
                                getString(R.string.inbox_messages_empty)
                            } else {
                                null
                            }
                        )
                    }
                },
                onFailure = {
                    if (current.rvMessages.adapter === conversationAdapter &&
                        conversationAdapter.itemCount == 0
                    ) {
                        showEmptyMessage(getString(R.string.inbox_messages_failed)) {
                            refreshConversations()
                        }
                    }
                },
            )
        }
    }

    /** Opening a row is reading it: clear the badge, then open that person's thread. */
    private fun openConversation(conversation: ConversationSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            UnrealStarApiClient.markConversationRead(conversation.peer_id).onSuccess { page ->
                conversationAdapter.submit(page.conversations)
                (activity as? MainActivity)?.updateInboxBadge(
                    page.conversations.sumOf { it.unread_count }
                )
            }
        }
        DirectMessageBottomSheet(
            targetUserId = conversation.peer_id,
            targetNickname = conversation.peer_nickname,
            targetAvatarUrl = conversation.peer_avatar_url,
            onConversationChanged = { refreshConversations() }
        ).show(childFragmentManager, DirectMessageBottomSheet.TAG)
    }

    private fun loadMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = UnrealStarApiClient.fetchInbox(selectedCategory)
            val current = _binding ?: return@launch
            current.swipeRefresh.isRefreshing = false
            result.fold(
                onSuccess = { page ->
                    messageAdapter.submit(page.messages)
                    if (current.rvMessages.adapter === messageAdapter) {
                        showEmptyMessage(
                            if (page.messages.isEmpty()) getString(R.string.inbox_empty) else null
                        )
                    }
                },
                onFailure = {
                    if (current.rvMessages.adapter === messageAdapter) {
                        showEmptyMessage(getString(R.string.inbox_messages_failed)) { loadMessages() }
                    }
                },
            )
        }
    }

    /** Single owner of the empty/error slot under the Information list. */
    private fun showEmptyMessage(text: String?, retry: (() -> Unit)? = null) {
        val view = _binding?.tvEmpty ?: return
        view.text = text
        view.isVisible = text != null
        view.isClickable = retry != null
        view.setOnClickListener { retry?.invoke() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvMessages.adapter = null
        binding.rvOfficial.adapter = null
        _binding = null
    }
}

/** One entry in the Official feed's RecyclerView. */
private sealed interface FeedItem {
    data class Requests(val summary: OfficialFollowRequests) : FeedItem
    data class Bucket(val label: String) : FeedItem
    data class Row(val row: OfficialRow) : FeedItem
    data class SuggestionsHeader(val label: String) : FeedItem
    data class Suggestion(val user: OfficialSuggestion) : FeedItem
    data class SeeAll(val label: String) : FeedItem
}

private const val TYPE_REQUESTS = 0
private const val TYPE_BUCKET = 1
private const val TYPE_NOTICE = 2
private const val TYPE_ROW = 3
private const val TYPE_SUGGESTED_HEADER = 4
private const val TYPE_SUGGESTION = 5
private const val TYPE_SEE_ALL = 6

/**
 * The Official tab's reference anatomy: the "Follow requests" summary row,
 * time buckets ("This week" / "This month" / "Earlier") holding notice and
 * activity rows with their trailing slot (media thumbnail / Follow pill /
 * Confirm + Delete), then "Suggested for you" and "See all suggestions".
 */
private class OfficialFeedAdapter(
    private val callbacks: Callbacks
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        fun onOpenPerson(actor: OfficialActor)
        fun onOpenNotice(row: OfficialRow)
        fun onOpenThumbnail(row: OfficialRow)
        fun onFollow(peerId: Long, follow: Boolean)
        fun onConfirm(peerId: Long)
        fun onDelete(peerId: Long)
        fun onOpenRequests(summary: OfficialFollowRequests)
        fun onSeeAll()
    }

    private val items = mutableListOf<FeedItem>()

    fun submit(page: OfficialNoticePage) {
        val oldSize = items.size
        items.clear()
        if (page.follow_requests.pending.isNotEmpty()) {
            items.add(FeedItem.Requests(page.follow_requests))
        }
        page.groups.forEach { group ->
            if (group.rows.isEmpty()) return@forEach
            items.add(FeedItem.Bucket(group.label))
            group.rows.forEach { items.add(FeedItem.Row(it)) }
        }
        if (page.suggested.users.isNotEmpty()) {
            items.add(FeedItem.SuggestionsHeader(page.suggested.label))
            page.suggested.users.forEach { items.add(FeedItem.Suggestion(it)) }
            items.add(FeedItem.SeeAll(page.suggested.see_all_label))
        }
        notifyListReplaced(oldSize, items.size)
    }

    /**
     * Flips every pill for [peerId] in place and repaints just those rows, so
     * a follow tap never re-submits the list mid-gesture.
     */
    fun applyFollow(peerId: Long, following: Boolean) {
        items.forEachIndexed { index, item ->
            when (item) {
                is FeedItem.Row -> if (item.row.trailing.kind == "follow" &&
                    item.row.trailing.user_id == peerId
                ) {
                    items[index] = FeedItem.Row(
                        item.row.copy(trailing = item.row.trailing.copy(is_following = following))
                    )
                    notifyItemChanged(index)
                }
                is FeedItem.Suggestion -> if (item.user.user_id == peerId) {
                    items[index] = FeedItem.Suggestion(item.user.copy(is_following = following))
                    notifyItemChanged(index)
                }
                else -> Unit
            }
        }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        return when (item) {
            is FeedItem.Requests -> TYPE_REQUESTS
            is FeedItem.Bucket -> TYPE_BUCKET
            is FeedItem.Row -> if (item.row.kind == "notice") TYPE_NOTICE else TYPE_ROW
            is FeedItem.SuggestionsHeader -> TYPE_SUGGESTED_HEADER
            is FeedItem.Suggestion -> TYPE_SUGGESTION
            is FeedItem.SeeAll -> TYPE_SEE_ALL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_REQUESTS -> RequestsHolder(
                ItemInboxFollowRequestsBinding.inflate(inflater, parent, false), callbacks
            )
            TYPE_BUCKET, TYPE_SUGGESTED_HEADER -> BucketHolder(
                ItemInboxSectionHeaderBinding.inflate(inflater, parent, false)
            )
            TYPE_NOTICE -> NoticeHolder(
                ItemInboxOfficialNoticeBinding.inflate(inflater, parent, false), callbacks
            )
            TYPE_ROW -> RowHolder(
                ItemInboxOfficialRowBinding.inflate(inflater, parent, false), callbacks
            )
            TYPE_SUGGESTION -> SuggestionHolder(
                ItemInboxSuggestionBinding.inflate(inflater, parent, false), callbacks
            )
            else -> SeeAllHolder(
                ItemInboxSeeAllBinding.inflate(inflater, parent, false), callbacks
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FeedItem.Requests -> (holder as RequestsHolder).bind(item.summary)
            is FeedItem.Bucket -> (holder as BucketHolder).bind(item.label)
            is FeedItem.Row -> if (item.row.kind == "notice") {
                (holder as NoticeHolder).bind(item.row)
            } else {
                (holder as RowHolder).bind(item.row)
            }
            is FeedItem.SuggestionsHeader -> (holder as BucketHolder).bind(item.label)
            is FeedItem.Suggestion -> (holder as SuggestionHolder).bind(item.user)
            is FeedItem.SeeAll -> (holder as SeeAllHolder).bind(item.label)
        }
    }

    private class BucketHolder(
        private val binding: ItemInboxSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            binding.tvSectionTitle.text = label
        }
    }

    private class RequestsHolder(
        private val binding: ItemInboxFollowRequestsBinding,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: OfficialFollowRequests) {
            binding.tvFollowReqTitle.text = summary.title
            binding.tvFollowReqSubtitle.text = summary.subtitle
            binding.ivFollowReqBack.isVisible = summary.avatars.size >= 2
            loadAvatar(binding.ivFollowReqFront, summary.avatars.getOrNull(0))
            loadAvatar(binding.ivFollowReqBack, summary.avatars.getOrNull(1))
            binding.root.setOnClickListener { callbacks.onOpenRequests(summary) }
        }
    }

    private class NoticeHolder(
        private val binding: ItemInboxOfficialNoticeBinding,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: OfficialRow) {
            val context = binding.root.context
            if (row.icon == "info") {
                // The reference's smaller info glyph: no tile, just the mark.
                binding.ivNoticeGlyph.setBackgroundResource(0)
                binding.ivNoticeGlyph.clipToOutline = false
                binding.ivNoticeGlyph.setPadding(
                    dp(binding.root, 9), dp(binding.root, 9),
                    dp(binding.root, 9), dp(binding.root, 9),
                )
                binding.ivNoticeGlyph.setImageResource(R.drawable.ic_inbox_info)
            } else {
                // The CamStar mark in a rounded tile, like the brand notices.
                binding.ivNoticeGlyph.setPadding(0, 0, 0, 0)
                binding.ivNoticeGlyph.setBackgroundResource(R.drawable.bg_inbox_thumb)
                binding.ivNoticeGlyph.clipToOutline = true
                binding.ivNoticeGlyph.setImageResource(R.drawable.ic_launcher_foreground)
            }
            renderNotificationText(context, binding.tvNoticeText, row, callbacks)
            binding.root.setOnClickListener { callbacks.onOpenNotice(row) }
        }
    }

    private class RowHolder(
        private val binding: ItemInboxOfficialRowBinding,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: OfficialRow) {
            val context = binding.root.context
            val actors = row.actors

            // Leading slot: one 44dp avatar, or the stacked 32dp pair.
            val stacked = actors.size >= 2
            binding.ivActor.isVisible = !stacked && actors.isNotEmpty()
            binding.ivActorBack.isVisible = stacked
            binding.ivActorFront.isVisible = stacked
            if (stacked) {
                loadAvatar(binding.ivActorBack, actors[1].avatar_url)
                loadAvatar(binding.ivActorFront, actors[0].avatar_url)
                binding.ivActorBack.setOnClickListener { callbacks.onOpenPerson(actors[1]) }
                binding.ivActorFront.setOnClickListener { callbacks.onOpenPerson(actors[0]) }
            } else if (actors.isNotEmpty()) {
                loadAvatar(binding.ivActor, actors[0].avatar_url)
                binding.ivActor.setOnClickListener { callbacks.onOpenPerson(actors[0]) }
            }

            renderNotificationText(context, binding.tvOfficialBody, row, callbacks)

            // Trailing slot: exactly one of thumbnail / Follow / Confirm + Delete.
            val trailing = row.trailing
            binding.ivTrailingThumb.isVisible = trailing.kind == "thumbnail"
            binding.btnTrailingFollow.isVisible = trailing.kind == "follow"
            binding.trailingActions.isVisible = trailing.kind == "confirm_delete"
            when (trailing.kind) {
                "thumbnail" -> {
                    binding.ivTrailingThumb.clipToOutline = true
                    Glide.with(binding.ivTrailingThumb)
                        .load(UnrealStarApiClient.localise(trailing.image_url))
                        .centerCrop()
                        .placeholder(R.drawable.bg_inbox_thumb)
                        .into(binding.ivTrailingThumb)
                    binding.ivTrailingThumb.setOnClickListener {
                        callbacks.onOpenThumbnail(row)
                    }
                }
                "follow" -> {
                    binding.btnTrailingFollow.text = context.getString(
                        if (trailing.is_following) R.string.me_following else R.string.follow
                    )
                    binding.btnTrailingFollow.setBackgroundResource(
                        if (trailing.is_following) R.drawable.bg_me_pill_outline
                        else R.drawable.bg_btn_primary
                    )
                    binding.btnTrailingFollow.setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (trailing.is_following) R.color.text_muted else R.color.text_white
                        )
                    )
                    binding.btnTrailingFollow.setOnClickListener {
                        callbacks.onFollow(trailing.user_id, !trailing.is_following)
                    }
                }
                "confirm_delete" -> {
                    binding.btnConfirm.setOnClickListener {
                        callbacks.onConfirm(trailing.user_id)
                    }
                    binding.btnDelete.setOnClickListener {
                        callbacks.onDelete(trailing.user_id)
                    }
                }
            }

            binding.root.setOnClickListener(if (actors.isEmpty()) null else View.OnClickListener {
                callbacks.onOpenPerson(actors[0])
            })
        }
    }

    private class SuggestionHolder(
        private val binding: ItemInboxSuggestionBinding,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(user: OfficialSuggestion) {
            val context = binding.root.context
            binding.tvSuggestionName.text = user.nickname
            binding.tvSuggestionProof.text = user.social_proof
            loadAvatar(binding.ivSuggestionAvatar, user.avatar_url)
            binding.btnSuggestionFollow.text = context.getString(
                if (user.is_following) R.string.me_following else R.string.follow
            )
            binding.btnSuggestionFollow.setBackgroundResource(
                if (user.is_following) R.drawable.bg_me_pill_outline
                else R.drawable.bg_btn_primary
            )
            binding.btnSuggestionFollow.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (user.is_following) R.color.text_muted else R.color.text_white
                )
            )
            binding.btnSuggestionFollow.setOnClickListener {
                callbacks.onFollow(user.user_id, !user.is_following)
            }
            val actor = OfficialActor(user.user_id, user.nickname, user.avatar_url)
            binding.ivSuggestionAvatar.setOnClickListener { callbacks.onOpenPerson(actor) }
            binding.root.setOnClickListener { callbacks.onOpenPerson(actor) }
        }
    }

    private class SeeAllHolder(
        private val binding: ItemInboxSeeAllBinding,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            binding.tvSeeAll.text = label
            binding.root.setOnClickListener { callbacks.onSeeAll() }
        }
    }
}

/**
 * Renders a feed row's body: `[tokens]` become highlight chips, `{actor}` /
 * `{actor2}` become the row's people (bold, tappable), `@mentions` render
 * gold, and the row's stamp trails the sentence in gray — the exact anatomy
 * of the reference notifications page.
 */
private fun renderNotificationText(
    context: Context,
    view: TextView,
    row: OfficialRow,
    callbacks: OfficialFeedAdapter.Callbacks
) {
    val text = SpannableStringBuilder()
    var hasLinks = false
    val body = row.body
    var index = 0

    while (index < body.length) {
        when (body[index]) {
            '[' -> {
                val end = body.indexOf(']', index + 1)
                if (end < 0) {
                    text.append(body[index])
                } else {
                    val chip = SpannableString(body.substring(index + 1, end))
                    chip.setSpan(
                        BackgroundColorSpan(0xFF3A3A5E.toInt()),
                        0, chip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    chip.setSpan(
                        ForegroundColorSpan(0xFFFFC93C.toInt()),
                        0, chip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    text.append(chip)
                    index = end
                }
            }
            '{' -> {
                val end = body.indexOf('}', index + 1)
                val token = if (end < 0) "" else body.substring(index, end + 1)
                val actorIndex = when (token) {
                    "{actor}" -> 0
                    "{actor2}" -> 1
                    else -> -1
                }
                val actor = if (actorIndex >= 0) row.actors.getOrNull(actorIndex) else null
                if (actor == null) {
                    text.append(body[index])
                } else {
                    val start = text.length
                    text.append(actor.nickname)
                    text.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                callbacks.onOpenPerson(actor)
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                // Keep the row's own color and weight; no underline.
                            }
                        },
                        start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    text.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    text.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_white)),
                        start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    hasLinks = true
                    index = end
                }
            }
            '@' -> {
                var end = index + 1
                while (end < body.length &&
                    (body[end].isLetterOrDigit() || body[end] == '_' || body[end] == '.')
                ) {
                    end++
                }
                if (end == index + 1) {
                    text.append(body[index])
                } else {
                    val start = text.length
                    text.append(body.substring(index, end))
                    text.setSpan(
                        ForegroundColorSpan(0xFFFFC93C.toInt()),
                        start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    index = end - 1
                }
            }
            else -> text.append(body[index])
        }
        index++
    }

    val stamp = officialStamp(row.created_at)
    if (stamp.isNotEmpty()) {
        val start = text.length
        text.append(" ").append(stamp)
        text.setSpan(
            ForegroundColorSpan(0xFF6E6E8C.toInt()),
            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    view.movementMethod = if (hasLinks) LinkMovementMethod.getInstance() else null
    view.highlightColor = 0
    view.text = text
}

private fun loadAvatar(view: ImageView, url: String?) {
    Glide.with(view)
        .load(UnrealStarApiClient.localise(url))
        .circleCrop()
        .placeholder(R.drawable.bg_live_avatar)
        .into(view)
}

private fun dp(view: View, value: Int): Int =
    (value * view.resources.displayMetrics.density).toInt()

/** The five category tiles are inflated directly, so this is a plain binder. */
private class CategoryAdapter(
    private val onSelect: (String) -> Unit
) {
    fun bind(binding: ItemInboxCategoryBinding, category: InboxCategory, active: Boolean) {
        binding.tvLabel.text = category.label
        binding.ivTile.setImageResource(iconFor(category.key))
        binding.ivTile.setBackgroundResource(tileFor(category.key))
        binding.tvLabel.setTextColor(
            ContextCompat.getColor(
                binding.root.context,
                if (active) R.color.text_white else R.color.text_muted
            )
        )
        binding.tvBadge.isVisible = category.unread > 0
        binding.tvBadge.text = compactCount(category.unread)
        binding.root.setOnClickListener { onSelect(category.key) }
        binding.root.alpha = if (active) 1f else 0.85f
    }

    private fun iconFor(key: String) = when (key) {
        "likes" -> R.drawable.ic_inbox_like
        "mentions" -> R.drawable.ic_inbox_mention
        "followers" -> R.drawable.ic_inbox_person
        "comments" -> R.drawable.ic_inbox_comment
        else -> R.drawable.ic_inbox_gift
    }

    private fun tileFor(key: String) = when (key) {
        "likes" -> R.drawable.bg_inbox_likes
        "mentions" -> R.drawable.bg_inbox_mentions
        "followers" -> R.drawable.bg_inbox_followers
        "comments" -> R.drawable.bg_inbox_comments
        else -> R.drawable.bg_inbox_gift
    }
}

/** Message rows on the Information tab. */
private class MessageAdapter(
    private val onMessageClick: (InboxMessage) -> Unit
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val items = mutableListOf<InboxMessage>()

    fun submit(next: List<InboxMessage>) {
        val oldSize = items.size
        items.clear()
        items.addAll(next)
        notifyListReplaced(oldSize, items.size)
    }

    fun markAllRead() {
        for (i in items.indices) {
            items[i] = items[i].copy(unread = false)
        }
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MessageViewHolder(
        ItemInboxMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        onMessageClick
    )

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) =
        holder.bind(items[position])

    override fun getItemCount() = items.size

    class MessageViewHolder(
        private val binding: ItemInboxMessageBinding,
        private val onMessageClick: (InboxMessage) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: InboxMessage) {
            binding.tvSender.text = message.sender_nickname
            binding.tvPreview.text = message.preview
            binding.tvTime.text = relativeStamp(message.created_at)
            binding.tvUnread.isVisible = message.unread
            Glide.with(binding.ivAvatar)
                .load(UnrealStarApiClient.localise(message.sender_avatar_url))
                .circleCrop()
                .placeholder(R.drawable.bg_live_avatar)
                .into(binding.ivAvatar)

            binding.root.setOnClickListener {
                onMessageClick(message)
            }
        }
    }
}

/** Message inbox rows: avatar, name (bold while unread), preview, relative time, unread dot. */
private class ConversationAdapter(
    private val onConversationClick: (ConversationSummary) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder>() {

    private val items = mutableListOf<ConversationSummary>()

    fun submit(next: List<ConversationSummary>) {
        val oldSize = items.size
        items.clear()
        items.addAll(next)
        notifyListReplaced(oldSize, items.size)
    }

    fun unreadPeerIds(): List<Long> = items.filter { it.unread_count > 0 }.map { it.peer_id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ConversationViewHolder(
        ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        onConversationClick
    )

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) =
        holder.bind(items[position])

    override fun getItemCount() = items.size

    class ConversationViewHolder(
        private val binding: ItemConversationBinding,
        private val onClick: (ConversationSummary) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: ConversationSummary) {
            val unread = conversation.unread_count > 0
            val context = binding.root.context

            binding.tvName.text = conversation.peer_nickname
            binding.tvName.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            binding.tvName.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (unread) R.color.text_white else R.color.text_muted
                )
            )
            // Four or more unread lines collapse to the reference's "4+ new messages".
            binding.tvPreview.text = if (conversation.unread_count >= 4) {
                context.getString(R.string.inbox_new_messages)
            } else {
                conversation.preview
            }
            binding.tvTime.text = relativeStamp(conversation.last_message_at)
            binding.tvUnread.isVisible = unread

            Glide.with(binding.ivAvatar)
                .load(UnrealStarApiClient.localise(conversation.peer_avatar_url))
                .circleCrop()
                .placeholder(R.drawable.bg_live_avatar)
                .into(binding.ivAvatar)

            binding.root.setOnClickListener { onClick(conversation) }
        }
    }
}

/** "now" / "12m" / "5h" / "1d" / "2w" — the reference message list's timestamps. */
private fun relativeStamp(raw: String): String {
    val date = parseUtc(raw) ?: return raw.take(16)
    val minutes = (System.currentTimeMillis() - date.time) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
        minutes < 60 * 24 * 28 -> "${minutes / (60 * 24 * 7)}w"
        else -> "${minutes / (60 * 24 * 30)}mo"
    }
}

/** "now" / "12m" / "5h" / "4d" then "Oct 09" — the reference notifications page. */
private fun officialStamp(raw: String): String {
    val date = parseUtc(raw) ?: return raw.take(16)
    val minutes = (System.currentTimeMillis() - date.time) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
        else -> SimpleDateFormat("MMM dd", Locale.US).format(date)
    }
}

/** Full timestamp for the notice dialog. */
private fun officialDate(raw: String): String = parseUtc(raw)?.let {
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(it)
} ?: raw.take(16)

/** Parses the server's RFC3339 UTC stamps. */
private fun parseUtc(raw: String): Date? = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    parser.timeZone = TimeZone.getTimeZone("UTC")
    parser.parse(raw.substringBefore('.').substringBefore('+'))
} catch (e: Exception) {
    null
}

/** 999 -> "999", 1200 -> "1.2K". */
private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}