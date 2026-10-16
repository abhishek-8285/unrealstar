package com.unrealstar.live

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.SessionStore
import com.unrealstar.live.databinding.SheetSettingsBinding
import java.io.File
import java.util.Locale

class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvSettingsUserId.text = SessionStore.userId.toString()
        updateCacheSize()

        binding.btnCloseSettings.setOnClickListener { dismiss() }

        binding.switchPushNotifications.setOnCheckedChangeListener { _, isChecked ->
            val status = if (isChecked) "Enabled" else "Muted"
            Toast.makeText(requireContext(), "Push Notifications $status", Toast.LENGTH_SHORT).show()
        }

        binding.rowClearCache.setOnClickListener {
            clearAppCache()
            Toast.makeText(requireContext(), "🧹 Media Cache Cleared Successfully", Toast.LENGTH_SHORT).show()
            updateCacheSize()
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "🛡️ CamStar & Trendo Safety: Zero harassment tolerance, end-to-end encrypted chats, 24/7 stream moderation.",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnLogout.setOnClickListener {
            dismiss()
            startActivity(Intent(requireContext(), OtpLoginActivity::class.java))
        }
    }

    private fun updateCacheSize() {
        val cacheBytes = calculateCacheSize(requireContext().cacheDir)
        val mb = cacheBytes / (1024.0 * 1024.0)
        binding.tvCacheSize.text = String.format(Locale.US, "%.1f MB", if (mb < 0.1) 0.0 else mb)
    }

    private fun calculateCacheSize(dir: File): Long {
        var size: Long = 0
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateCacheSize(file) else file.length()
        }
        return size
    }

    private fun clearAppCache() {
        try {
            requireContext().cacheDir.deleteRecursively()
            requireContext().cacheDir.mkdirs()
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
