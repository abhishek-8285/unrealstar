package com.unrealstar.live

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.FragmentLiveBinding
import com.unrealstar.live.databinding.ItemLiveBannerBinding
import com.unrealstar.live.databinding.ItemLiveRoomBinding
import com.unrealstar.live.models.FeedRoomItem
import com.unrealstar.live.models.LiveBanner
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/**
 * Live tab: a promoted carousel above a two-column card wall, matching the
 * Party / Popular / New tab strip.
 *
 * Tabs map onto the backend contract in docs/API_SPECIFICATION.md rather than
 * renaming the API — `GET /feed/rooms?tab=…&page=N`.
 */
class LiveFragment : Fragment() {

    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private val adapter = RoomAdapter { room ->
        // Enter the watch screen: join + WebRTC viewer session happens there.
        WatchLiveActivity.start(requireContext(), room.room_id, room.host_nickname)
    }
    private val bannerAdapter = BannerAdapter()

    // Holds resource ids, not resolved strings: the fragment is constructed
    // before it is attached to a context, so getString() is illegal here.
    private val tabs = listOf(
        LiveTab(R.string.live_tab_party, "party"),
        LiveTab(R.string.live_tab_popular, "hot"),
        LiveTab(R.string.live_tab_new, "new_stars"),
        LiveTab(R.string.live_tab_pk, "pk"),
    )
    private val tabViews = mutableListOf<Pair<TextView, View>>()

    private var currentTab = "hot"
    private var currentPage = 0
    private var isLoading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.liveHeader.applyTopSystemInset()

        binding.rvBanners.layoutManager =
            LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        binding.rvBanners.adapter = bannerAdapter

        binding.rvRooms.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvRooms.adapter = adapter

        binding.btnRank.setOnClickListener {
            LeaderboardBottomSheet().show(childFragmentManager, LeaderboardBottomSheet.TAG)
        }

        // Two columns means ~4 rows of prefetch is plenty to keep the wall
        // filled while the next page loads.
        binding.rvRooms.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val manager = rv.layoutManager as? GridLayoutManager ?: return
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    loadPage(currentPage + 1, append = true)
                }
            }
        })

        buildTabs()
        loadBanners()
        loadPage(0, append = false)
    }

    /**
     * The tab strip is built in code so the active underline can track whichever
     * entry is selected without four near-identical blocks of XML.
     */
    private fun buildTabs() {
        val context = requireContext()
        binding.tabRow.removeAllViews()
        tabViews.clear()

        val margin = (12 * resources.displayMetrics.density).toInt()
        tabs.forEach { tab ->
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setPadding(margin, 2 * margin / 3, margin, margin / 2)
            }
            val label = TextView(context).apply {
                text = context.getString(tab.labelRes)
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                textSize = 14f
                typeface = Typeface.DEFAULT
                maxLines = 1
                isSingleLine = true
                // Without explicit params a vertical LinearLayout measures this
                // against the column's width and clips the label to one glyph.
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            // Short underline marking the active tab, like the reference.
            val underline = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (14 * resources.displayMetrics.density).toInt(),
                    (2 * resources.displayMetrics.density).toInt()
                ).apply { topMargin = margin / 2 }
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
            }
            column.addView(label)
            column.addView(underline)
            column.setOnClickListener { selectTab(tab) }

            tabViews += label to underline
            binding.tabRow.addView(
                column,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        highlightTab("hot")
    }

    private fun selectTab(tab: LiveTab) {
        if (tab.apiValue == currentTab) return
        currentTab = tab.apiValue
        highlightTab(tab.apiValue)
        binding.rvRooms.scrollToPosition(0)
        loadPage(0, append = false)
    }

    private fun highlightTab(apiValue: String) {
        val active = ContextCompat.getColor(requireContext(), R.color.primary)
        val inactive = ContextCompat.getColor(requireContext(), R.color.text_muted)
        tabs.forEachIndexed { index, tab ->
            val (label, underline) = tabViews[index]
            val isActive = tab.apiValue == apiValue
            label.setTextColor(if (isActive) active else inactive)
            label.typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            underline.setBackgroundColor(if (isActive) active else android.graphics.Color.TRANSPARENT)
        }
    }

    private fun loadBanners() {
        viewLifecycleOwner.lifecycleScope.launch {
            bannerAdapter.submit(UnrealStarApiClient.fetchLiveBanners().banners)
        }
    }

    private fun loadPage(page: Int, append: Boolean) {
        if (isLoading) return
        isLoading = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = UnrealStarApiClient.fetchRooms(currentTab, page)
            isLoading = false
            val current = _binding ?: return@launch

            currentPage = result.page
            if (append) {
                adapter.append(result.rooms)
            } else {
                adapter.submit(result.rooms)
            }

            val empty = adapter.itemCount == 0
            current.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            current.rvBanners.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvRooms.adapter = null
        binding.rvBanners.adapter = null
        _binding = null
    }
}

