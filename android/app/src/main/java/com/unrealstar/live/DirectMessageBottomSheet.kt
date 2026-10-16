package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemDirectMessageBubbleBinding
import com.unrealstar.live.databinding.SheetDirectMessageBinding
import com.unrealstar.live.models.DirectMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DirectMessageBottomSheet(
    private val targetUserId: Long = 1002,
    private val targetNickname: String = "Satya Rajput",
    private val targetAvatarUrl: String = ""
) : BottomSheetDialogFragment() {

    private var _binding: SheetDirectMessageBinding? = null
    private val binding get() = _binding!!

    private val messages = mutableListOf<DirectMessage>()
    private lateinit var adapter: DirectMessageAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetDirectMessageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvHeaderName.text = targetNickname
        Glide.with(this)
            .load(UnrealStarApiClient.localise(targetAvatarUrl))
            .circleCrop()
            .placeholder(R.drawable.bg_live_avatar)
            .into(binding.ivHeaderAvatar)

        binding.btnCloseChat.setOnClickListener {
            dismiss()
        }

        adapter = DirectMessageAdapter(targetAvatarUrl)
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.layoutManager = layoutManager
        binding.rvChatMessages.adapter = adapter

        binding.btnSendMessage.setOnClickListener {
            sendCurrentMessage()
        }

        binding.etMessageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }

        loadConversation()
    }

    private fun loadConversation() {
        lifecycleScope.launch {
            val result = UnrealStarApiClient.fetchConversation(targetUserId)
            result.onSuccess { thread ->
                messages.clear()
                messages.addAll(thread.messages)
                adapter.submit(messages)
                if (messages.isNotEmpty()) {
                    binding.rvChatMessages.scrollToPosition(messages.size - 1)
                }
            }.onFailure {
                Toast.makeText(requireContext(), "Failed to load messages", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendCurrentMessage() {
        val text = binding.etMessageInput.text.toString().trim()
        if (text.isEmpty()) return

        binding.etMessageInput.setText("")
        binding.btnSendMessage.isEnabled = false

        lifecycleScope.launch {
            val res = UnrealStarApiClient.sendDirectMessage(targetUserId, text)
            binding.btnSendMessage.isEnabled = true
            res.onSuccess { msg ->
                messages.add(msg)
                adapter.submit(messages)
                binding.rvChatMessages.smoothScrollToPosition(messages.size - 1)
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Send failed: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DirectMessageBottomSheet"
    }

    private class DirectMessageAdapter(
        private val otherAvatarUrl: String
    ) : RecyclerView.Adapter<DirectMessageAdapter.MsgViewHolder>() {

        private val items = mutableListOf<DirectMessage>()

        fun submit(next: List<DirectMessage>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
            val binding = ItemDirectMessageBubbleBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return MsgViewHolder(binding, otherAvatarUrl)
        }

        override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class MsgViewHolder(
            private val binding: ItemDirectMessageBubbleBinding,
            private val otherAvatarUrl: String
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(msg: DirectMessage) {
                if (msg.is_self) {
                    binding.layoutSelf.isVisible = true
                    binding.layoutOther.isVisible = false
                    binding.tvSelfText.text = msg.text
                    binding.tvSelfTime.text = formatTime(msg.created_at)
                } else {
                    binding.layoutSelf.isVisible = false
                    binding.layoutOther.isVisible = true
                    binding.tvOtherText.text = msg.text
                    binding.tvOtherTime.text = formatTime(msg.created_at)
                    Glide.with(binding.ivOtherAvatar)
                        .load(UnrealStarApiClient.localise(otherAvatarUrl))
                        .circleCrop()
                        .placeholder(R.drawable.bg_live_avatar)
                        .into(binding.ivOtherAvatar)
                }
            }

            private fun formatTime(raw: String): String = try {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                parser.timeZone = TimeZone.getTimeZone("UTC")
                val date: Date = parser.parse(raw.substringBefore('.').substringBefore('+'))!!
                SimpleDateFormat("HH:mm", Locale.US).format(date)
            } catch (e: Exception) {
                "now"
            }
        }
    }
}
