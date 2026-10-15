package com.unrealstar.live

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unrealstar.live.api.SessionStore
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ActivityWatchLiveBinding
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.IceCandidate

/**
 * Minimal watch screen: join a live room, open the viewer's WebRTC session,
 * render the host stream. Chat is intentionally read-only here (the input
 * flow lands with the room messaging phase).
 */
class WatchLiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchLiveBinding
    private var roomId = 0L
    private var hostId = 0L
    private var viewer: RtcViewer? = null
    private var signalClient: RtcSignalClient? = null

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
            startWatching()
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
                override fun onRoomState(viewers: Long) {
                    runOnUiThread { binding.tvViewers.text = "👁 $viewers" }
                }

                override fun onViewerCount(viewers: Long) {
                    runOnUiThread { binding.tvViewers.text = "👁 $viewers" }
                }

                override fun onChat(fromUserId: Long, nickname: String, text: String) {
                    runOnUiThread {
                        binding.tvChat.text = "$nickname: $text"
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
                                // A rejected offer/answer kills the stream for
                                // this viewer and must be visible in the log.
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
            // Ask the host for video. "{}" is a deliberate placeholder: the
            // server only requires a non-empty opaque sdp payload.
            client.sendSignal(hostId, "request", "{}")
        } catch (t: Throwable) {
            // Media-stack failures degrade to the error status, never a crash.
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
