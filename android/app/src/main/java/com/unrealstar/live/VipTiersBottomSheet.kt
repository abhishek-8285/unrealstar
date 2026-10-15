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
import com.unrealstar.live.databinding.ItemVipTierBinding
import com.unrealstar.live.databinding.SheetVipTiersBinding
import com.unrealstar.live.models.VipTierItem
import kotlinx.coroutines.launch

class VipTiersBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetVipTiersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetVipTiersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.rvTiers.layoutManager = LinearLayoutManager(requireContext())

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.rvTiers.visibility = View.GONE

            val res = UnrealStarApiClient.fetchVipTiers()
            binding.progressBar.visibility = View.GONE
            res.onSuccess { tiers ->
                binding.rvTiers.visibility = View.VISIBLE
                binding.rvTiers.adapter = VipAdapter(tiers)
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Failed to load VIP tiers: ${err.message}", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class VipAdapter(private val tiers: List<VipTierItem>) :
        RecyclerView.Adapter<VipAdapter.VH>() {

        inner class VH(val b: ItemVipTierBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemVipTierBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tier = tiers[position]
            holder.b.tvTierName.text = tier.name
            holder.b.tvPrivileges.text = tier.privileges.joinToString(", ")
            holder.b.tvPrice.text = "₹${tier.price_inr}"
        }

        override fun getItemCount() = tiers.size
    }

    companion object {
        const val TAG = "VipTiersBottomSheet"
    }
}
