package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.FragmentMeBinding
import com.unrealstar.live.databinding.ItemMeActionBinding
import com.unrealstar.live.databinding.ItemMeStatBinding
import com.unrealstar.live.models.MeDashboard
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Me tab: bokeh header with avatar, ID badges and engagement counters, a row
 * of quick action tiles, then the DATA / POSTS / FAVORITES views.
 */
class MeFragment : Fragment() {

    private var _binding: FragmentMeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.meHeader.applyTopSystemInset()

        buildActionTiles()
        wireTabs()
        load()
    }

    /** Inflate the tiles once; they are static chrome, not a data list. */
    private fun buildActionTiles() {
        val current = _binding ?: return
        current.actionRow.removeAllViews()

        actions().forEach { action ->
            val tile = ItemMeActionBinding.inflate(
                LayoutInflater.from(requireContext()), current.actionRow, false
            )
            tile.tvActionLabel.text = getString(action.label)
            tile.ivAction.setBackgroundResource(action.background)
            tile.ivAction.setImageResource(action.icon)
            tile.root.setOnClickListener {
                when (action.label) {
                    R.string.me_live_action -> {
                        startActivity(android.content.Intent(requireContext(), CameraLiveActivity::class.java))
                    }
                    R.string.me_earnings -> {
                        EarningsBottomSheet(onPayoutSuccess = { load() }).show(childFragmentManager, EarningsBottomSheet.TAG)
                    }
                    R.string.me_svip -> {
                        VipTiersBottomSheet().show(childFragmentManager, VipTiersBottomSheet.TAG)
                    }
                    R.string.me_tasks, R.string.me_store -> {
                        DailyTasksBottomSheet(onRewardClaimed = { load() }).show(childFragmentManager, DailyTasksBottomSheet.TAG)
                    }
                    R.string.me_badge -> {
                        MyLevelBottomSheet(initialTab = "badges").show(childFragmentManager, MyLevelBottomSheet.TAG)
                    }
                    R.string.me_bag -> {
                        MyBagBottomSheet(onBagUpdated = { load() }).show(childFragmentManager, MyBagBottomSheet.TAG)
                    }
                    R.string.me_mylevel -> {
                        MyLevelBottomSheet(initialTab = "privileges").show(childFragmentManager, MyLevelBottomSheet.TAG)
                    }
                    R.string.me_family -> {
                        FamilyBottomSheet().show(childFragmentManager, FamilyBottomSheet.TAG)
                    }
                    R.string.me_creator -> {
                        lifecycleScope.launch {
                            val kyc = UnrealStarApiClient.fetchKycStatus().getOrNull()
                            // A rejection reason written by the review desk
                            // must reach the applicant, not just the status.
                            val msg = when {
                                kyc == null -> getString(R.string.me_creator_active)
                                kyc.status.equals("rejected", true) && !kyc.rejection_reason.isNullOrBlank() ->
                                    getString(R.string.me_kyc_rejected, kyc.rejection_reason)
                                else -> getString(R.string.me_kyc_status, kyc.status.uppercase())
                            }
                            Toast.makeText(requireContext(), getString(R.string.me_cheer, msg), Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {
                        Toast.makeText(requireContext(), R.string.me_soon, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            current.actionRow.addView(tile.root)
        }
    }

    private fun wireTabs() {
        val tabs = listOf(binding.tabData, binding.tabPosts, binding.tabFavorites)
        val active = ContextCompat.getColor(requireContext(), R.color.text_white)
        val inactive = ContextCompat.getColor(requireContext(), R.color.me_tab_idle)

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                tabs.forEachIndexed { i, t ->
                    val isActive = i == index
                    t.setTextColor(if (isActive) active else inactive)
                    t.typeface = if (isActive) android.graphics.Typeface.DEFAULT_BOLD
                    else android.graphics.Typeface.DEFAULT
                }
                when (index) {
                    0 -> {
                        binding.filterRow.visibility = View.VISIBLE
                        binding.statGridTop.visibility = View.VISIBLE
                        binding.statGridBottom.visibility = View.VISIBLE
                    }
                    1 -> {
                        binding.filterRow.visibility = View.GONE
                        binding.statGridTop.visibility = View.VISIBLE
                        binding.statGridBottom.visibility = View.GONE
                        Toast.makeText(requireContext(), getString(R.string.me_no_moments), Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        binding.filterRow.visibility = View.GONE
                        binding.statGridTop.visibility = View.GONE
                        binding.statGridBottom.visibility = View.VISIBLE
                        Toast.makeText(requireContext(), getString(R.string.me_no_favorites), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnFilterActivity.setOnClickListener {
            DailyTasksBottomSheet(onRewardClaimed = { load() }).show(childFragmentManager, DailyTasksBottomSheet.TAG)
        }
        binding.btnFilterToday.setOnClickListener {
            VipTiersBottomSheet().show(childFragmentManager, VipTiersBottomSheet.TAG)
        }
        binding.btnGift.setOnClickListener {
            MyBagBottomSheet(onBagUpdated = { load() }).show(childFragmentManager, MyBagBottomSheet.TAG)
        }
        binding.btnCrown.setOnClickListener {
            MyLevelBottomSheet().show(childFragmentManager, MyLevelBottomSheet.TAG)
        }
        binding.btnShare.setOnClickListener {
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "CamStar Live Profile")
                putExtra(android.content.Intent.EXTRA_TEXT, "Check out my profile on CamStar Live! User: ${binding.tvNickname.text} (${binding.tvDisplayId.text})")
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "Share Profile"))
        }
        binding.btnSettings.setOnClickListener {
            SettingsBottomSheet().show(childFragmentManager, SettingsBottomSheet.TAG)
        }
        binding.tvLevel.setOnClickListener {
            MyLevelBottomSheet().show(childFragmentManager, MyLevelBottomSheet.TAG)
        }
        binding.tvDiamonds.setOnClickListener {
            EarningsBottomSheet(onPayoutSuccess = { load() }).show(childFragmentManager, EarningsBottomSheet.TAG)
        }

        binding.tvDisplayId.setOnClickListener {
            val id = binding.tvDisplayId.text.toString().removePrefix("ID:").trim()
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("User ID", id))
            Toast.makeText(requireContext(), getString(R.string.common_copied_uid, id), Toast.LENGTH_SHORT).show()
        }

        binding.ivAvatar.setOnClickListener {
            val name = binding.tvNickname.text.toString()
            Toast.makeText(requireContext(), getString(R.string.me_logged_in, name), Toast.LENGTH_SHORT).show()
        }

        val openLeaderboard = View.OnClickListener {
            LeaderboardBottomSheet().show(childFragmentManager, LeaderboardBottomSheet.TAG)
        }
        binding.tvFollowing.setOnClickListener(openLeaderboard)
        binding.tvFollowers.setOnClickListener(openLeaderboard)
        binding.tvLikes.setOnClickListener(openLeaderboard)
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val dashboard = UnrealStarApiClient.fetchMeDashboard()
            val current = _binding ?: return@launch
            render(dashboard)
        }
    }

    private fun render(me: MeDashboard) {
        binding.tvNickname.text = me.nickname
        binding.tvBio.text = me.bio
        binding.tvDisplayId.text = me.display_id
        binding.tvAge.text = resources.getQuantityString(R.plurals.me_age_years, me.age, me.age)
        binding.tvLevel.text = getString(R.string.me_level, me.level)

        binding.tvFollowing.text = compactCount(me.following_count)
        binding.tvFollowers.text = compactCount(me.followers_count)
        binding.tvLikes.text = compactCount(me.likes_count)
        binding.tvDiamonds.text = compactCount(me.diamonds.toLong())

        Glide.with(binding.ivAvatar)
            .load(UnrealStarApiClient.localise(me.avatar_url))
            .circleCrop()
            .placeholder(R.drawable.bg_live_avatar)
            .into(binding.ivAvatar)

        viewLifecycleOwner.lifecycleScope.launch {
            val bag = UnrealStarApiClient.fetchMyBag().getOrNull()
            val frame = bag?.equipped_frame
            if (frame != null) {
                binding.ivAvatarFrame.visibility = View.VISIBLE
                when (frame) {
                    "frame_cyber_neon" -> binding.ivAvatarFrame.setImageResource(R.drawable.bg_frame_cyber_neon)
                    "frame_rose_blossom" -> binding.ivAvatarFrame.setImageResource(R.drawable.bg_frame_rose_blossom)
                    else -> binding.ivAvatarFrame.setImageResource(R.drawable.bg_frame_golden_aura)
                }
            } else {
                binding.ivAvatarFrame.visibility = View.GONE
            }
        }

        bindStats(me)
    }

    /** The DATA grid is a fixed 2x2, so build the four cards explicitly. */
    private fun bindStats(me: MeDashboard) {
        val current = _binding ?: return
        current.statGridTop.removeAllViews()
        current.statGridBottom.removeAllViews()

        val s = me.stats
        val cards = listOf(
            Triple(getString(R.string.me_stat_videos), s.videos_watched, R.drawable.ic_me_eye),
            Triple(getString(R.string.me_stat_lives), s.lives_watched, R.drawable.ic_me_live),
            Triple(
                getString(R.string.me_stat_time),
                s.time_spent_minutes,
                R.drawable.ic_me_crown
            ),
            Triple(getString(R.string.me_stat_active), s.active_days, R.drawable.ic_me_check),
        )

        cards.forEachIndexed { index, (label, value, _) ->
            val card = ItemMeStatBinding.inflate(
                LayoutInflater.from(requireContext()), current.statGridTop, false
            )
            card.tvStatLabel.text = label
            card.tvStatValue.text = formatValue(label, value)

            // Two cards per row: equal weight with a gutter on the second.
            val lp = android.widget.LinearLayout.LayoutParams(0, -2).apply {
                weight = 1f
                if (index % 2 == 1) {
                    marginStart = (6 * resources.displayMetrics.density).toInt()
                }
            }
            card.root.layoutParams = lp

            val row = if (index < 2) current.statGridTop else current.statGridBottom
            row.addView(card.root)
        }
    }

    private fun formatValue(label: String, value: Long): String = when {
        label == getString(R.string.me_stat_time) -> {
            val hours = value / 60
            val minutes = value % 60
            if (hours > 0) String.format(Locale.US, "%dh %dm", hours, minutes)
            else String.format(Locale.US, "%dm", minutes)
        }

        else -> compactCount(value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class MeAction(
        val label: Int,
        val icon: Int,
        val background: Int,
    )

    private fun actions() = listOf(
        MeAction(R.string.me_live_action, R.drawable.ic_me_live, R.drawable.bg_me_live),
        MeAction(R.string.me_earnings, R.drawable.ic_me_creator, R.drawable.bg_me_creator),
        MeAction(R.string.me_svip, R.drawable.ic_me_svip, R.drawable.bg_me_svip),
        MeAction(R.string.me_tasks, R.drawable.ic_me_store, R.drawable.bg_me_store),
        MeAction(R.string.me_badge, R.drawable.ic_live_trophy, R.drawable.bg_me_family),
        MeAction(R.string.me_bag, R.drawable.ic_me_gift, R.drawable.bg_me_store),
        MeAction(R.string.me_mylevel, R.drawable.ic_me_diamond, R.drawable.bg_me_svip),
        MeAction(R.string.me_family, R.drawable.ic_me_family, R.drawable.bg_me_family),
    )
}

/** 19662 -> "19.7K", 7_500_000 -> "7.5M". */
private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fm", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}