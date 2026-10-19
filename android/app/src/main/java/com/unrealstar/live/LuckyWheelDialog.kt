package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.DialogLuckyWheelBinding
import com.unrealstar.live.models.SpinWheelRequest
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.util.UUID

class LuckyWheelDialog : BottomSheetDialogFragment() {

    private var _binding: DialogLuckyWheelBinding? = null
    private val binding get() = _binding!!

    private val roomId: Long by lazy {
        arguments?.getLong(ARG_ROOM_ID, 1L) ?: 1L
    }

    companion object {
        const val TAG = "LuckyWheelDialog"
        const val RESULT_KEY = "lucky_wheel_result"
        const val RESULT_COINS = "remaining_coins"
        private const val ARG_ROOM_ID = "room_id"

        fun newInstance(roomId: Long): LuckyWheelDialog =
            LuckyWheelDialog().apply {
                arguments = android.os.Bundle().apply {
                    putLong(ARG_ROOM_ID, roomId)
                }
            }
    }

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
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val balance = UnrealStarApiClient.fetchWalletBalance()
                _binding?.tvWheelCoinsBalance?.text = resources.getQuantityString(R.plurals.wheel_coins_balance_fmt, balance.coins.toInt(), balance.coins)
            } catch (e: Exception) {
                _binding?.tvWheelCoinsBalance?.text = getString(R.string.wheel_coins_offline)
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
        binding.btnSpinWheelAction.text = getString(R.string.spin_wheel_action_bet, selectedBet)

        val activeColor = "#FFD700".toColorInt()
        val inactiveColor = "#80FFFFFF".toColorInt()

        binding.btnBet10.strokeColor = android.content.res.ColorStateList.valueOf(if (bet == 10L) activeColor else inactiveColor)
        binding.btnBet50.strokeColor = android.content.res.ColorStateList.valueOf(if (bet == 50L) activeColor else inactiveColor)
        binding.btnBet100.strokeColor = android.content.res.ColorStateList.valueOf(if (bet == 100L) activeColor else inactiveColor)
        binding.btnBet500.strokeColor = android.content.res.ColorStateList.valueOf(if (bet == 500L) activeColor else inactiveColor)
    }

    private fun performSpin() {
        val b = _binding ?: return
        isSpinning = true
        isCancelable = false
        b.btnSpinWheelAction.isEnabled = false
        b.btnSpinWheelAction.text = getString(R.string.wheel_spinning)
        b.tvSpinResult.text = getString(R.string.wheel_spin_started)
        b.tvSpinResult.setTextColor("#FFE082".toColorInt())

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val req = SpinWheelRequest(
                    room_id = roomId,
                    wheel_id = "wheel_classic",
                    coins_bet = selectedBet,
                    idempotency_key = "spin_${UUID.randomUUID()}"
                )

                val res = kotlinx.coroutines.withTimeout(15_000) {
                    UnrealStarApiClient.spinWheel(req)
                }
                res.onSuccess { spinRes ->
                    val bb = _binding ?: return@launch
                    bb.wheelView.spinToAngle(spinRes.target_angle_deg) {
                        val bbb = _binding ?: return@spinToAngle
                        isSpinning = false
                        isCancelable = true
                        bbb.btnSpinWheelAction.isEnabled = true
                        bbb.btnSpinWheelAction.text = getString(R.string.spin_wheel_action_bet, selectedBet)

                        val resultText = if (spinRes.multiplier > 0) {
                            resources.getQuantityString(R.plurals.wheel_winner, spinRes.reward_coins.toInt(), spinRes.tier, spinRes.multiplier, spinRes.reward_coins)
                        } else {
                            getString(R.string.wheel_loser, spinRes.tier)
                        }
                        bbb.tvSpinResult.text = resultText
                        bbb.tvSpinResult.setTextColor(
                            if (spinRes.multiplier > 0) "#00E676".toColorInt() else "#FF5252".toColorInt()
                        )

                        bbb.tvWheelCoinsBalance.text = resources.getQuantityString(R.plurals.wheel_coins_balance_fmt, spinRes.remaining_coins.toInt(), spinRes.remaining_coins)
                        parentFragmentManager.setFragmentResult(
                            RESULT_KEY,
                            android.os.Bundle().apply {
                                putLong(RESULT_COINS, spinRes.remaining_coins)
                            }
                        )
                        try {
                            bbb.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        } catch (_: Exception) {}
                    }

                }.onFailure { err ->
                    val bb = _binding ?: return@launch
                    isSpinning = false
                    isCancelable = true
                    bb.btnSpinWheelAction.isEnabled = true
                    bb.btnSpinWheelAction.text = getString(R.string.spin_wheel_action_bet, selectedBet)
                    bb.tvSpinResult.text = getString(R.string.wheel_spin_failed, err.message)
                    bb.tvSpinResult.setTextColor("#FF5252".toColorInt())
                    try {
                        Toast.makeText(requireContext(), getString(R.string.wheel_spin_error, err.message), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
            } catch (t: Throwable) {
                // Timeout or cancellation while spinning.
                val bb = _binding ?: return@launch
                isSpinning = false
                isCancelable = true
                bb.btnSpinWheelAction.isEnabled = true
                bb.btnSpinWheelAction.text = getString(R.string.spin_wheel_action_bet, selectedBet)
                bb.tvSpinResult.text = getString(R.string.wheel_spin_timeout)
                bb.tvSpinResult.setTextColor("#FF5252".toColorInt())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
