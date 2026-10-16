package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.unrealstar.live.api.SessionStore
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ActivityWatchLiveBinding
import com.unrealstar.live.databinding.ItemChatMessageBinding
import com.unrealstar.live.models.ChatMessage
import com.unrealstar.live.models.SendGiftRequest
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.UUID

/**
 * Watch screen: join a live room, open the viewer's WebRTC session,
 * render the host stream, live chat, gifting, and in-room minigames.
 */
class WatchLiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchLiveBinding
    private var roomId = 0L
    private var hostId = 0L
    private var viewer: RtcViewer? = null
    private var signalClient: RtcSignalClient? = null
    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWatchLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0)
        val hostNickname = intent.getStringExtra(EXTRA_HOST_NICKNAME)
        if (hostNickname != null) {
            binding.tvHostName.text = hostNickname
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.tvStatus.text = getString(R.string.watch_connecting)
        binding.tvViewers.text = getString(R.string.watch_connecting)

        chatAdapter = ChatAdapter(chatMessages)
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChat.adapter = chatAdapter

        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                addChatMessage(SessionStore.userId, "You", text)
                signalClient?.sendChat(text)
                binding.etChatInput.setText("")
            }
        }

        binding.btnToggleGifts.setOnClickListener {
            sendSampleGift()
        }

        binding.btnToggleWheel.setOnClickListener {
            val dialog = LuckyWheelDialog(roomId = roomId) { remainingCoins ->
                addChatMessage(SessionStore.userId, "System", "🎡 Wheel balance updated: $remainingCoins Coins")
            }
            dialog.show(supportFragmentManager, LuckyWheelDialog.TAG)
        }

        binding.btnFollowHost.setOnClickListener {
            if (hostId <= 0L) return@setOnClickListener
            lifecycleScope.launch {
                val res = UnrealStarApiClient.followUser(hostId)
                res.onSuccess {
                    binding.btnFollowHost.text = "✓ Following"
                    binding.btnFollowHost.alpha = 0.7f
                    Toast.makeText(this@WatchLiveActivity, "Followed ${binding.tvHostName.text}!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@WatchLiveActivity, "Follow note: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }


        lifecycleScope.launch {
            if (!SessionStore.isSignedIn) {
                Toast.makeText(this@WatchLiveActivity, R.string.watch_sign_in_required, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val room = UnrealStarApiClient.joinRoom(roomId).getOrNull()
            if (room == null) {
                Toast.makeText(this@WatchLiveActivity, R.string.watch_join_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            hostId = room.host_id
            binding.tvHostName.text = room.host_nickname
            binding.tvViewers.text = "👁 ${room.viewers_count}"
            addChatMessage(0L, "System", "Welcome to ${room.host_nickname}'s room!")
            startWatching()
        }
    }

    private var hostAPoints = 0L

    private fun addChatMessage(userId: Long, nickname: String, text: String) {
        val position = chatMessages.size
        chatMessages.add(ChatMessage(userId, nickname, text))
        chatAdapter.notifyItemInserted(position)
        binding.rvChat.scrollToPosition(position)

        if (text.contains("🎁") || text.contains("VIP") || text.contains("joined")) {
            showGiftBanner(text)
        }
    }

    private fun showGiftBanner(text: String) {
        binding.tvGiftBanner.text = text
        binding.tvGiftBanner.visibility = android.view.View.VISIBLE
        binding.tvGiftBanner.alpha = 1f
        binding.tvGiftBanner.removeCallbacks(hideBannerRunnable)
        binding.tvGiftBanner.postDelayed(hideBannerRunnable, 3500)
    }

    private val hideBannerRunnable = Runnable {
        binding.tvGiftBanner.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                binding.tvGiftBanner.visibility = android.view.View.GONE
            }
    }

    private fun addPkPoints(points: Long) {
        hostAPoints += points
        binding.tvPkHostAPoints.text = "🔵 Blue: $hostAPoints"
    }

    private fun sendSampleGift() {
        lifecycleScope.launch {
            val giftReq = SendGiftRequest(
                room_id = roomId,
                recipient_id = hostId,
                gift_id = "gift_sparkler",
                count = 1,
                combo_seq = 1,
                idempotency_key = "gift_${UUID.randomUUID()}"
            )
            val result = UnrealStarApiClient.sendGift(giftReq)
            result.onSuccess {
                Toast.makeText(this@WatchLiveActivity, "✨ Gift sent! (Remaining coins: ${it.remaining_coins})", Toast.LENGTH_SHORT).show()
                addChatMessage(SessionStore.userId, "System", "🎁 You sent Diwali Sparkler to host!")
                signalClient?.sendChat("🎁 sent Diwali Sparkler!")
                addPkPoints(99)
                showGiftBanner("✨ Combo x1! Sent Diwali Sparkler (+99 pts)")
            }.onFailure {
                Toast.makeText(this@WatchLiveActivity, "Gifting note: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startWatching() {
        try {
            viewer = RtcViewer(application, object : RtcViewerListener {
                override fun onHostIce(candidate: IceCandidate) {
                    signalClient?.sendSignal(
                        hostId,
                        "ice",
                        "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}",
                    )
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    runOnUiThread {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                binding.tvStatus.text = getString(R.string.watch_connected)
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                binding.tvStatus.text = getString(R.string.watch_stream_error)
                            }
                            else -> {}
                        }
                    }
                }

                override fun onVideoReady() {
                    runOnUiThread {
                        binding.tvStatus.text = getString(R.string.watch_connected)
                    }
                }

                override fun onViewerError(where: String, message: String) {
                    android.util.Log.w("UnrealStarRtc", "viewer $where: $message")
                }
            })
            viewer?.init()
            viewer?.attachRenderer(binding.renderer)

            val client = RtcSignalClient(roomId, object : RtcSignalClient.Listener {
                override fun onConnected() {
                    signalClient?.sendJoin()
                    signalClient?.sendSignal(hostId, "request", "{}")
                }

                override fun onRoomState(viewers: Long) {
                    runOnUiThread { binding.tvViewers.text = "👁 $viewers" }
                }

                override fun onViewerCount(viewers: Long) {
                    runOnUiThread { binding.tvViewers.text = "👁 $viewers" }
                }

                override fun onChat(fromUserId: Long, nickname: String, text: String) {
                    runOnUiThread {
                        addChatMessage(fromUserId, nickname, text)
                    }
                }

                override fun onRtcSignal(fromUserId: Long, kind: String, sdp: String) {
                    val v = viewer ?: return
                    when (kind) {
                        "offer" -> lifecycleScope.launch {
                            try {
                                val answer = v.acceptHostOffer(fromUserId, sdp)
                                signalClient?.sendSignal(fromUserId, "answer", answer.description)
                            } catch (t: Throwable) {
                                android.util.Log.w("UnrealStarRtc", "answer to host $fromUserId failed", t)
                                runOnUiThread {
                                    binding.tvStatus.text = getString(R.string.watch_stream_error)
                                }
                            }
                        }
                        "ice" -> RtcPublisher.parseIce(sdp)?.let { (mid, mline, cand) ->
                            v.addRemoteIce(mid, mline, cand)
                        }
                    }
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        binding.tvStatus.text = getString(R.string.watch_stream_error)
                    }
                }

                override fun onKicked(banned: Boolean) {
                    runOnUiThread {
                        Toast.makeText(
                            this@WatchLiveActivity,
                            if (banned) R.string.watch_banned else R.string.watch_kicked,
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }
            })
            if (!client.connect()) {
                binding.tvStatus.text = getString(R.string.watch_join_failed)
                return
            }
            signalClient = client
            client.sendJoin()
            client.sendSignal(hostId, "request", "{}")
        } catch (t: Throwable) {
            android.util.Log.w("UnrealStarRtc", "watch unavailable", t)
            binding.tvStatus.text = getString(R.string.watch_stream_error)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { signalClient?.close() } catch (_: Exception) {}
        signalClient = null
        viewer?.close()
        viewer = null
        val rid = roomId
        if (rid > 0) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { UnrealStarApiClient.leaveRoom(rid) }
            }
        }
    }

    inner class ChatAdapter(private val list: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

        inner class ChatViewHolder(val b: ItemChatMessageBinding) :
            RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val b = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ChatViewHolder(b)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val msg = list[position]
            if (!msg.familyBadge.isNullOrEmpty()) {
                holder.b.tvFamilyTag.text = msg.familyBadge
                holder.b.tvFamilyTag.visibility = View.VISIBLE
            } else {
                holder.b.tvFamilyTag.visibility = View.GONE
            }
            if (msg.vipLevel > 0) {
                holder.b.tvVipTag.text = "VIP ${msg.vipLevel}"
                holder.b.tvVipTag.visibility = View.VISIBLE
            } else {
                holder.b.tvVipTag.visibility = View.GONE
            }
            holder.b.tvSender.text = "${msg.nickname}:"
            holder.b.tvMessage.text = msg.message
        }

        override fun getItemCount() = list.size
    }

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_HOST_NICKNAME = "host_nickname"

        fun start(activity: android.content.Context, roomId: Long, hostNickname: String) {
            activity.startActivity(
                android.content.Intent(activity, WatchLiveActivity::class.java)
                    .putExtra(EXTRA_ROOM_ID, roomId)
                    .putExtra(EXTRA_HOST_NICKNAME, hostNickname),
            )
        }
    }
}
