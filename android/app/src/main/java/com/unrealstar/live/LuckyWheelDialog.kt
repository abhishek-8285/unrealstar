package com.unrealstar.live

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.DialogLuckyWheelBinding
import com.unrealstar.live.models.SpinWheelRequest
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.util.UUID

class LuckyWheelDialog(
    private val roomId: Long = 1L,
    private val onBalanceUpdated: (Long) -> Unit = {}
) : BottomSheetDialogFragment() {

    private var _binding: DialogLuckyWheelBinding? = null
    private val binding get() = _binding!!

    private var selectedBet: Long = 10L
    private var isSpinning: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLuckyWheelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        refreshBalance()
        setupBetSelectors()

        binding.btnCloseWheel.setOnClickListener {
            dismiss()
        }

        binding.btnSpinWheelAction.setOnClickListener {
            if (!isSpinning) {
                performSpin()
            }
        }
    }

    private fun refreshBalance() {
        lifecycleScope.launch {
            try {
                val balance = UnrealStarApiClient.fetchWalletBalance()
                binding.tvWheelCoinsBalance.text = "🪙 %,d Coins".format(balance.coins)
            } catch (e: Exception) {
                binding.tvWheelCoinsBalance.text = "🪙 — Coins (offline)"
            }
        }
    }

    private fun setupBetSelectors() {
        updateBetSelection(10L)

        binding.btnBet10.setOnClickListener { updateBetSelection(10L) }
        binding.btnBet50.setOnClickListener { updateBetSelection(50L) }
        binding.btnBet100.setOnClickListener { updateBetSelection(100L) }
        binding.btnBet500.setOnClickListener { updateBetSelection(500L) }
    }

    private fun updateBetSelection(bet: Long) {
        if (isSpinning) return
        selectedBet = bet
        binding.btnSpinWheelAction.text = "SPIN WHEEL ($selectedBet COINS)"

        val activeColor = Color.parseColor("#FFD700")
        val inactiveColor = Color.parseColor("#80FFFFFF")

        binding.btnBet10.strokeColor = android.content.res.ColorStateList.valueOf(if (bet == 10L) activeColor else inactiveColor)
        binding.btnBet50.strokeColor = android.content.res.ColorStateList.valueOf(if (bet == 50L) activeColor else inactiveColor)
        binding.btnBet100.strokeColor = android.content.res.ColorStateList.valueOf(if (bet == 100L) activeColor else inactiveColor)
        binding.btnBet500.strokeColor = android.content.res.ColorStateList.valueOf(if (bet == 500L) activeColor else inactiveColor)
    }

    private fun performSpin() {
        isSpinning = true
        binding.btnSpinWheelAction.isEnabled = false
        binding.btnSpinWheelAction.text = "SPINNING..."
        binding.tvSpinResult.text = "Good luck! The wheel is spinning..."
        binding.tvSpinResult.setTextColor(Color.parseColor("#FFE082"))

        lifecycleScope.launch {
            val req = SpinWheelRequest(
                room_id = roomId,
                wheel_id = "wheel_classic",
                coins_bet = selectedBet,
                idempotency_key = "spin_${UUID.randomUUID()}"
            )

            val res = UnrealStarApiClient.spinWheel(req)
            res.onSuccess { spinRes ->
                binding.wheelView.spinToAngle(spinRes.target_angle_deg) {
                    isSpinning = false
                    binding.btnSpinWheelAction.isEnabled = true
                    binding.btnSpinWheelAction.text = "SPIN WHEEL ($selectedBet COINS)"

                    val resultText = if (spinRes.multiplier > 0) {
                        "🎉 WINNER! ${spinRes.tier} (${spinRes.multiplier}x)! Won ${spinRes.reward_coins} Coins!"
                    } else {
                        "😢 Better luck next time! (${spinRes.tier})"
                    }
                    binding.tvSpinResult.text = resultText
                    binding.tvSpinResult.setTextColor(
                        if (spinRes.multiplier > 0) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
                    )

                    binding.tvWheelCoinsBalance.text = "🪙 %,d Coins".format(spinRes.remaining_coins)
                    onBalanceUpdated(spinRes.remaining_coins)
                }
            }.onFailure { err ->
                isSpinning = false
                binding.btnSpinWheelAction.isEnabled = true
                binding.btnSpinWheelAction.text = "SPIN WHEEL ($selectedBet COINS)"
                binding.tvSpinResult.text = "Spin failed: ${err.message}"
                binding.tvSpinResult.setTextColor(Color.parseColor("#FF5252"))
                Toast.makeText(context, "Spin error: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LuckyWheelDialog"
    }
}
