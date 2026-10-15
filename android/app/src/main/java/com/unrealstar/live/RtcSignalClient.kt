package com.unrealstar.live

import android.os.Handler
import android.os.Looper
import com.unrealstar.live.api.SessionStore
import com.unrealstar.live.api.UnrealStarApiClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket transport for /ws/rooms/{id}: Join, Chat and RtcSignal.
 * Callbacks fire on OkHttp's WS thread; hop to the main scope for UI work.
 * The server relays RtcSignal verbatim; clients filter on target_user_id and
 * ignore their own relays (the room broadcast sends everything back to the
 * sender too).
 *
 * Auth uses the Authorization header — native code can set it, so the token
 * never lands in URLs, server access logs, or intermediary logs (the query
 * string carrier survives for browsers only, server-side).
 */
class RtcSignalClient(
    private val roomId: Long,
    private val listener: Listener,
) {
    interface Listener {
        fun onRoomState(viewers: Long) {}
        fun onChat(fromUserId: Long, nickname: String, text: String) {}
        fun onRtcSignal(fromUserId: Long, kind: String, sdp: String) {}
        /** Alias for onRoomState driven by the live ViewerCount updates. */
        fun onViewerCount(viewers: Long) {}
        /** Socket giving up after retries, or room closed server-side. */
        fun onDisconnected() {}
        /** This device was kicked or banned: non-reconnectable close. */
        fun onKicked(banned: Boolean) {}
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Silence network idle detection: NAT gateways drop idle sockets
        // within a minute, which used to kill streams silently mid-view.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var userClosed = false
    private var attempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connect(attempts) }

    fun connect(): Boolean = connect(0)

    private fun connect(attempt: Int): Boolean {
        userClosed = false
        attempts = attempt
        val token = SessionStore.rawToken() ?: return false
        socket = client.newWebSocket(
            Request.Builder()
                .url("${UnrealStarApiClient.wsBaseUrl()}/ws/rooms/$roomId")
                .header("Authorization", "Bearer $token")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Successful (re)connection resets the backoff ladder.
                    attempts = 0
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (userClosed) return
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (userClosed) return
                    scheduleReconnect()
                }
            },
        )
        return true
    }

    private fun scheduleReconnect() {
        if (userClosed || attempts >= MAX_RECONNECTS) {
            mainHandler.removeCallbacks(reconnectRunnable)
            listener.onDisconnected()
            return
        }
        val backoffMs = ((1 shl attempts) * 1000L).coerceAtMost(8000L)
        attempts += 1
        mainHandler.postDelayed(reconnectRunnable, backoffMs)
    }

    fun sendJoin() {
        val userId = SessionStore.userId
        send(JSONObject().apply {
            put("type", "Join")
            put("payload", JSONObject().apply {
                put("user_id", userId)
                put("room_id", roomId)
            })
        }.toString())
    }

    /** kind is "offer" | "answer" | "ice". target 0 broadcasts to the room. */
    fun sendSignal(targetUserId: Long, kind: String, sdp: String) {
        if (sdp.isEmpty() || sdp.length > 16 * 1024) return
        send(JSONObject().apply {
            put("type", "RtcSignal")
            put("payload", JSONObject().apply {
                put("target_user_id", targetUserId)
                put("kind", kind)
                put("sdp", sdp)
            })
        }.toString())
    }

    fun sendChat(text: String) {
        send(JSONObject().apply {
            put("type", "Chat")
            put("payload", JSONObject().apply {
                put("text", text)
            })
        }.toString())
    }

    fun isConnected(): Boolean = socket != null && attempts == 0

    fun close() {
        userClosed = true
        mainHandler.removeCallbacks(reconnectRunnable)
        try { socket?.close(1000, "bye") } catch (_: Exception) {}
        socket = null
        try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
    }

    private fun send(text: String) {
        try { socket?.send(text) } catch (_: Exception) {}
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val type = msg.optString("type")
            val payload = msg.optJSONObject("payload") ?: JSONObject()
            when (type) {
                // RoomState arrives on Join snapshots; ViewerCount is the
                // live delta-free update pushed on every join/leave/kick.
                "RoomState" -> listener.onRoomState(payload.optLong("viewers_count"))
                "ViewerCount" -> listener.onViewerCount(payload.optLong("viewers_count"))
                "ChatBroadcast" -> listener.onChat(
                    payload.optLong("user_id"),
                    payload.optString("nickname"),
                    payload.optString("text"),
                )
                "RtcSignalRelay" -> {
                    // Ignore our own relays: the room broadcast echoes every
                    // signal back to its sender and we must not feed our own
                    // offer back into our own peer connections.
                    val from = payload.optLong("from_user_id")
                    if (from == SessionStore.userId) return
                    val target = payload.optLong("target_user_id")
                    if (target == 0L || target == SessionStore.userId) {
                        listener.onRtcSignal(from, payload.optString("kind"), payload.optString("sdp"))
                    }
                }
                "RoomClosed" -> {
                    userClosed = true
                    try { socket?.close(1000, "room closed") } catch (_: Exception) {}
                    listener.onDisconnected()
                }
                "KickedFromRoom" -> {
                    if (payload.optLong("user_id") == SessionStore.userId) {
                        userClosed = true
                        try { socket?.close(1000, "kicked") } catch (_: Exception) {}
                        listener.onKicked(payload.optBoolean("banned"))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private companion object {
        /** Reconnect ladder: 1s,2s,4s,8s,8s capped — then give up. */
        private const val MAX_RECONNECTS = 5
    }
}
