package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemLeaderboardBinding
import com.unrealstar.live.databinding.SheetLeaderboardBinding
import com.unrealstar.live.models.LeaderboardItem
import kotlinx.coroutines.launch

class LeaderboardBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetLeaderboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetLeaderboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.rvLeaderboard.layoutManager = LinearLayoutManager(requireContext())

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.rvLeaderboard.visibility = View.GONE

            val res = UnrealStarApiClient.fetchLeaderboards()
            binding.progressBar.visibility = View.GONE
            res.onSuccess { list ->
                binding.rvLeaderboard.visibility = View.VISIBLE
                binding.rvLeaderboard.adapter = LeaderboardAdapter(list)
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Failed to load leaderboard: ${err.message}", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class LeaderboardAdapter(private val items: List<LeaderboardItem>) :
        RecyclerView.Adapter<LeaderboardAdapter.VH>() {

        inner class VH(val b: ItemLeaderboardBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemLeaderboardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val rankText = when (item.rank) {
                1L -> "🥇"
                2L -> "🥈"
                3L -> "🥉"
                else -> "#${item.rank}"
            }
            holder.b.tvRank.text = rankText
            holder.b.tvNickname.text = item.nickname
            holder.b.tvScore.text = "⚡ ${item.score}"

            Glide.with(holder.b.ivAvatar)
                .load(UnrealStarApiClient.localise(item.avatar_url))
                .placeholder(R.drawable.bg_live_avatar)
                .circleCrop()
                .into(holder.b.ivAvatar)

            holder.b.root.setOnClickListener {
                DirectMessageBottomSheet(
                    targetUserId = item.user_id,
                    targetNickname = item.nickname,
                    targetAvatarUrl = item.avatar_url
                ).show(parentFragmentManager, "dm_chat")
            }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        const val TAG = "LeaderboardBottomSheet"
    }
}
