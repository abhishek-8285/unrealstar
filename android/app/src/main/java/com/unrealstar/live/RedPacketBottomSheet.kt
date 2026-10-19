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
                    binding.tvDropperInfo.text = getString(R.string.redpacket_dropper_info, packet.dropper_nickname, packet.total_coins)
                    binding.tvSplitsRemaining.text = resources.getQuantityString(R.plurals.redpacket_splits_remaining, packet.split_count.toInt(), packet.remaining_splits, packet.split_count)
                    binding.btnClaimPacket.isEnabled = true
                    binding.btnClaimPacket.text = getString(R.string.claim_packet)
                    binding.btnClaimPacket.setOnClickListener {
                        claimPacket(packet.packet_id)
                    }
                    showClaimView(true)
                } else {
                    binding.tvDropperInfo.text = getString(R.string.redpacket_none_active)
                    binding.tvSplitsRemaining.text = getString(R.string.redpacket_promo)
                    binding.btnClaimPacket.isEnabled = false
                    binding.btnClaimPacket.text = getString(R.string.redpacket_no_packet_active)
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
        binding.btnClaimPacket.text = getString(R.string.redpacket_opening)

        lifecycleScope.launch {
            val res = UnrealStarApiClient.claimRedPacket(packetId)
            res.onSuccess { claimRes ->
                binding.btnClaimPacket.text = getString(R.string.redpacket_claimed)
                binding.tvClaimOutcome.isVisible = true
                binding.tvClaimOutcome.text = resources.getQuantityString(R.plurals.redpacket_claim_outcome, claimRes.remaining_splits.toInt(), claimRes.coins_claimed, claimRes.remaining_splits)
                Toast.makeText(requireContext(), resources.getQuantityString(R.plurals.redpacket_received, claimRes.coins_claimed.toInt(), claimRes.coins_claimed), Toast.LENGTH_SHORT).show()
                onCoinsClaimed?.invoke(claimRes.coins_claimed)
            }.onFailure { err ->
                binding.btnClaimPacket.isEnabled = true
                binding.btnClaimPacket.text = getString(R.string.claim_packet)
                Toast.makeText(requireContext(), getString(R.string.redpacket_claim_failed, err.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Survives failure so timeout retaps reuse one idempotency key. */
    private var dropKey: String? = null

    private fun dropPacket() {
        if (roomId <= 0L) {
            Toast.makeText(requireContext(), getString(R.string.watch_joining), Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCoins <= 0L || selectedSplits !in 1L..100L) {
            Toast.makeText(requireContext(), getString(R.string.redpacket_pick_valid), Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnDropPacket.isEnabled = false
        binding.btnDropPacket.text = getString(R.string.redpacket_dropping)

        val key = dropKey ?: java.util.UUID.randomUUID().toString().also { dropKey = it }
        lifecycleScope.launch {
            val res = try {
                UnrealStarApiClient.dropRedPacket(roomId, selectedCoins, selectedSplits, key)
            } catch (t: Throwable) {
                // require() validation above should prevent this; belt-and-braces.
                Result.failure<com.unrealstar.live.models.RedPacketDropResponse>(t)
            }
            _binding?.let { b ->
                b.btnDropPacket.isEnabled = true
                b.btnDropPacket.text = getString(R.string.drop_packet)
            }

            res.onSuccess {
                dropKey = null
                Toast.makeText(requireContext(), resources.getQuantityString(R.plurals.redpacket_dropped, selectedCoins.toInt(), selectedCoins, selectedSplits), Toast.LENGTH_LONG).show()
                loadActivePackets()
            }.onFailure { err ->
                Toast.makeText(requireContext(), getString(R.string.redpacket_drop_failed, err.message), Toast.LENGTH_SHORT).show()
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
