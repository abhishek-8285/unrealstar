package com.unrealstar.live

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.unrealstar.live.databinding.ItemInboxCategoryBinding
import com.unrealstar.live.databinding.ItemInboxMessageBinding
import com.unrealstar.live.databinding.ItemInboxOfficialBinding
import com.unrealstar.live.models.InboxCategory
import com.unrealstar.live.models.InboxMessage
import com.unrealstar.live.models.OfficialNotice
import com.unrealstar.live.models.OfficialSection
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Inbox with two top-level views:
 *  - Official: system notices under Officially / Event / Picked / Task.
 *  - Information: the message thread list under the five category tiles.
 */
class InboxFragment : Fragment() {

    private var _binding: FragmentInboxBinding? = null
    private val binding get() = _binding!!

    private val categoryBinder = CategoryAdapter { selectCategory(it) }
    private val messageAdapter = MessageAdapter()
    private val officialAdapter = OfficialAdapter()

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
            Toast.makeText(requireContext(), "✓ All notifications marked as read", Toast.LENGTH_SHORT).show()
        }
        binding.btnInboxSettings.setOnClickListener {
            SettingsBottomSheet().show(childFragmentManager, SettingsBottomSheet.TAG)
        }
        binding.btnOfficialRetry.setOnClickListener { loadOfficial() }

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
        current.rvOfficial.isVisible = official && officialHasData
        current.rvMessages.isVisible = !official
        if (!official) {
            current.officialLoading.isVisible = false
            current.officialError.isVisible = false
        }

        if (official) loadOfficial() else loadMessages()
    }

    // ---- Official ----------------------------------------------------------

    private fun loadOfficial() {
        val current = _binding ?: return
        current.rvOfficial.isVisible = false
        current.officialError.isVisible = false
        current.officialLoading.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = UnrealStarApiClient.fetchOfficialNotices(officialSection)
            val b = _binding ?: return@launch
            b.officialLoading.isVisible = false
            result.fold(
                onSuccess = { page ->
                    b.officialError.isVisible = false
                    officialHasData = true
                    b.rvOfficial.isVisible = showingOfficial
                    officialAdapter.submit(page.notices)
                    buildOfficialTabs(page.sections)
                },
                onFailure = {
                    officialHasData = false
                    if (showingOfficial) b.officialError.isVisible = true
                },
            )
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

    private fun selectCategory(key: String?) {
        if (selectedCategory == key) return
        selectedCategory = key
        loadCategories()
        loadMessages()
    }

    private fun loadMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            val page = UnrealStarApiClient.fetchInbox(selectedCategory)
            val current = _binding ?: return@launch
            messageAdapter.submit(page.messages)
            current.tvEmpty.isVisible = page.messages.isEmpty()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvMessages.adapter = null
        binding.rvOfficial.adapter = null
        _binding = null
    }
}

/** Official notice feed. [bracketed] tokens render as highlight chips. */
private class OfficialAdapter : RecyclerView.Adapter<OfficialAdapter.OfficialViewHolder>() {

    private val items = mutableListOf<OfficialNotice>()

    fun submit(next: List<OfficialNotice>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = OfficialViewHolder(
        ItemInboxOfficialBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: OfficialViewHolder, position: Int) =
        holder.bind(items[position])

    override fun getItemCount() = items.size

    class OfficialViewHolder(private val binding: ItemInboxOfficialBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(notice: OfficialNotice) {
            binding.tvNoticeTitle.text = notice.title
            binding.tvNoticeTime.text = stamp(notice.created_at)
            renderBody(notice.body)

            val image = UnrealStarApiClient.localise(notice.image_url)
            binding.ivNoticeImage.isVisible = !image.isNullOrBlank()
            if (!image.isNullOrBlank()) {
                Glide.with(binding.ivNoticeImage).load(image).centerCrop()
                    .into(binding.ivNoticeImage)
            }
        }

        private fun renderBody(body: String) {
            val view = binding.tvNoticeBody
            view.text = ""
            val pattern = Regex("\\[([^]]+)]")
            var cursor = 0
            pattern.findAll(body).forEach { match ->
                if (match.range.first > cursor) {
                    view.append(body.substring(cursor, match.range.first))
                }
                val chip = SpannableString(match.groupValues.firstOrNull().orEmpty())
                chip.setSpan(
                    BackgroundColorSpan(0xFF3A3A5E.toInt()),
                    0, chip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                chip.setSpan(
                    ForegroundColorSpan(0xFFFFC93C.toInt()),
                    0, chip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                view.append(chip)
                cursor = match.range.last + 1
            }
            if (cursor < body.length) view.append(body.substring(cursor))
        }

        private fun stamp(raw: String): String = try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date: Date = parser.parse(raw.substringBefore('.').substringBefore('+'))!!
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(date)
        } catch (e: Exception) {
            raw.take(16)
        }
    }
}

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
private class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val items = mutableListOf<InboxMessage>()

    fun submit(next: List<InboxMessage>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MessageViewHolder(
        ItemInboxMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) =
        holder.bind(items[position])

    override fun getItemCount() = items.size

    class MessageViewHolder(private val binding: ItemInboxMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: InboxMessage) {
            binding.tvSender.text = message.sender_nickname
            binding.tvPreview.text = message.preview
            binding.tvTime.text = stamp(message.created_at)
            binding.tvUnread.isVisible = message.unread
            Glide.with(binding.ivAvatar)
                .load(UnrealStarApiClient.localise(message.sender_avatar_url))
                .circleCrop()
                .placeholder(R.drawable.bg_live_avatar)
                .into(binding.ivAvatar)

            binding.root.setOnClickListener {
                Toast.makeText(
                    binding.root.context,
                    "💬 ${message.sender_nickname}: ${message.preview}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun stamp(raw: String): String = try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date: Date = parser.parse(raw.substringBefore('.').substringBefore('+'))!!
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(date)
        } catch (e: Exception) {
            raw.take(16)
        }
    }
}

/** 999 -> "999", 1200 -> "1.2K". */
private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}