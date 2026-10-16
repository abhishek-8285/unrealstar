package com.unrealstar.live

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemSearchResultBinding
import com.unrealstar.live.databinding.SheetSearchBinding
import com.unrealstar.live.models.FeedRoomItem
import com.unrealstar.live.models.SearchResponse
import com.unrealstar.live.models.SearchTopicItem
import com.unrealstar.live.models.SearchUserItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSearchBinding? = null
    private val binding get() = _binding!!

    private var currentFilter = "all"
    private var searchJob: Job? = null
    private val resultAdapter = SearchResultAdapter(
        onUserClick = { user ->
            lifecycleScope.launch {
                val ok = UnrealStarApiClient.followUser(user.user_id).getOrDefault(true)
                Toast.makeText(requireContext(), if (ok) "✓ Following ${user.nickname}" else "Unfollowed", Toast.LENGTH_SHORT).show()
                executeSearch(binding.etSearchQuery.text.toString().trim())
            }
        },
        onRoomClick = { room ->
            dismiss()
            WatchLiveActivity.start(requireContext(), room.room_id, room.host_nickname)
        },
        onTopicClick = { topic ->
            Toast.makeText(requireContext(), "Selected Topic ${topic.tag}", Toast.LENGTH_SHORT).show()
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchResults.adapter = resultAdapter

        setupTrendingChips()
        setupTabs()
        setupSearchInput()

        // Initial search to display trending live rooms & topics
        executeSearch("")
    }

    private fun setupTrendingChips() {
        val tags = listOf("#Party", "#Trending", "#Dance", "#Music", "#Live", "#Bollywood", "#RisingStar", "#Mushaira")
        binding.trendingChipsRow.removeAllViews()

        tags.forEach { tag ->
            val chip = TextView(requireContext()).apply {
                text = tag
                setTextColor(ContextCompat.getColor(context, R.color.text_white))
                textSize = 12f
                setBackgroundResource(R.drawable.bg_me_pill_outline)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                layoutParams = lp
                setOnClickListener {
                    binding.etSearchQuery.setText(tag)
                    binding.etSearchQuery.setSelection(tag.length)
                    executeSearch(tag)
                }
            }
            binding.trendingChipsRow.addView(chip)
        }
    }

    private fun setupTabs() {
        val tabs = listOf(
            Triple(binding.tabFilterAll, "all", "All"),
            Triple(binding.tabFilterHosts, "users", "Hosts"),
            Triple(binding.tabFilterRooms, "rooms", "Rooms"),
            Triple(binding.tabFilterTopics, "topics", "Topics")
        )

        val activeColor = ContextCompat.getColor(requireContext(), R.color.text_white)
        val mutedColor = ContextCompat.getColor(requireContext(), R.color.text_muted)

        tabs.forEach { (view, filterKey, _) ->
            view.setOnClickListener {
                currentFilter = filterKey
                tabs.forEach { (v, k, _) ->
                    val isActive = k == filterKey
                    v.setTextColor(if (isActive) activeColor else mutedColor)
                    v.typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                executeSearch(binding.etSearchQuery.text.toString().trim())
            }
        }
    }

    private fun setupSearchInput() {
        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                binding.btnClearSearch.isVisible = !s.isNullOrEmpty()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    executeSearch(s?.toString()?.trim() ?: "")
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchQuery.setText("")
            executeSearch("")
        }

        binding.btnSearch.setOnClickListener {
            executeSearch(binding.etSearchQuery.text.toString().trim())
        }

        binding.etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                executeSearch(binding.etSearchQuery.text.toString().trim())
                true
            } else false
        }
    }

    private fun executeSearch(query: String) {
        binding.searchProgress.isVisible = true
        binding.tvSearchEmpty.isVisible = false

        lifecycleScope.launch {
            val response = UnrealStarApiClient.search(query, currentFilter).getOrNull()
            binding.searchProgress.isVisible = false

            if (response == null || (response.users.isEmpty() && response.rooms.isEmpty() && response.topics.isEmpty())) {
                resultAdapter.clear()
                binding.tvSearchEmpty.text = if (query.isEmpty()) "Search for hosts, live rooms, or topics like #Party" else "No results found for \"$query\""
                binding.tvSearchEmpty.isVisible = true
            } else {
                binding.tvSearchEmpty.isVisible = false
                resultAdapter.submit(response, currentFilter)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SearchBottomSheet"
    }
}

/** Unified Search Result Adapter supporting Users, Rooms, and Topics */
class SearchResultAdapter(
    private val onUserClick: (SearchUserItem) -> Unit,
    private val onRoomClick: (FeedRoomItem) -> Unit,
    private val onTopicClick: (SearchTopicItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.SearchViewHolder>() {

    sealed class SearchItem {
        data class User(val data: SearchUserItem) : SearchItem()
        data class Room(val data: FeedRoomItem) : SearchItem()
        data class Topic(val data: SearchTopicItem) : SearchItem()
    }

    private val items = mutableListOf<SearchItem>()

    fun submit(response: SearchResponse, filter: String) {
        items.clear()
        when (filter) {
            "users" -> items.addAll(response.users.map { SearchItem.User(it) })
            "rooms" -> items.addAll(response.rooms.map { SearchItem.Room(it) })
            "topics" -> items.addAll(response.topics.map { SearchItem.Topic(it) })
            else -> {
                items.addAll(response.rooms.map { SearchItem.Room(it) })
                items.addAll(response.users.map { SearchItem.User(it) })
                items.addAll(response.topics.map { SearchItem.Topic(it) })
            }
        }
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val binding = ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SearchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class SearchViewHolder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SearchItem) {
            when (item) {
                is SearchItem.User -> {
                    val u = item.data
                    binding.tvResultTitle.text = u.nickname
                    binding.tvResultBadge.isVisible = true
                    binding.tvResultBadge.text = "Lv ${u.level}"
                    binding.tvResultSubtitle.text = "ID: ${u.user_id} • ${u.followers_count} followers${if (u.bio.isNotEmpty()) " • " + u.bio else ""}"
                    binding.tvLiveIndicator.isVisible = u.is_live
                    binding.btnResultAction.text = if (u.is_following) "Following" else "+ Follow"
                    binding.btnResultAction.setBackgroundResource(
                        if (u.is_following) R.drawable.bg_me_pill_outline else R.drawable.bg_me_pill_gradient
                    )

                    Glide.with(binding.ivResultIcon)
                        .load(UnrealStarApiClient.localise(u.avatar_url))
                        .circleCrop()
                        .placeholder(R.drawable.bg_live_avatar)
                        .into(binding.ivResultIcon)

                    binding.btnResultAction.setOnClickListener { onUserClick(u) }
                    binding.root.setOnClickListener { onUserClick(u) }
                }
                is SearchItem.Room -> {
                    val r = item.data
                    binding.tvResultTitle.text = r.title
                    binding.tvResultBadge.isVisible = r.is_pk
                    binding.tvResultBadge.text = "PK"
                    binding.tvResultSubtitle.text = "Host: ${r.host_nickname} • 👥 ${r.viewers_count} watching • ${r.category.uppercase()}"
                    binding.tvLiveIndicator.isVisible = true
                    binding.btnResultAction.text = "Watch"
                    binding.btnResultAction.setBackgroundResource(R.drawable.bg_me_pill_gradient)

                    Glide.with(binding.ivResultIcon)
                        .load(UnrealStarApiClient.localise(r.cover_image_url))
                        .centerCrop()
                        .placeholder(R.drawable.bg_live_avatar)
                        .into(binding.ivResultIcon)

                    binding.btnResultAction.setOnClickListener { onRoomClick(r) }
                    binding.root.setOnClickListener { onRoomClick(r) }
                }
                is SearchItem.Topic -> {
                    val t = item.data
                    binding.tvResultTitle.text = t.tag
                    binding.tvResultBadge.isVisible = false
                    binding.tvResultSubtitle.text = "${t.posts_count} moments posted under this topic"
                    binding.tvLiveIndicator.isVisible = false
                    binding.btnResultAction.text = "Explore"
                    binding.btnResultAction.setBackgroundResource(R.drawable.bg_me_pill_outline)

                    binding.ivResultIcon.setImageResource(R.drawable.ic_me_crown)

                    binding.btnResultAction.setOnClickListener { onTopicClick(t) }
                    binding.root.setOnClickListener { onTopicClick(t) }
                }
            }
        }
    }
}
