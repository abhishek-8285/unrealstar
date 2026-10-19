package com.unrealstar.live

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemBadgeBinding
import com.unrealstar.live.databinding.ItemPrivilegeBinding
import com.unrealstar.live.databinding.SheetMyLevelBinding
import com.unrealstar.live.models.BadgeItem
import com.unrealstar.live.models.LevelPrivilegeItem
import com.unrealstar.live.models.UserLevelResponse
import kotlinx.coroutines.launch
import java.util.Locale

class MyLevelBottomSheet(
    private val initialTab: String = "privileges"
) : BottomSheetDialogFragment() {

    private var _binding: SheetMyLevelBinding? = null
    private val binding get() = _binding!!

    private var activeTab = initialTab
    private var levelData: UserLevelResponse? = null

    private val privilegeAdapter = PrivilegeAdapter()
    private val badgeAdapter = BadgeAdapter { badge ->
        val status = if (badge.is_unlocked) "Unlocked on ${badge.unlocked_at ?: "recent"}" else "Locked: Complete requirements to claim"
        Toast.makeText(requireContext(), getString(R.string.level_status, badge.name, status), Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetMyLevelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseLevel.setOnClickListener { dismiss() }

        setupTabs()
        loadLevelData()
    }

    private fun setupTabs() {
        val active = ContextCompat.getColor(requireContext(), R.color.text_white)
        val muted = ContextCompat.getColor(requireContext(), R.color.text_muted)

        fun updateTabStyles() {
            val isPrivileges = activeTab == "privileges"
            binding.tabPrivileges.setTextColor(if (isPrivileges) active else muted)
            binding.tabPrivileges.typeface = if (isPrivileges) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            binding.tabBadges.setTextColor(if (!isPrivileges) active else muted)
            binding.tabBadges.typeface = if (!isPrivileges) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            if (isPrivileges) {
                binding.rvLevelContent.layoutManager = LinearLayoutManager(requireContext())
                binding.rvLevelContent.adapter = privilegeAdapter
            } else {
                binding.rvLevelContent.layoutManager = GridLayoutManager(requireContext(), 2)
                binding.rvLevelContent.adapter = badgeAdapter
            }
        }

        binding.tabPrivileges.setOnClickListener {
            activeTab = "privileges"
            updateTabStyles()
        }

        binding.tabBadges.setOnClickListener {
            activeTab = "badges"
            updateTabStyles()
        }

        updateTabStyles()
    }

    private fun loadLevelData() {
        binding.levelProgress.isVisible = true

        lifecycleScope.launch {
            val res = UnrealStarApiClient.fetchUserLevel().getOrNull()
            binding.levelProgress.isVisible = false
            levelData = res

            if (res != null) {
                binding.tvMyLevelBadge.text = getString(R.string.level_lv_badge, res.current_level)
                binding.tvMyWealthTitle.text = res.wealth_title
                binding.tvMyExpLabel.text = String.format(
                    Locale.US,
                    "%,d / %,d EXP (%d%%)",
                    res.current_exp,
                    res.next_level_exp,
                    res.progress_pct
                )
                binding.levelProgressBar.progress = res.progress_pct

                privilegeAdapter.submit(res.privileges)
                badgeAdapter.submit(res.badges)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MyLevelBottomSheet"
    }
}

class PrivilegeAdapter : RecyclerView.Adapter<PrivilegeAdapter.PrivilegeViewHolder>() {

    private val items = mutableListOf<LevelPrivilegeItem>()

    fun submit(newItems: List<LevelPrivilegeItem>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        notifyListReplaced(oldSize, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrivilegeViewHolder {
        val binding = ItemPrivilegeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PrivilegeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PrivilegeViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PrivilegeViewHolder(private val binding: ItemPrivilegeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LevelPrivilegeItem) {
            binding.tvPrivilegeLevel.text = binding.root.context.getString(R.string.level_lv_badge, item.level)
            binding.tvPrivilegeTitle.text = item.title
            binding.tvPrivilegeDesc.text = item.description

            if (item.is_unlocked) {
                binding.tvPrivilegeStatus.text = binding.root.context.getString(R.string.level_unlocked_check)
                binding.tvPrivilegeStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.disclaimer_green))
            } else {
                binding.tvPrivilegeStatus.text = binding.root.context.getString(R.string.locked)
                binding.tvPrivilegeStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_muted))
            }

            binding.root.setOnClickListener {
                val status = if (item.is_unlocked) "Unlocked privilege" else "Requires Level ${item.level} to unlock"
                Toast.makeText(binding.root.context, binding.root.context.getString(R.string.level_crown, item.title, status), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class BadgeAdapter(
    private val onBadgeClick: (BadgeItem) -> Unit
) : RecyclerView.Adapter<BadgeAdapter.BadgeViewHolder>() {

    private val items = mutableListOf<BadgeItem>()

    fun submit(newItems: List<BadgeItem>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        notifyListReplaced(oldSize, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BadgeViewHolder {
        val binding = ItemBadgeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BadgeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BadgeViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class BadgeViewHolder(private val binding: ItemBadgeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BadgeItem) {
            binding.tvBadgeName.text = item.name
            binding.tvBadgeDesc.text = item.description

            if (item.is_unlocked) {
                binding.tvBadgeStatus.text = binding.root.context.getString(R.string.unlocked_upper)
                binding.tvBadgeStatus.setBackgroundResource(R.drawable.bg_me_pill_gradient)
                binding.ivBadgeIcon.alpha = 1.0f
            } else {
                binding.tvBadgeStatus.text = binding.root.context.getString(R.string.locked_upper)
                binding.tvBadgeStatus.setBackgroundResource(R.drawable.bg_me_pill_outline)
                binding.ivBadgeIcon.alpha = 0.35f
            }

            binding.root.setOnClickListener { onBadgeClick(item) }
        }
    }
}
