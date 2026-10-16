package com.unrealstar.live

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemGiftCardBinding
import com.unrealstar.live.databinding.SheetGiftDialogBinding
import com.unrealstar.live.models.GiftCatalogItem
import com.unrealstar.live.models.SendGiftRequest
import kotlinx.coroutines.launch
import java.util.UUID

class GiftBottomSheet(
    private val roomId: Long,
    private val recipientId: Long,
    private val onGiftSent: (gift: GiftCatalogItem, count: Int, comboSeq: Int, remainingCoins: Long) -> Unit = { _, _, _, _ -> }
) : BottomSheetDialogFragment() {

    private var _binding: SheetGiftDialogBinding? = null
    private val binding get() = _binding!!

    private val gifts = mutableListOf<GiftCatalogItem>()
    private var selectedGift: GiftCatalogItem? = null
    private var selectedQuantity: Int = 1
    private var comboSequence: Int = 0
    private var currentCoins: Long = 0L
    private lateinit var adapter: GiftCardAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetGiftDialogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = GiftCardAdapter(gifts) { item ->
            selectedGift = item
            adapter.notifyDataSetChanged()
            updateSendButton()
        }
        binding.rvGiftGrid.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvGiftGrid.adapter = adapter

        binding.btnCloseGiftSheet.setOnClickListener { dismiss() }
        binding.btnRechargeCoins.setOnClickListener {
            Toast.makeText(requireContext(), "Opening coin top-up store...", Toast.LENGTH_SHORT).show()
        }

        setupQuantityChips()
        binding.btnSendGiftAction.setOnClickListener { sendGift() }

        loadCatalogAndBalance()
    }

    private fun setupQuantityChips() {
        val chips = listOf(
            binding.chipQty1 to 1,
            binding.chipQty5 to 5,
            binding.chipQty10 to 10,
            binding.chipQty66 to 66,
            binding.chipQty99 to 99,
        )

        fun selectQty(qty: Int) {
            selectedQuantity = qty
            chips.forEach { (view, q) ->
                val active = q == qty
                view.background = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(),
                    if (active) R.drawable.bg_topup_badge else R.drawable.bg_chip_translucent
                )
            }
            updateSendButton()
        }

        chips.forEach { (view, q) ->
            view.setOnClickListener { selectQty(q) }
        }
        selectQty(1)
    }

    private fun updateSendButton() {
        val gift = selectedGift
        if (gift == null) {
            binding.btnSendGiftAction.text = "SELECT GIFT"
            binding.btnSendGiftAction.isEnabled = false
            return
        }
        binding.btnSendGiftAction.isEnabled = true
        val totalCost = gift.price_coins * selectedQuantity
        binding.btnSendGiftAction.text = "SEND ($totalCost 🪙)"
    }

    private fun loadCatalogAndBalance() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Fetch balance
            try {
                val balance = UnrealStarApiClient.fetchWalletBalance()
                currentCoins = balance.coins
                binding.tvGiftCoinBalance.text = "🪙 %,d Coins".format(currentCoins)
            } catch (_: Exception) {}

            // Fetch catalog
            val res = UnrealStarApiClient.fetchGiftCatalog()
            res.onSuccess { list ->
                gifts.clear()
                if (list.isNotEmpty()) {
                    gifts.addAll(list)
                } else {
                    gifts.addAll(defaultCatalog())
                }
                if (gifts.isNotEmpty() && selectedGift == null) {
                    selectedGift = gifts[0]
                }
                adapter.notifyDataSetChanged()
                updateSendButton()
            }.onFailure {
                gifts.clear()
                gifts.addAll(defaultCatalog())
                if (gifts.isNotEmpty() && selectedGift == null) {
                    selectedGift = gifts[0]
                }
                adapter.notifyDataSetChanged()
                updateSendButton()
            }
        }
    }

    private fun defaultCatalog(): List<GiftCatalogItem> = listOf(
        GiftCatalogItem("gift_rose", "Rose", 1, "standard"),
        GiftCatalogItem("gift_chai", "Chai", 10, "standard"),
        GiftCatalogItem("gift_sparkler", "Diwali Sparkler", 99, "animated"),
        GiftCatalogItem("gift_fireworks", "Fireworks", 99, "animated"),
        GiftCatalogItem("gift_supercar", "Supercar", 999, "luxury"),
        GiftCatalogItem("gift_castle", "Taj Palace", 5000, "luxury")
    )

    private fun sendGift() {
        val gift = selectedGift ?: return
        val totalCost = gift.price_coins * selectedQuantity
        if (currentCoins > 0 && currentCoins < totalCost) {
            Toast.makeText(requireContext(), "Insufficient coins. Please recharge!", Toast.LENGTH_SHORT).show()
            return
        }

        comboSequence++
        val seq = comboSequence

        // Tactical button pop animation
        binding.btnSendGiftAction.animate().cancel()
        binding.btnSendGiftAction.scaleX = 0.88f
        binding.btnSendGiftAction.scaleY = 0.88f
        binding.btnSendGiftAction.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        try {
            binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Exception) {}

        viewLifecycleOwner.lifecycleScope.launch {
            val req = SendGiftRequest(
                room_id = roomId,
                recipient_id = recipientId,
                gift_id = gift.gift_id,
                count = selectedQuantity,
                combo_seq = seq,
                idempotency_key = "gift_${UUID.randomUUID()}"
            )
            val res = UnrealStarApiClient.sendGift(req)
            res.onSuccess { resp ->
                currentCoins = resp.remaining_coins
                binding.tvGiftCoinBalance.text = "🪙 %,d Coins".format(currentCoins)
                onGiftSent(gift, selectedQuantity, seq, resp.remaining_coins)
                Toast.makeText(
                    requireContext(),
                    "✨ Sent ${selectedQuantity}x ${gift.name}!",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Gifting note: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class GiftCardAdapter(
        private val items: List<GiftCatalogItem>,
        private val onSelect: (GiftCatalogItem) -> Unit
    ) : RecyclerView.Adapter<GiftCardAdapter.GiftViewHolder>() {

        inner class GiftViewHolder(val binding: ItemGiftCardBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GiftViewHolder {
            val b = ItemGiftCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return GiftViewHolder(b)
        }

        override fun onBindViewHolder(holder: GiftViewHolder, position: Int) {
            val item = items[position]
            val isSelected = item.gift_id == selectedGift?.gift_id

            holder.binding.tvGiftName.text = item.name
            holder.binding.tvGiftPrice.text = item.price_coins.toString()
            holder.binding.tvGiftEmoji.text = emojiForGift(item.gift_id)

            val stroke = if (isSelected) Color.parseColor("#FFD700") else Color.parseColor("#3D3766")
            val bg = if (isSelected) Color.parseColor("#2F2B4D") else Color.parseColor("#211E38")
            holder.binding.cardGift.strokeColor = stroke
            holder.binding.cardGift.setCardBackgroundColor(ColorStateList.valueOf(bg))
            holder.binding.cardGift.strokeWidth = if (isSelected) 3 else 1

            holder.itemView.setOnClickListener {
                onSelect(item)
            }
        }

        override fun getItemCount() = items.size

        private fun emojiForGift(id: String): String = when (id) {
            "gift_rose" -> "🌹"
            "gift_chai" -> "☕"
            "gift_sparkler" -> "✨"
            "gift_fireworks" -> "🎆"
            "gift_supercar" -> "🏎️"
            "gift_castle" -> "🏰"
            else -> "🎁"
        }
    }

    companion object {
        const val TAG = "GiftBottomSheet"
    }
}
