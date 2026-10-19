package com.unrealstar.live

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.toColorInt
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
    /** In-flight guard: one request at a time, one key per user intent. */
    private var giftInFlight: Boolean = false

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
            val prev = gifts.indexOfFirst { it.gift_id == selectedGift?.gift_id }
            selectedGift = item
            val next = gifts.indexOfFirst { it.gift_id == item.gift_id }
            if (prev >= 0) adapter.notifyItemChanged(prev)
            if (next >= 0 && next != prev) adapter.notifyItemChanged(next)
            updateSendButton()
        }
        binding.rvGiftGrid.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvGiftGrid.adapter = adapter

        binding.btnCloseGiftSheet.setOnClickListener { dismiss() }
        binding.btnRechargeCoins.setOnClickListener {
            val store = CoinStoreBottomSheet {
                loadCatalogAndBalance()
            }
            store.show(childFragmentManager, CoinStoreBottomSheet.TAG)
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
            binding.btnSendGiftAction.text = getString(R.string.gift_select_action)
            binding.btnSendGiftAction.isEnabled = false
            return
        }
        binding.btnSendGiftAction.isEnabled = true
        val totalCost = gift.price_coins * selectedQuantity
        binding.btnSendGiftAction.text = getString(R.string.gift_send_action_total, totalCost)
    }

    private fun loadCatalogAndBalance() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Fetch balance
            try {
                val balance = UnrealStarApiClient.fetchWalletBalance()
                currentCoins = balance.coins
                binding.tvGiftCoinBalance.text = resources.getQuantityString(R.plurals.coin_balance_fmt, currentCoins.toInt(), currentCoins)
            } catch (_: Exception) {}

            // Fetch catalog
            val res = UnrealStarApiClient.fetchGiftCatalog()
            res.onSuccess { list ->
                val oldSize = gifts.size
                gifts.clear()
                if (list.isNotEmpty()) {
                    gifts.addAll(list)
                } else {
                    gifts.addAll(defaultCatalog())
                }
                if (gifts.isNotEmpty() && selectedGift == null) {
                    selectedGift = gifts[0]
                }
                adapter.notifyListReplaced(oldSize, gifts.size)
                updateSendButton()
            }.onFailure {
                val oldSize = gifts.size
                gifts.clear()
                gifts.addAll(defaultCatalog())
                if (gifts.isNotEmpty() && selectedGift == null) {
                    selectedGift = gifts[0]
                }
                adapter.notifyListReplaced(oldSize, gifts.size)
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
        // Zero means "no coins" (or balance not loaded yet) — never spend blind.
        if (currentCoins < totalCost) {
            Toast.makeText(requireContext(), getString(R.string.gift_insufficient), Toast.LENGTH_SHORT).show()
            return
        }
        // One key per user intent; transport retries reuse it so the server
        // ledger can dedupe. Double-tap while in flight is ignored.
        if (giftInFlight) return
        giftInFlight = true
        val key = "gift_${UUID.randomUUID()}"
        comboSequence++
        val seq = comboSequence

        // Tactical button pop animation
        _binding?.let { b ->
            b.btnSendGiftAction.animate().cancel()
            b.btnSendGiftAction.scaleX = 0.88f
            b.btnSendGiftAction.scaleY = 0.88f
            b.btnSendGiftAction.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            b.btnSendGiftAction.isEnabled = false
        }
        try {
            binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Exception) {}

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val req = SendGiftRequest(
                    room_id = roomId,
                    recipient_id = recipientId,
                    gift_id = gift.gift_id,
                    count = selectedQuantity,
                    combo_seq = seq,
                    idempotency_key = key
                )
                val res = UnrealStarApiClient.sendGift(req)
                res.onSuccess { resp ->
                    currentCoins = resp.remaining_coins
                    _binding?.let { b ->
                        b.tvGiftCoinBalance.text = resources.getQuantityString(R.plurals.coin_balance_fmt, currentCoins.toInt(), currentCoins)
                    }
                    onGiftSent(gift, selectedQuantity, seq, resp.remaining_coins)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.gift_sent_host, selectedQuantity, gift.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { err ->
                    Toast.makeText(requireContext(), getString(R.string.live_gift_note, err.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                giftInFlight = false
                _binding?.btnSendGiftAction?.isEnabled = true
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
            holder.binding.tvGiftPrice.text = getString(R.string.number_plain, item.price_coins)
            holder.binding.tvGiftEmoji.text = emojiForGift(item.gift_id)

            val stroke = if (isSelected) "#FFD700".toColorInt() else "#3D3766".toColorInt()
            val bg = if (isSelected) "#2F2B4D".toColorInt() else "#211E38".toColorInt()
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
