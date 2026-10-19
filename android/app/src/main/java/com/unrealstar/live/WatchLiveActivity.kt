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

        binding.hud.applyTopSystemInset()
        binding.bottomContainer.applyBottomSystemInset()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0)
        val hostNickname = intent.getStringExtra(EXTRA_HOST_NICKNAME)
        if (hostNickname != null) {
            binding.tvHostName.text = hostNickname
        }
        binding.tvHostName.setOnClickListener {
            // Open the host profile once the real id lands (joinRoom); before
            // that there is nothing meaningful to show yet.
            if (hostId <= 0L) return@setOnClickListener
            ProfileBottomSheet(hostId, binding.tvHostName.text.toString())
                .show(supportFragmentManager, ProfileBottomSheet.TAG)
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.tvViewers.setOnClickListener {
            Toast.makeText(this, getString(R.string.watch_viewers_now, binding.tvViewers.text), Toast.LENGTH_SHORT).show()
        }
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
            // hostId arrives with joinRoom: gifting before that would charge
            // coins to a placeholder recipient.
            if (hostId <= 0L) {
                Toast.makeText(this, getString(R.string.watch_joining), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sheet = GiftBottomSheet(
                roomId = roomId,
                recipientId = hostId
            ) { gift, count, comboSeq, _ ->
                val points = gift.price_coins * count
                addPkPoints(points)
                showGiftBanner("✨ Combo x$comboSeq! Sent ${count}x ${gift.name} (+${points} pts)")
                addChatMessage(SessionStore.userId, "System", "🎁 You sent ${count}x ${gift.name}!")
                signalClient?.sendChat("🎁 Sent ${count}x ${gift.name}!")
            }
            sheet.show(supportFragmentManager, GiftBottomSheet.TAG)
        }


        binding.btnRedPacket.setOnClickListener {
            val sheet = RedPacketBottomSheet(roomId = roomId) { coinsClaimed ->
                addChatMessage(SessionStore.userId, "System", "🧧 You claimed +$coinsClaimed Coins from Red Packet!")
                showGiftBanner("🧧 Claimed +$coinsClaimed Coins from Lucky Shagun Lifafa!")
            }
            sheet.show(supportFragmentManager, RedPacketBottomSheet.TAG)
        }

        binding.btnToggleWheel.setOnClickListener {
            supportFragmentManager.setFragmentResultListener(LuckyWheelDialog.RESULT_KEY, this) { _, bundle ->
                val remainingCoins = bundle.getLong(LuckyWheelDialog.RESULT_COINS, -1L)
                if (remainingCoins >= 0) {
                    addChatMessage(SessionStore.userId, "System", "🎡 Wheel balance updated: $remainingCoins Coins")
                }
            }
            LuckyWheelDialog.newInstance(roomId).show(supportFragmentManager, LuckyWheelDialog.TAG)
        }

        binding.btnFollowHost.setOnClickListener {
            if (hostId <= 0L) return@setOnClickListener
            binding.btnFollowHost.isEnabled = false
            lifecycleScope.launch {
                try {
                    val res = UnrealStarApiClient.followUser(hostId)
                    res.onSuccess {
                        binding.btnFollowHost.text = getString(R.string.watch_following)
                        binding.btnFollowHost.alpha = 0.7f
                        Toast.makeText(this@WatchLiveActivity, getString(R.string.watch_followed, binding.tvHostName.text), Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        binding.btnFollowHost.isEnabled = true
                        Toast.makeText(this@WatchLiveActivity, getString(R.string.watch_follow_note, it.message), Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    // Re-enable only if still unfollowed (success latches).
                    if (binding.btnFollowHost.text != "✓ Following") {
                        try { binding.btnFollowHost.isEnabled = true } catch (_: Exception) {}
                    }
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
            binding.tvViewers.text = getString(R.string.watch_viewers_eye, room.viewers_count)
            addChatMessage(0L, "System", "Welcome to ${room.host_nickname}'s room!")
            // ICE refresh rides the join round-trip so the viewer PC is
            // built with time-limited TURN when the deployment has it.
            // Best-effort: failure keeps the static list.
            try {
                UnrealStarApiClient.fetchIceServers().onSuccess { RtcIce.applyServerEntries(it.servers) }
            } catch (_: Throwable) {}
            // The join was slow: the user may have left already. Never
            // attach a renderer/WS/PC to a dead activity.
            if (!isFinishing && !isDestroyed) {
                startWatching()
            }
        }
    }

    private var hostAPoints = 0L
    /** True once a server PK scoreboard arrived; local tallies stop. */
    private var pkLive: Boolean = false

    /** Single-flight video re-request (ICE recovery). */
    private var iceRecoveryInFlight: Boolean = false

    private val disconnectedRunnable = Runnable {
        requestVideoAgain()
    }

    private fun requestVideoAgain() {
        if (iceRecoveryInFlight || isFinishing || isDestroyed) return
        iceRecoveryInFlight = true
        try {
            signalClient?.sendSignal(hostId, "request", "{}")
        } catch (_: Throwable) {
            iceRecoveryInFlight = false
        }
    }

    private fun addChatMessage(userId: Long, nickname: String, text: String) {
        // Cap the buffer: long popular streams would otherwise grow this
        // forever (memory + scroll churn).
        if (chatMessages.size >= 200) {
            val drop = chatMessages.size - 199
            repeat(drop) { chatMessages.removeAt(0) }
            chatAdapter.notifyItemRangeRemoved(0, drop)
        }
        val position = chatMessages.size
        chatMessages.add(ChatMessage(userId, nickname, text))
        chatAdapter.notifyItemInserted(position)
        binding.rvChat.scrollToPosition(position)
        // NOTE: no substring heuristics here. Banners fire only from explicit
        // showGiftBanner calls at typed gift/join/claim sites — any user
        // typing "VIP" or "joined" must never pop a gift banner.
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
        // Optimistic local tally only while no battle is live. Once the
        // server runs a PK, onPkStateChanged overwrites both sides with
        // authoritative totals (which already include our gifts).
        if (pkLive) return
        hostAPoints += points
        binding.tvPkHostAPoints.text = getString(R.string.watch_pk_blue, hostAPoints)
    }

    /** Server-authoritative PK scoreboard: replaces any local tally. */
    private fun renderPkScore(pkId: String, pointsA: Long, pointsB: Long) {
        pkLive = true
        hostAPoints = pointsA
        binding.tvPkHostAPoints.text = getString(R.string.watch_pk_blue, pointsA)
        binding.tvPkHostBPoints.text = getString(R.string.watch_pk_red, pointsB)
        binding.tvPkStatus.text = getString(R.string.watch_pk_live_badge)
        addChatMessage(0L, "System", getString(R.string.watch_pk_chat, pointsA, pointsB))
    }

    private var sampleGiftInFlight: Boolean = false

    private fun sendSampleGift() {
        if (sampleGiftInFlight) return
        sampleGiftInFlight = true
        val key = "gift_${UUID.randomUUID()}"
        lifecycleScope.launch {
            try {
                val giftReq = SendGiftRequest(
                    room_id = roomId,
                    recipient_id = hostId,
                    gift_id = "gift_sparkler",
                    count = 1,
                    combo_seq = 1,
                    idempotency_key = key
                )
                val result = UnrealStarApiClient.sendGift(giftReq)
                result.onSuccess {
                    Toast.makeText(this@WatchLiveActivity, getString(R.string.watch_gift_sent, it.remaining_coins), Toast.LENGTH_SHORT).show()
                    addChatMessage(SessionStore.userId, "System", "🎁 You sent Diwali Sparkler to host!")
                    signalClient?.sendChat("🎁 sent Diwali Sparkler!")
                    addPkPoints(99)
                    showGiftBanner("✨ Combo x1! Sent Diwali Sparkler (+99 pts)")
                }.onFailure {
                    Toast.makeText(this@WatchLiveActivity, getString(R.string.watch_gift_note, it.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                sampleGiftInFlight = false
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
                                iceRecoveryInFlight = false
                                binding.tvStatus.text = getString(R.string.watch_connected)
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                binding.tvStatus.text = getString(R.string.watch_reconnecting)
                                // Still stuck after 20s: ask the host for a
                                // fresh offer (single-flight, renegotiates).
                                binding.tvStatus.removeCallbacks(disconnectedRunnable)
                                binding.tvStatus.postDelayed(disconnectedRunnable, 20_000)
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                binding.tvStatus.text = getString(R.string.watch_stream_error)
                                requestVideoAgain()
                            }
                            PeerConnection.IceConnectionState.CLOSED -> {
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
                    runOnUiThread {
                        // Fatal viewer errors must be visible, not logcat-only.
                        binding.tvStatus.text = getString(R.string.watch_stream_error)
                    }
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
                    runOnUiThread { binding.tvViewers.text = getString(R.string.watch_viewers_eye, viewers) }
                }

                override fun onViewerCount(viewers: Long) {
                    runOnUiThread { binding.tvViewers.text = getString(R.string.watch_viewers_eye, viewers) }
                }

                override fun onChat(fromUserId: Long, nickname: String, text: String) {
                    runOnUiThread {
                        addChatMessage(fromUserId, nickname, text)
                    }
                }

                override fun onRtcSignal(fromUserId: Long, kind: String, sdp: String) {
                    // Strict sender check: only the host may offer/ICE us.
                    // Anything else is ignored (and logged) so a mistargeted
                    // or rogue signal can never poison this session.
                    if (fromUserId != hostId) {
                        android.util.Log.w("UnrealStarRtc", "ignoring $kind signal from non-host $fromUserId")
                        return
                    }
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

                override fun onPkStateChanged(pkId: String, pointsA: Long, pointsB: Long) {
                    runOnUiThread {
                        renderPkScore(pkId, pointsA, pointsB)
                    }
                }

                override fun onGift(senderId: Long, nickname: String, giftId: String, count: Int) {
                    runOnUiThread {
                        // Others' gifts (and our own echo): banner only. The
                        // scoreboard moves via onPkStateChanged, never here,
                        // so the echo of our own gift cannot double-count.
                        if (senderId != SessionStore.userId) {
                            val who = nickname.ifEmpty { "Someone" }
                            showGiftBanner(getString(R.string.watch_gift_banner_other, who, giftId, count))
                            addChatMessage(senderId, who, getString(R.string.watch_gift_chat_other, giftId, count))
                        }
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
                try { viewer?.close() } catch (_: Exception) {}
                viewer = null
                return
            }
            signalClient = client
            // Join + video request go out exactly once, from onConnected —
            // connect() is async and outbound is held until the socket opens.
        } catch (t: Throwable) {
            android.util.Log.w("UnrealStarRtc", "watch unavailable", t)
            try { viewer?.close() } catch (_: Exception) {}
            viewer = null
            binding.tvStatus.text = getString(R.string.watch_stream_error)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { binding.tvStatus.removeCallbacks(disconnectedRunnable) } catch (_: Exception) {}
        try { binding.tvGiftBanner.removeCallbacks(hideBannerRunnable) } catch (_: Exception) {}
        try { binding.tvGiftBanner.animate().cancel() } catch (_: Exception) {}
        try { binding.rvChat.adapter = null } catch (_: Exception) {}
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
                holder.b.tvVipTag.text = getString(R.string.chat_vip_tag, msg.vipLevel)
                holder.b.tvVipTag.visibility = View.VISIBLE
            } else {
                holder.b.tvVipTag.visibility = View.GONE
            }
            holder.b.tvSender.text = getString(R.string.chat_sender_format, msg.nickname)
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
