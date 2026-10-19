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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemCosmeticBinding
import com.unrealstar.live.databinding.SheetMyBagBinding
import com.unrealstar.live.models.CosmeticItem
import com.unrealstar.live.models.UserBagResponse
import kotlinx.coroutines.launch

class MyBagBottomSheet(
    private val onBagUpdated: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetMyBagBinding? = null
    private val binding get() = _binding!!

    private var currentCategory = "avatar_frame"
    private var bagData: UserBagResponse? = null
    private val adapter = CosmeticAdapter { item ->
        if (!item.is_owned) {
            promptPurchase(item)
        } else {
            toggleEquip(item)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetMyBagBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvBagItems.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvBagItems.adapter = adapter

        binding.btnCloseBag.setOnClickListener { dismiss() }

        setupTabs()
        loadBag()
    }

    private fun setupTabs() {
        val tabs = listOf(
            Triple(binding.tabFrames, "avatar_frame", "Frames"),
            Triple(binding.tabRides, "entry_ride", "Rides"),
            Triple(binding.tabBubbles, "chat_bubble", "Bubbles"),
            Triple(binding.tabMedals, "medal", "Medals")
        )

        val active = ContextCompat.getColor(requireContext(), R.color.text_white)
        val muted = ContextCompat.getColor(requireContext(), R.color.text_muted)

        tabs.forEach { (view, catKey, _) ->
            view.setOnClickListener {
                currentCategory = catKey
                tabs.forEach { (v, k, _) ->
                    val isActive = k == catKey
                    v.setTextColor(if (isActive) active else muted)
                    v.typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                filterItems()
            }
        }
    }

    private fun loadBag(showSpinner: Boolean = true) {
        binding.bagProgress.isVisible = showSpinner
        binding.tvBagEmpty.isVisible = false

        lifecycleScope.launch {
            val res = UnrealStarApiClient.fetchMyBag().getOrNull()
            binding.bagProgress.isVisible = false
            bagData = res
            filterItems()
        }
    }

    private fun filterItems() {
        val allItems = bagData?.items ?: emptyList()
        val filtered = allItems.filter { it.category == currentCategory }
        adapter.submit(filtered)
        binding.tvBagEmpty.isVisible = filtered.isEmpty()
    }

    private fun promptPurchase(item: CosmeticItem) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Unlock ${item.name}")
            .setMessage("Unlock this ${item.category.replace('_', ' ')} for 🪙 ${item.price_coins} Coins?\n\n${item.description}")
            .setPositiveButton("Unlock & Equip") { _, _ ->
                lifecycleScope.launch {
                    UnrealStarApiClient.equipCosmetic(item.item_id, item.category, true)
                        .onSuccess {
                            // Re-read the server copy rather than guessing: the
                            // purchase may have failed (coins) or the server may
                            // have kept a different equipped item.
                            loadBag(showSpinner = false)
                            Toast.makeText(requireContext(), getString(R.string.bag_unlocked, item.name), Toast.LENGTH_SHORT).show()
                            onBagUpdated?.invoke()
                        }
                        .onFailure {
                            Toast.makeText(requireContext(), it.message, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNeutralButton("Recharge") { _, _ ->
                CoinStoreBottomSheet().show(parentFragmentManager, CoinStoreBottomSheet.TAG)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleEquip(item: CosmeticItem) {
        val nextEquipState = !item.is_equipped
        lifecycleScope.launch {
            UnrealStarApiClient.equipCosmetic(item.item_id, item.category, nextEquipState)
                .onSuccess {
                    // The server owns the "one per category" rule; refresh the
                    // list so the badges match it instead of a local guess that
                    // reverts the next time the sheet opens.
                    loadBag(showSpinner = false)
                    val action = if (nextEquipState) "Equipped ${item.name}" else "Unequipped ${item.name}"
                    Toast.makeText(requireContext(), getString(R.string.bag_action, action), Toast.LENGTH_SHORT).show()
                    onBagUpdated?.invoke()
                }
                .onFailure {
                    Toast.makeText(requireContext(), it.message, Toast.LENGTH_SHORT).show()
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MyBagBottomSheet"
    }
}

class CosmeticAdapter(
    private val onActionClick: (CosmeticItem) -> Unit
) : RecyclerView.Adapter<CosmeticAdapter.CosmeticViewHolder>() {

    private val items = mutableListOf<CosmeticItem>()

    fun submit(newItems: List<CosmeticItem>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        notifyListReplaced(oldSize, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CosmeticViewHolder {
        val binding = ItemCosmeticBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CosmeticViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CosmeticViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class CosmeticViewHolder(private val binding: ItemCosmeticBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CosmeticItem) {
            binding.tvCosmeticName.text = item.name
            binding.tvCosmeticDesc.text = item.description

            binding.tvCosmeticEquippedBadge.isVisible = item.is_equipped

            val fallbackIcon = when (item.category) {
                "avatar_frame" -> R.drawable.ic_me_crown
                "entry_ride" -> R.drawable.ic_me_store
                "chat_bubble" -> R.drawable.ic_me_diamond
                else -> R.drawable.ic_live_trophy
            }

            Glide.with(binding.ivCosmeticIcon)
                .load(UnrealStarApiClient.localise(item.icon_url))
                .placeholder(fallbackIcon)
                .error(fallbackIcon)
                .into(binding.ivCosmeticIcon)

            if (!item.is_owned) {
                binding.btnCosmeticAction.text = binding.root.context.getString(R.string.cosmetic_price, item.price_coins)
                binding.btnCosmeticAction.setBackgroundResource(R.drawable.bg_me_pill_outline)
            } else if (item.is_equipped) {
                binding.btnCosmeticAction.text = binding.root.context.getString(R.string.in_use)
                binding.btnCosmeticAction.setBackgroundResource(R.drawable.bg_me_pill_gradient)
            } else {
                binding.btnCosmeticAction.text = binding.root.context.getString(R.string.equip)
                binding.btnCosmeticAction.setBackgroundResource(R.drawable.bg_me_pill_outline)
            }

            binding.btnCosmeticAction.setOnClickListener { onActionClick(item) }
            binding.root.setOnClickListener { onActionClick(item) }
        }
    }
}