private data class LiveTab(val labelRes: Int, val apiValue: String)

private class RoomAdapter(
    private val onOpen: (FeedRoomItem) -> Unit
) : RecyclerView.Adapter<RoomAdapter.RoomViewHolder>() {

    private val items = mutableListOf<FeedRoomItem>()

    fun submit(next: List<FeedRoomItem>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    fun append(next: List<FeedRoomItem>) {
        if (next.isEmpty()) return
        val start = items.size
        items.addAll(next)
        notifyItemRangeInserted(start, next.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RoomViewHolder(
        ItemLiveRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class RoomViewHolder(private val binding: ItemLiveRoomBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(room: FeedRoomItem) {
            binding.tvHost.text = room.host_nickname
            binding.tvTitle.text = room.title
            binding.tvViewers.text = compactCount(room.viewers_count)

            Glide.with(binding.ivCover)
                .load(UnrealStarApiClient.localise(room.cover_image_url))
                .centerCrop()
                .into(binding.ivCover)

            bindTopLeftChip(room)

            // PK cards get the pink frame the reference uses to separate them.
            val stroke = if (room.is_pk) PK_STROKE else 0
            (binding.cardRoom as MaterialCardView).setStrokeWidth(stroke)
            (binding.cardRoom as MaterialCardView).strokeColor =
                ContextCompat.getColor(binding.root.context, R.color.primary)

            binding.tvHostBadge.text = room.host_badge.orEmpty()
            binding.tvHostBadge.visibility =
                if (room.host_badge.isNullOrEmpty()) View.GONE else View.VISIBLE

            binding.cardRoom.setOnClickListener { onOpen(room) }
        }

        /** PK wins over the promotional chip; promotional chip over nothing. */
        private fun bindTopLeftChip(room: FeedRoomItem) {
            val context = binding.root.context
            val chip = binding.chipTopLeft
            when {
                room.is_pk -> {
                    chip.visibility = View.VISIBLE
                    chip.setBackgroundResource(R.drawable.bg_live_pk_chip)
                    binding.ivChipIcon.setImageResource(R.drawable.ic_live_pk)
                    binding.tvChipText.text =
                        context.getString(R.string.live_chip_pk, room.pk_opponent.orEmpty())
                }

                !room.rank_tag.isNullOrEmpty() -> {
                    chip.visibility = View.VISIBLE
                    binding.tvChipText.tvChipIconTint(room.rank_tag!!)
                    binding.tvChipText.text = room.rank_tag
                }

                else -> chip.visibility = View.GONE
            }
        }

        /** "Free coins" chips invert to gold-on-dark; others stay neutral. */
        private fun TextView.tvChipIconTint(tag: String) {
            val context = binding.root.context
            val free = tag.contains("Free", ignoreCase = true)
            binding.chipTopLeft.setBackgroundResource(
                if (free) R.drawable.bg_live_free_chip else R.drawable.bg_live_rank_chip
            )
            binding.ivChipIcon.setImageResource(
                if (free) R.drawable.ic_live_gift else R.drawable.ic_live_pk
            )
            setTextColor(
                ContextCompat.getColor(context, if (free) R.color.background_dark else R.color.text_white)
            )
        }
    }

    private companion object {
        const val PK_STROKE = 2
    }
}

private class BannerAdapter : RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {

    private val items = mutableListOf<LiveBanner>()

    fun submit(next: List<LiveBanner>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = BannerViewHolder(
        ItemLiveBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: BannerViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    class BannerViewHolder(private val binding: ItemLiveBannerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(banner: LiveBanner) {
            binding.tvBannerTitle.text = banner.title
            binding.tvBannerSubtitle.text = banner.subtitle
            Glide.with(binding.ivBanner)
                .load(UnrealStarApiClient.localise(banner.image_url))
                .centerCrop()
                .into(binding.ivBanner)
        }
    }
}

/** 1200 -> "1.2K", 1733 -> "1.7K", matching the compact counts on the cards. */
private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
    else -> value.toString()
}