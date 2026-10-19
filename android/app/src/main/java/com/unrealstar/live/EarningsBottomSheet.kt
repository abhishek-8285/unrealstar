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

    private var currentDiamonds: Long = 0L
    /** Survives failure so timeout retaps reuse one idempotency key. */
    private var payoutKey: String? = null

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
                Toast.makeText(requireContext(), getString(R.string.earn_upi_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnWithdrawUpi.isEnabled = false
            binding.btnWithdrawUpi.text = getString(R.string.earn_processing_cashout)
            binding.btnWithdrawUpi.alpha = 0.7f

            // Key survives failure: a timeout has unknown outcome, so the
            // retap reuses the same key and the server dedupes. Cleared only
            // after a confirmed payout.
            val key = payoutKey ?: java.util.UUID.randomUUID().toString().also { payoutKey = it }
            lifecycleScope.launch {
                val res = UnrealStarApiClient.requestPayout(diamondsAmount = 1000, idempotencyKey = key)
                res.onSuccess { record ->
                    payoutKey = null
                    _binding?.let { b ->
                        b.btnWithdrawUpi.isEnabled = true
                        b.btnWithdrawUpi.text = getString(R.string.earn_payout_initiated)
                        b.tvPayoutStatusMsg.text = getString(R.string.earn_payout_transferred, record.net_inr, upiId, record.payout_id.take(18))
                        b.tvPayoutStatusMsg.setTextColor(requireContext().getColor(R.color.disclaimer_green))
                    }
                    Toast.makeText(requireContext(), getString(R.string.earn_cashout_ok, record.payout_id), Toast.LENGTH_LONG).show()
                    onPayoutSuccess?.invoke()
                }.onFailure { err ->
                    _binding?.let { b ->
                        b.btnWithdrawUpi.isEnabled = true
                        b.btnWithdrawUpi.text = getString(R.string.withdraw_upi)
                        b.btnWithdrawUpi.alpha = 1.0f
                    }
                    Toast.makeText(requireContext(), getString(R.string.earn_payout_note, err.message), Toast.LENGTH_SHORT).show()
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
