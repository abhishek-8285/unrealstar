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
        fun onConnected() {}
        fun onRoomState(viewers: Long) {}
        fun onChat(fromUserId: Long, nickname: String, text: String) {}
        fun onRtcSignal(fromUserId: Long, kind: String, sdp: String) {}
        /** Alias for onRoomState driven by the live ViewerCount updates. */
        fun onViewerCount(viewers: Long) {}
        /** Socket giving up after retries, or room closed server-side. */
        fun onDisconnected() {}
        /** This device was kicked or banned: non-reconnectable close. */
        fun onKicked(banned: Boolean) {}
        /** Live PK scoreboard update (accepted battles + gift scoring). */
        fun onPkStateChanged(pkId: String, pointsA: Long, pointsB: Long) {}
        /** A gift landed in this room (server broadcast, authoritative). */
        fun onGift(senderId: Long, nickname: String, giftId: String, count: Int) {}
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
    /** Terminal close (kick/ban/room-closed/leave): reconnect must never fire. */
    private var terminalClosed = false
    /** True only between onOpen and onClosed/onFailure/close. */
    private val opened = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Outbound held until the socket opens (connect() is async). */
    private val outbox = java.util.ArrayDeque<String>()
    private var attempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connect(attempts) }

    fun connect(): Boolean = connect(0)

    private fun connect(attempt: Int): Boolean {
        if (terminalClosed) return false
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
                    opened.set(true)
                    flushOutbox()
                    postToMain { listener.onConnected() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    opened.set(false)
                    if (userClosed) return
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.set(false)
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

    fun isConnected(): Boolean = opened.get()

    fun close() {
        userClosed = true
        terminalClosed = true
        opened.set(false)
        mainHandler.removeCallbacks(reconnectRunnable)
        synchronized(outbox) { outbox.clear() }
        try { socket?.close(1000, "bye") } catch (_: Exception) {}
        socket = null
        // Never shut the dispatcher's executor down here: it would break any
        // later socket in this process. Just cancel in-flight calls.
        try { client.dispatcher.cancelAll() } catch (_: Exception) {}
    }

    private fun postToMain(fn: () -> Unit) {
        mainHandler.post(fn)
    }

    private fun send(text: String) {
        val hold = synchronized(outbox) {
            if (!opened.get()) {
                if (outbox.size < 50) outbox.addLast(text)
                true
            } else false
        }
        if (hold) return
        try { socket?.send(text) } catch (_: Exception) {}
    }

    private fun flushOutbox() {
        val batch = synchronized(outbox) {
            if (outbox.isEmpty()) return
            val b = outbox.toList()
            outbox.clear()
            b
        }
        batch.forEach { text -> try { socket?.send(text) } catch (_: Exception) {} }
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val type = msg.optString("type")
            val payload = msg.optJSONObject("payload") ?: JSONObject()
            when (type) {
                // RoomState arrives on Join snapshots; ViewerCount is the
                // live delta-free update pushed on every join/leave/kick.
                "RoomState" -> postToMain {
                    listener.onRoomState(payload.optLong("viewers_count"))
                }
                "ViewerCount" -> postToMain {
                    listener.onViewerCount(payload.optLong("viewers_count"))
                }
                "ChatBroadcast" -> {
                    val from = payload.optLong("user_id")
                    val nickname = payload.optString("nickname")
                    val body = payload.optString("text")
                    postToMain { listener.onChat(from, nickname, body) }
                }
                "RtcSignalRelay" -> {
                    // Ignore our own relays: the room broadcast echoes every
                    // signal back to its sender and we must not feed our own
                    // offer back into our own peer connections.
                    val from = payload.optLong("from_user_id")
                    if (from == SessionStore.userId) return
                    val target = payload.optLong("target_user_id")
                    if (target != 0L && target != SessionStore.userId) return
                    val kind = payload.optString("kind")
                    val sdp = payload.optString("sdp")
                    if (kind != "offer" && kind != "answer" && kind != "ice" && kind != "request") {
                        android.util.Log.w("UnrealStarRtc", "dropping relay with unknown kind from=$from target=$target")
                        return
                    }
                    if (kind != "request" && sdp.isEmpty()) {
                        android.util.Log.w("UnrealStarRtc", "dropping $kind relay with empty sdp from=$from")
                        return
                    }
                    postToMain { listener.onRtcSignal(from, kind, sdp) }
                }
                "RoomClosed" -> {
                    terminalClosed = true
                    userClosed = true
                    opened.set(false)
                    mainHandler.removeCallbacks(reconnectRunnable)
                    try { socket?.close(1000, "room closed") } catch (_: Exception) {}
                    postToMain { listener.onDisconnected() }
                }
                "KickedFromRoom" -> {
                    if (payload.optLong("user_id") == SessionStore.userId) {
                        terminalClosed = true
                        userClosed = true
                        opened.set(false)
                        mainHandler.removeCallbacks(reconnectRunnable)
                        try { socket?.close(1000, "kicked") } catch (_: Exception) {}
                        val banned = payload.optBoolean("banned")
                        postToMain { listener.onKicked(banned) }
                    }
                }
                "PkStateChanged" -> {
                    val pkId = payload.optString("pk_id")
                    if (pkId.isNotEmpty()) {
                        val a = payload.optLong("points_a")
                        val b = payload.optLong("points_b")
                        postToMain { listener.onPkStateChanged(pkId, a, b) }
                    }
                }
                "GiftBroadcast" -> {
                    val giftId = payload.optString("gift_id")
                    if (giftId.isNotEmpty()) {
                        val from = payload.optLong("sender_id")
                        val nickname = payload.optString("nickname")
                        val count = payload.optInt("count", 1).coerceIn(1, 99)
                        postToMain { listener.onGift(from, nickname, giftId, count) }
                    }
                }
                else -> {
                    android.util.Log.w("UnrealStarRtc", "ignoring unknown signal type=$type")
                }
            }
        } catch (_: Exception) {}
    }

    private companion object {
        /** Reconnect ladder: 1s,2s,4s,8s,8s capped — then give up. */
        private const val MAX_RECONNECTS = 5
    }
}
