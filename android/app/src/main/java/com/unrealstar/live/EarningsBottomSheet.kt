package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.SheetEarningsBinding
import kotlinx.coroutines.launch
import java.util.Locale

class EarningsBottomSheet(
    private val onPayoutSuccess: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetEarningsBinding? = null
    private val binding get() = _binding!!

    private var currentDiamonds: Long = 7_500_000L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetEarningsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseEarnings.setOnClickListener { dismiss() }

        loadPreview()
        setupWithdrawAction()
    }

    private fun loadPreview() {
        lifecycleScope.launch {
            val preview = UnrealStarApiClient.fetchPayoutPreview().getOrNull()
            if (preview != null) {
                currentDiamonds = preview.diamonds_amount
                binding.tvEarningsDiamonds.text = String.format(Locale.US, "%,d", preview.diamonds_amount)
                binding.tvGrossInr.text = String.format(Locale.US, "₹%,d", preview.gross_inr)
                binding.tvTdsInr.text = String.format(Locale.US, "- ₹%,d", preview.tds_194q_inr)
                binding.tvNetPayoutInr.text = String.format(Locale.US, "₹%,d", preview.net_payout_inr)
            }
        }
    }

    private fun setupWithdrawAction() {
        binding.btnWithdrawUpi.setOnClickListener {
            val upiId = binding.etUpiAddress.text.toString().trim()
            if (!upiId.contains("@") || upiId.length < 5) {
                Toast.makeText(requireContext(), "Please enter a valid UPI ID (e.g. name@bank)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnWithdrawUpi.isEnabled = false
            binding.btnWithdrawUpi.text = "Processing UPI Cashout..."
            binding.btnWithdrawUpi.alpha = 0.7f

            lifecycleScope.launch {
                val res = UnrealStarApiClient.requestPayout(diamondsAmount = 1000)
                res.onSuccess { record ->
                    binding.btnWithdrawUpi.isEnabled = true
                    binding.btnWithdrawUpi.text = "✓ Payout Initiated"
                    binding.tvPayoutStatusMsg.text = "🎉 ₹${record.net_inr} transferred to $upiId (Txn: ${record.payout_id.take(18)}...)"
                    binding.tvPayoutStatusMsg.setTextColor(requireContext().getColor(R.color.disclaimer_green))
                    Toast.makeText(requireContext(), "Instant Cashout Successful! Payout ID: ${record.payout_id}", Toast.LENGTH_LONG).show()
                    onPayoutSuccess?.invoke()
                }.onFailure { err ->
                    binding.btnWithdrawUpi.isEnabled = true
                    binding.btnWithdrawUpi.text = "💸 Instant Cashout to UPI"
                    binding.btnWithdrawUpi.alpha = 1.0f
                    Toast.makeText(requireContext(), "Payout notice: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "EarningsBottomSheet"
    }
}
