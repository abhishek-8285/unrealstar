package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.SheetCreatePostBinding
import com.unrealstar.live.models.CreateMomentRequest
import com.unrealstar.live.models.FeedPost
import kotlinx.coroutines.launch

class CreatePostBottomSheet(
    private val initialCaption: String? = null,
    private val initialCover: String? = null,
    private val onPostCreated: ((FeedPost) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetCreatePostBinding? = null
    private val binding get() = _binding!!

    private var selectedDuration = 15
    private val selectedTags = mutableSetOf<String>()
    private var selectedCoverUrl = initialCover ?: "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=600"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCreatePostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClosePost.setOnClickListener {
            dismiss()
        }

        if (!initialCaption.isNullOrEmpty()) {
            binding.etCaption.setText(initialCaption)
        }

        setupCovers()
        setupTags()
        setupDurationSelector()

        binding.btnPublishPost.setOnClickListener {
            val caption = binding.etCaption.text.toString().trim()
            if (caption.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.post_caption_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            publishMoment(caption)
        }
    }

    private fun setupCovers() {
        val coverMap = listOf(
            binding.coverParty to "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=600",
            binding.coverStudio to "https://images.unsplash.com/photo-1598387993441-a364f854c3e1?w=600",
            binding.coverConcert to "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=600",
            binding.coverBollywood to "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=600"
        )

        fun updateCovers(activeUrl: String) {
            selectedCoverUrl = activeUrl
            coverMap.forEach { (view, url) ->
                if (url == activeUrl) {
                    view.setBackgroundResource(R.drawable.bg_chip_active)
                } else {
                    view.setBackgroundResource(R.drawable.bg_chip_translucent)
                }
            }
        }

        coverMap.forEach { (view, url) ->
            view.setOnClickListener {
                updateCovers(url)
            }
        }
    }

    private fun setupTags() {
        val tagViews = listOf(
            binding.tagParty to "#Party",
            binding.tagTrending to "#Trending",
            binding.tagDance to "#Dance",
            binding.tagMusic to "#Music",
            binding.tagLive to "#Live",
            binding.tagBollywood to "#Bollywood"
        )

        tagViews.forEach { (view, tag) ->
            view.setOnClickListener {
                val currentText = binding.etCaption.text.toString()
                if (!currentText.contains(tag)) {
                    val newText = if (currentText.isEmpty()) tag else "$currentText $tag"
                    binding.etCaption.setText(newText)
                    binding.etCaption.setSelection(newText.length)
                    selectedTags.add(tag)
                    view.setBackgroundResource(R.drawable.bg_chip_active)
                }
            }
        }
    }

    private fun setupDurationSelector() {
        val durationChips = listOf(
            binding.chipDur15 to 15,
            binding.chipDur30 to 30,
            binding.chipDur60 to 60
        )

        fun updateChips(activeDuration: Int) {
            selectedDuration = activeDuration
            durationChips.forEach { (chip, duration) ->
                if (duration == activeDuration) {
                    chip.setBackgroundResource(R.drawable.bg_chip_active)
                } else {
                    chip.setBackgroundResource(R.drawable.bg_chip_translucent)
                }
            }
        }

        durationChips.forEach { (chip, duration) ->
            chip.setOnClickListener {
                updateChips(duration)
            }
        }
    }

    private fun publishMoment(caption: String) {
        binding.btnPublishPost.isEnabled = false
        binding.btnPublishPost.text = getString(R.string.publishing_dots)
        binding.btnPublishPost.alpha = 0.7f

        lifecycleScope.launch {
            val req = CreateMomentRequest(
                media_url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                caption = caption,
                duration_secs = selectedDuration,
                cover_url = selectedCoverUrl,
                tags = selectedTags.toList()
            )

            val res = UnrealStarApiClient.createMoment(req)
            res.onSuccess { post ->
                Toast.makeText(requireContext(), getString(R.string.post_published), Toast.LENGTH_SHORT).show()
                onPostCreated?.invoke(post)
                dismiss()
            }.onFailure { err ->
                binding.btnPublishPost.isEnabled = true
                binding.btnPublishPost.text = getString(R.string.publish_moment)
                binding.btnPublishPost.alpha = 1.0f
                Toast.makeText(requireContext(), getString(R.string.post_publish_error, err.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CreatePostBottomSheet"
    }
}
