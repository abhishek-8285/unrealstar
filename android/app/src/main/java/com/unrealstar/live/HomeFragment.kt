package com.unrealstar.live

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.FragmentHomeBinding
import com.unrealstar.live.models.FeedPost
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val adapter = MomentAdapter()
    private var player: ExoPlayer? = null

    /** "For you" shows every moment; "Followed" filters to followed authors. */
    private var showingFollowed = false

    private val allMoments = mutableListOf<FeedPost>()

    /** Cursor for the next page; null once the feed is exhausted. */
    private var nextCursor: String? = null
    private var isLoadingPage = false

    private var loadFailed = false
    private var signedOut = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The feed stays full-bleed; only its header clears the status bar.
        binding.header.applyTopSystemInset()

        // Header height depends on the status-bar inset, so measure it and tell
        // the adapter how far down the promotional overlay must start.
        binding.header.doOnPreDraw { header ->
            val gap = (8 * resources.displayMetrics.density).toInt()
            adapter.bannerTopOffsetPx = header.bottom + gap
            adapter.notifyDataSetChanged()
        }

        player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(MediaCache.dataSourceFactory(requireContext()))
            )
            .build().also {
            // Without this the clip plays once and freezes on its last frame.
            it.repeatMode = Player.REPEAT_MODE_ONE
            adapter.player = it
        }

        binding.rvFeed.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        binding.rvFeed.adapter = adapter

        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(binding.rvFeed)
        binding.rvFeed.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                    val manager = rv.layoutManager ?: return
                    val snapView = snapHelper.findSnapView(manager) ?: return
                    val position = manager.getPosition(snapView)
                    adapter.setActive(position)

                    // Prefetch one page ahead so a swipe never stalls on the
                    // network.
                    if (position >= adapter.itemCount - 2) loadNextPage()
                }
            }
        )

        adapter.onAction = { moment, action -> perform(moment, action) }
        adapter.onOpenComments = { moment ->
            CommentsBottomSheet.newInstance(moment.moment_id, moment.comment_count)
                .show(parentFragmentManager, CommentsBottomSheet.TAG)
        }

        parentFragmentManager.setFragmentResultListener(
            CommentsBottomSheet.RESULT_COMMENTED, viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString(CommentsBottomSheet.RESULT_MOMENT_ID)?.let { id ->
                adapter.markActed(id, MomentAction.COMMENT)
                adapter.updateCommentCount(id, bundle.getLong(CommentsBottomSheet.RESULT_COMMENT_COUNT))
            }
        }

        wireHeader()
        binding.btnRetry.setOnClickListener { loadMoments() }
        loadMoments()
        startProgressTicker()
    }

    private fun startProgressTicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(120)
                adapter.updateProgress()
            }
        }
    }

    private fun wireHeader() {
        binding.tvForYou.setOnClickListener { selectTab(followed = false) }
        binding.tvFollowed.setOnClickListener { selectTab(followed = true) }
        binding.ivSearch.setOnClickListener {
            SearchBottomSheet().show(childFragmentManager, SearchBottomSheet.TAG)
        }
        binding.ivShield.setOnClickListener {
            Toast.makeText(requireContext(), "🛡️ Community Safety & Content Shield Active", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectTab(followed: Boolean) {
        if (showingFollowed == followed) return
        showingFollowed = followed

        val active = 0xFFFFFFFF.toInt()
        val inactive = 0xB3FFFFFF.toInt()

        binding.tvForYou.setTextColor(if (followed) inactive else active)
        binding.tvFollowed.setTextColor(if (followed) active else inactive)
        binding.tvForYou.setTypeface(null, if (followed) Typeface.NORMAL else Typeface.BOLD)
        binding.tvFollowed.setTypeface(null, if (followed) Typeface.BOLD else Typeface.NORMAL)

        binding.viewIndicator.visibility = if (followed) View.GONE else View.VISIBLE
        binding.viewIndicatorFollowed.visibility = if (followed) View.VISIBLE else View.GONE

        loadMoments()
    }

    private fun loadMoments() {
        if (isLoadingPage) return
        isLoadingPage = true

        viewLifecycleOwner.lifecycleScope.launch {
            val auth = UnrealStarApiClient.probeAuth()
            signedOut = auth == UnrealStarApiClient.AuthState.SignedOut
            loadFailed = auth == UnrealStarApiClient.AuthState.Unreachable

            if (signedOut || loadFailed) {
                allMoments.clear()
                nextCursor = null
                isLoadingPage = false
            } else {
                val page = UnrealStarApiClient.fetchFeed(cursor = null, limit = PAGE_SIZE, followedOnly = showingFollowed)
                allMoments.clear()
                allMoments.addAll(page.posts)
                nextCursor = page.next_cursor
                isLoadingPage = false
            }
            _binding ?: return@launch
            applyFilter()
        }
    }

    /** Fetches the next page and appends it without clearing what is on screen. */
    private fun loadNextPage() {
        val cursor = nextCursor ?: return
        if (isLoadingPage) return
        isLoadingPage = true

        viewLifecycleOwner.lifecycleScope.launch {
            val page = UnrealStarApiClient.fetchFeed(cursor = cursor, limit = PAGE_SIZE, followedOnly = showingFollowed)
            isLoadingPage = false
            nextCursor = page.next_cursor
            if (page.posts.isEmpty()) return@launch
            allMoments.addAll(page.posts)
            adapter.append(page.posts)
        }
    }

    private fun applyFilter() {
        val items: List<FeedPost> = allMoments

        adapter.submit(items)

        // Only surface a "nothing here" message when the request actually
        // succeeded; otherwise a dead backend would masquerade as a quiet feed.
        val showState = items.isEmpty()
        binding.stateBox.visibility = if (showState) View.VISIBLE else View.GONE

        if (showState) {
            when {
                signedOut -> {
                    binding.tvStateTitle.text = getString(R.string.sign_in_title)
                    binding.tvStateBody.text = getString(R.string.sign_in_body)
                    binding.btnRetry.visibility = View.GONE
                }
                loadFailed -> {
                    binding.tvStateTitle.text = getString(R.string.feed_offline_title)
                    binding.tvStateBody.text = getString(R.string.feed_offline_body)
                    binding.btnRetry.visibility = View.VISIBLE
                }
                showingFollowed -> {
                    binding.tvStateTitle.text = getString(R.string.feed_followed_title)
                    binding.tvStateBody.text = getString(R.string.feed_followed_body)
                    binding.btnRetry.visibility = View.GONE
                }
                else -> {
                    binding.tvStateTitle.text = getString(R.string.feed_empty_title)
                    binding.tvStateBody.text = getString(R.string.feed_empty_body)
                    binding.btnRetry.visibility = View.GONE
                }
            }
        }

        // The scroll listener only fires on an actual scroll, so on first load
        // nothing would ever become the "active" page and the player would
        // never receive a media item. Claim page 0 explicitly once laid out.
        if (items.isNotEmpty()) {
            binding.rvFeed.post { adapter.setActive(0) }
        }
    }

    private fun perform(moment: FeedPost, action: MomentAction) {
        // Comment text is created in CommentsBottomSheet; the rail only opens
        // the thread, so COMMENT never needs a request of its own.
        if (action == MomentAction.COMMENT) return

        if (action == MomentAction.FOLLOW) {
            viewLifecycleOwner.lifecycleScope.launch {
                val res = UnrealStarApiClient.followUser(moment.author_id)
                res.onSuccess {
                    adapter.markActed(moment.moment_id, MomentAction.FOLLOW)
                    Toast.makeText(requireContext(), "Followed ${moment.author_nickname}!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(requireContext(), "Follow note: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {

            val result = when (action) {
                MomentAction.LIKE -> UnrealStarApiClient.likeMoment(moment.moment_id)
                MomentAction.UNLIKE -> UnrealStarApiClient.unlikeMoment(moment.moment_id)
                MomentAction.SHARE -> UnrealStarApiClient.shareMoment(moment.moment_id)
                MomentAction.GIFT -> UnrealStarApiClient.giftMoment(moment.moment_id)
                MomentAction.COMMENT, MomentAction.FOLLOW -> return@launch
            }


            result
                .onSuccess { engagement ->
                    when (action) {
                        // No server-side "I shared this" flag exists, so these
                        // are recorded locally once the call succeeds.
                        MomentAction.SHARE -> adapter.markActed(moment.moment_id, action)
                        MomentAction.GIFT -> adapter.markActed(moment.moment_id, action)
                        else -> Unit
                    }
                    val liked = when (action) {
                        MomentAction.LIKE -> true
                        MomentAction.UNLIKE -> false
                        else -> null
                    }
                    adapter.applyEngagement(engagement, liked)
                }
                .onFailure {
                    Toast.makeText(requireContext(), R.string.action_failed, Toast.LENGTH_SHORT)
                        .show()
                }
        }
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        adapter.player = null
        _binding = null
    }

    private companion object {
        const val PAGE_SIZE = 10
    }
}