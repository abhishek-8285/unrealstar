package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemCoinPackageBinding
import com.unrealstar.live.databinding.SheetCoinStoreBinding
import com.unrealstar.live.models.CoinPackage
import kotlinx.coroutines.launch
import java.util.Locale

class CoinStoreBottomSheet(
    private val onRechargeSuccess: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetCoinStoreBinding? = null
    private val binding get() = _binding!!

    private val packageAdapter = CoinPackageAdapter { pkg ->
        performPurchase(pkg)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCoinStoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseCoinStore.setOnClickListener { dismiss() }

        binding.rvCoinPackages.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvCoinPackages.adapter = packageAdapter

        loadWalletBalance()
        loadPackages()
    }

    private fun loadWalletBalance() {
        lifecycleScope.launch {
            try {
                val balance = UnrealStarApiClient.fetchWalletBalance()
                binding.tvCurrentCoins.text = String.format(Locale.US, "%,d Coins", balance.coins)
            } catch (_: Exception) {
                binding.tvCurrentCoins.text = getString(R.string.current_coins)
            }
        }
    }

    private fun loadPackages() {
        lifecycleScope.launch {
            val res = UnrealStarApiClient.fetchCoinPackages().getOrNull()
            if (!res.isNullOrEmpty()) {
                packageAdapter.submit(res)
            } else {
                // Fallback default packages matching backend
                packageAdapter.submit(
                    listOf(
                        CoinPackage("pkg_70", 70, 0, 49),
                        CoinPackage("pkg_150", 150, 15, 99),
                        CoinPackage("pkg_500", 500, 75, 299),
                        CoinPackage("pkg_2000", 2000, 400, 999)
                    )
                )
            }
        }
    }

    private fun performPurchase(pkg: CoinPackage) {
        lifecycleScope.launch {
            Toast.makeText(requireContext(), getString(R.string.coin_connecting, pkg.price_inr), Toast.LENGTH_SHORT).show()
            val orderRes = UnrealStarApiClient.createWalletOrder(pkg.package_id)
            orderRes.onSuccess { order ->
                // Simulate instant payment settlement webhook
                val webhookRes = UnrealStarApiClient.simulatePaymentSuccess(order.order_id)
                webhookRes.onSuccess {
                    val credited = (pkg.coins + pkg.bonus_coins).toInt()
                    Toast.makeText(requireContext(), resources.getQuantityString(R.plurals.coin_credited, credited, credited), Toast.LENGTH_LONG).show()
                    loadWalletBalance()
                    onRechargeSuccess?.invoke()
                }.onFailure {
                    // Settlement failed: coins were NOT credited. Never claim
                    // success — direct the user to retry; the order stays
                    // pending server-side and a retry settles it idempotently.
                    Toast.makeText(requireContext(), getString(R.string.coin_not_settled, order.order_id), Toast.LENGTH_LONG).show()
                }
            }.onFailure { err ->
                Toast.makeText(requireContext(), getString(R.string.coin_purchase_error, err.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CoinStoreBottomSheet"
    }
}

class CoinPackageAdapter(
    private val onPackageClick: (CoinPackage) -> Unit
) : RecyclerView.Adapter<CoinPackageAdapter.CoinPackageViewHolder>() {

    private val items = mutableListOf<CoinPackage>()

    fun submit(newItems: List<CoinPackage>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        notifyListReplaced(oldSize, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoinPackageViewHolder {
        val binding = ItemCoinPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CoinPackageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CoinPackageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class CoinPackageViewHolder(private val binding: ItemCoinPackageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CoinPackage) {
            binding.tvCoinsAmount.text = String.format(Locale.US, "%,d", item.coins)
            binding.btnBuyPackage.text = binding.root.context.getString(R.string.coin_price_rupee, item.price_inr)

            if (item.bonus_coins > 0) {
                binding.tvCoinBonusTag.isVisible = true
                binding.tvCoinBonusTag.text = binding.root.context.getString(R.string.coin_bonus_fmt, item.bonus_coins)
            } else {
                binding.tvCoinBonusTag.isVisible = false
            }

            binding.btnBuyPackage.setOnClickListener { onPackageClick(item) }
            binding.root.setOnClickListener { onPackageClick(item) }
        }
    }
}
