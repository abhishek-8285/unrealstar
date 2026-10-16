package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.SheetRedPacketBinding
import com.unrealstar.live.models.ActiveRedPacketItem
import kotlinx.coroutines.launch

class RedPacketBottomSheet(
    private val roomId: Long = 101,
    private val onCoinsClaimed: ((Long) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetRedPacketBinding? = null
    private val binding get() = _binding!!

    private var activePacket: ActiveRedPacketItem? = null
    private var selectedCoins: Long = 100
    private var selectedSplits: Long = 5

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetRedPacketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.tabClaim.setOnClickListener {
            showClaimView(true)
        }

        binding.tabDrop.setOnClickListener {
            showClaimView(false)
        }

        setupDropSelectors()
        loadActivePackets()
    }

    private fun showClaimView(isClaim: Boolean) {
        binding.layoutClaimView.isVisible = isClaim
        binding.layoutDropView.isVisible = !isClaim
        binding.tabClaim.setBackgroundResource(if (isClaim) R.drawable.bg_chip_active else R.drawable.bg_chip_translucent)
        binding.tabDrop.setBackgroundResource(if (!isClaim) R.drawable.bg_chip_active else R.drawable.bg_chip_translucent)
    }

    private fun setupDropSelectors() {
        val coinChips = listOf(
            binding.chipCoins50 to 50L,
            binding.chipCoins100 to 100L,
            binding.chipCoins500 to 500L,
            binding.chipCoins1000 to 1000L
        )

        coinChips.forEach { (chip, amount) ->
            chip.setOnClickListener {
                selectedCoins = amount
                coinChips.forEach { (c, amt) ->
                    c.setBackgroundResource(if (amt == amount) R.drawable.bg_chip_active else R.drawable.bg_chip_translucent)
                }
            }
        }

        val splitChips = listOf(
            binding.chipSplits5 to 5L,
            binding.chipSplits10 to 10L,
            binding.chipSplits20 to 20L
        )

        splitChips.forEach { (chip, count) ->
            chip.setOnClickListener {
                selectedSplits = count
                splitChips.forEach { (c, cnt) ->
                    c.setBackgroundResource(if (cnt == count) R.drawable.bg_chip_active else R.drawable.bg_chip_translucent)
                }
            }
        }

        binding.btnDropPacket.setOnClickListener {
            dropPacket()
        }
    }

    private fun loadActivePackets() {
        lifecycleScope.launch {
            val res = UnrealStarApiClient.fetchActiveRedPackets(roomId)
            res.onSuccess { list ->
                val packet = list.firstOrNull { !it.already_claimed }
                if (packet != null) {
                    activePacket = packet
                    binding.tvDropperInfo.text = "${packet.dropper_nickname} dropped a ${packet.total_coins} Coin Packet!"
                    binding.tvSplitsRemaining.text = "${packet.remaining_splits} of ${packet.split_count} splits remaining • Tap Open to claim!"
                    binding.btnClaimPacket.isEnabled = true
                    binding.btnClaimPacket.text = "✨ OPEN & CLAIM ✨"
                    binding.btnClaimPacket.setOnClickListener {
                        claimPacket(packet.packet_id)
                    }
                    showClaimView(true)
                } else {
                    binding.tvDropperInfo.text = "No active Red Packets in this room"
                    binding.tvSplitsRemaining.text = "Be the hero! Drop a packet to shower coins on viewers 🧧"
                    binding.btnClaimPacket.isEnabled = false
                    binding.btnClaimPacket.text = "No Packet Active"
                    // Switch to drop view so user can send one
                    showClaimView(false)
                }
            }.onFailure {
                showClaimView(false)
            }
        }
    }

    private fun claimPacket(packetId: String) {
        binding.btnClaimPacket.isEnabled = false
        binding.btnClaimPacket.text = "Opening..."

        lifecycleScope.launch {
            val res = UnrealStarApiClient.claimRedPacket(packetId)
            res.onSuccess { claimRes ->
                binding.btnClaimPacket.text = "✓ Claimed!"
                binding.tvClaimOutcome.isVisible = true
                binding.tvClaimOutcome.text = "🎉 You grabbed +${claimRes.coins_claimed} Coins! (${claimRes.remaining_splits} splits left)"
                Toast.makeText(requireContext(), "🎉 Received ${claimRes.coins_claimed} Coins!", Toast.LENGTH_SHORT).show()
                onCoinsClaimed?.invoke(claimRes.coins_claimed)
            }.onFailure { err ->
                binding.btnClaimPacket.isEnabled = true
                binding.btnClaimPacket.text = "✨ OPEN & CLAIM ✨"
                Toast.makeText(requireContext(), "Claim failed: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dropPacket() {
        binding.btnDropPacket.isEnabled = false
        binding.btnDropPacket.text = "Dropping..."

        lifecycleScope.launch {
            val res = UnrealStarApiClient.dropRedPacket(roomId, selectedCoins, selectedSplits)
            binding.btnDropPacket.isEnabled = true
            binding.btnDropPacket.text = "🧧 Drop Red Packet into Room"

            res.onSuccess {
                Toast.makeText(requireContext(), "🧧 Dropped $selectedCoins Coins ($selectedSplits splits) into room!", Toast.LENGTH_LONG).show()
                loadActivePackets()
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Drop failed: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "RedPacketBottomSheet"
    }
}
