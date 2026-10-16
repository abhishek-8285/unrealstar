package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.databinding.SheetCreateOptionsBinding

class CreateOptionsBottomSheet(
    private val onPostSelected: () -> Unit,
    private val onShortsSelected: () -> Unit,
    private val onLiveSelected: () -> Unit,
    private val onPoemSelected: (() -> Unit)? = null,
    private val onAlbumSelected: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetCreateOptionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCreateOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.cardOptionPost.setOnClickListener {
            dismiss()
            onPostSelected()
        }

        binding.cardOptionShorts.setOnClickListener {
            dismiss()
            onShortsSelected()
        }

        binding.cardOptionLive.setOnClickListener {
            dismiss()
            onLiveSelected()
        }

        binding.cardOptionPoem.setOnClickListener {
            dismiss()
            onPoemSelected?.invoke()
        }

        binding.cardOptionAlbum.setOnClickListener {
            dismiss()
            onAlbumSelected?.invoke()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CreateOptionsBottomSheet"
    }
}
