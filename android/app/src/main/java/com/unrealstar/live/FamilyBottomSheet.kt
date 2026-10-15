package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemFamilyBinding
import com.unrealstar.live.databinding.SheetFamilyBinding
import com.unrealstar.live.models.FamilyItem
import kotlinx.coroutines.launch

class FamilyBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetFamilyBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetFamilyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.rvFamilies.layoutManager = LinearLayoutManager(requireContext())

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.rvFamilies.visibility = View.GONE

            // 1. Load user's family
            val myFamRes = UnrealStarApiClient.fetchMyFamily().getOrNull()
            if (myFamRes?.family != null) {
                val f = myFamRes.family
                binding.tvMyFamilyStatus.text = "${f.name} ${f.badge}\nRole: ${myFamRes.my_role.uppercase()} • Lv.${f.level} • ${f.members_count} Members"
            } else {
                binding.tvMyFamilyStatus.text = "You haven't joined a family yet.\nJoin a top clan below to unlock family bonuses!"
            }

            // 2. Load family leaderboard
            val lbRes = UnrealStarApiClient.fetchFamilyLeaderboard()
            binding.progressBar.visibility = View.GONE
            lbRes.onSuccess { list ->
                binding.rvFamilies.visibility = View.VISIBLE
                binding.rvFamilies.adapter = FamilyAdapter(list)
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Failed to load families: ${err.message}", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    private fun joinFamily(family: FamilyItem) {
        lifecycleScope.launch {
            val res = UnrealStarApiClient.joinFamily(family.family_id)
            res.onSuccess {
                Toast.makeText(requireContext(), "🎉 Successfully joined ${family.name}!", Toast.LENGTH_SHORT).show()
                loadData()
            }.onFailure {
                Toast.makeText(requireContext(), "Failed to join: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class FamilyAdapter(private val items: List<FamilyItem>) :
        RecyclerView.Adapter<FamilyAdapter.VH>() {

        inner class VH(val b: ItemFamilyBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemFamilyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvRank.text = "${item.rank}"
            holder.b.tvFamilyName.text = item.name
            holder.b.tvBadge.text = item.badge
            holder.b.tvDetails.text = "Lv.${item.level} • ${item.members_count} Members • Leader: ${item.leader_name}"

            holder.b.btnJoin.setOnClickListener {
                joinFamily(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val TAG = "FamilyBottomSheet"
    }
}
