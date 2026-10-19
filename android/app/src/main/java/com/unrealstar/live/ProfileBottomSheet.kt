package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.SessionStore
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.SheetProfileBinding
import com.unrealstar.live.models.OtherUserProfile
import kotlinx.coroutines.launch

/**
 * Public profile of any other user — the thing every host avatar/name tap in
 * the app opens. Backed by GET /users/{id} (per-caller follow state included)
 * with graceful fallbacks when the id is unknown yet (host joins async).
 */
class ProfileBottomSheet(
    private val userId: Long,
    private val fallbackNickname: String = "",
    private val fallbackAvatarUrl: String? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetProfileBinding? = null
    private val binding get() = _binding!!

    // Local mutable view-state: the model is an immutable data class.
    private var isFollowing = false
    private var followers: Long = 0
    private var nickname = ""
    private var avatarUrl: String? = null
    private var liveRoomId: Long? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseProfile.setOnClickListener { dismiss() }

        // Optimistic identity while the fetch lands; the sheet is still
        // useful (DM/Gift) even if the lookup fails.
        nickname = fallbackNickname.ifEmpty {
            getString(R.string.profile_fallback_name, userId)
        }
        binding.tvProfileNickname.text = nickname
        avatarUrl = fallbackAvatarUrl
        fallbackAvatarUrl?.let { loadAvatar(it) }

        binding.btnProfileFollow.setOnClickListener { toggleFollow() }
        binding.btnProfileMessage.setOnClickListener { openDm() }
        binding.btnProfileGift.setOnClickListener { openGift() }
        binding.btnProfileJoinLive.setOnClickListener { joinLive() }

        loadProfile()
    }

    private fun loadProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            UnrealStarApiClient.getUserProfile(userId).onSuccess { p ->
                nickname = p.nickname.ifEmpty {
                    getString(R.string.profile_fallback_name, p.user_id)
                }
                avatarUrl = p.avatar_url
                isFollowing = p.is_following
                followers = p.followers_count

                binding.tvProfileNickname.text = nickname
                loadAvatar(p.avatar_url)

                val meta = buildList {
                    if (p.city.isNotBlank()) add(p.city)
                    if (p.age > 0) add(p.age.toString())
                    add(
                        p.creator_status.replace('_', ' ').lowercase()
                            .replaceFirstChar { it.uppercase() }
                    )
                }.joinToString(" · ")
                binding.tvProfileMeta.text = meta
                binding.tvProfileMeta.isVisible = meta.isNotBlank()
                binding.tvProfileBio.text = p.bio.ifBlank { null }
                binding.tvProfileBio.isVisible = p.bio.isNotBlank()

                binding.tvProfileFollowers.text = compact(followers)
                binding.tvProfileFollowing.text = compact(p.following_count)
                binding.tvProfileLikes.text = compact(p.likes_received)

                val self = p.user_id == SessionStore.userId
                binding.btnProfileFollow.isVisible = !self
                binding.btnProfileGift.isVisible = !self
                binding.btnProfileMessage.isVisible = !self
                renderFollowState()

                // A host mid-broadcast gets a direct door into their room.
                liveRoomId = p.live_room_id.takeIf { p.is_live }
                binding.btnProfileJoinLive.isVisible = liveRoomId != null
            }.onFailure {
                binding.tvProfileBio.text = getString(R.string.profile_load_failed)
            }
        }
    }

    private fun renderFollowState() {
        binding.btnProfileFollow.text = if (isFollowing) "✓ Following" else "+ Follow"
        binding.btnProfileFollow.alpha = if (isFollowing) 0.7f else 1f
    }

    private fun toggleFollow() {
        binding.btnProfileFollow.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val res = if (isFollowing) UnrealStarApiClient.unfollowUser(userId)
            else UnrealStarApiClient.followUser(userId)
            binding.btnProfileFollow.isEnabled = true
            when (val state = res.getOrNull()) {
                null -> Toast.makeText(
                    requireContext(),
                    getString(R.string.profile_follow_failed),
                    Toast.LENGTH_SHORT
                ).show()
                else -> {
                    isFollowing = state
                    followers += if (state) 1 else -1
                    binding.tvProfileFollowers.text = compact(followers)
                    renderFollowState()
                }
            }
        }
    }

    private fun openDm() {
        DirectMessageBottomSheet(
            targetUserId = userId,
            targetNickname = nickname,
            targetAvatarUrl = avatarUrl ?: ""
        ).show(parentFragmentManager, DirectMessageBottomSheet.TAG)
    }

    private fun openGift() {
        // Feed gifting uses the placeholder roomId=1 as well; the server owns
        // the price and the ledger books it against the recipient.
        val sheet = GiftBottomSheet(roomId = 1L, recipientId = userId) { gift, count, _, _ ->
            Toast.makeText(
                requireContext(),
                getString(R.string.feed_gift_sent, count, gift.name, nickname),
                Toast.LENGTH_SHORT
            ).show()
        }
        sheet.show(parentFragmentManager, GiftBottomSheet.TAG)
    }

    private fun joinLive() {
        val roomId = liveRoomId ?: return
        WatchLiveActivity.start(requireContext(), roomId, nickname)
    }

    private fun loadAvatar(url: String?) {
        val localised = UnrealStarApiClient.localise(url)
        Glide.with(binding.ivProfileAvatar)
            .load(localised ?: R.drawable.bg_live_avatar)
            .placeholder(R.drawable.bg_live_avatar)
            .circleCrop()
            .into(binding.ivProfileAvatar)
    }

    /** 1.2K / 3.4M — same shape the room cards use. */
    private fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "profile_sheet"
    }
}
