package com.unrealstar.live

import org.webrtc.PeerConnection

/**
 * Shared ICE server list for publisher and viewer.
 *
 * Precedence: server-fetched entries (time-limited TURN from
 * GET /rtc/ice-servers) beat the static BuildConfig list, which beats
 * STUN-only. The static TURN triple (-PturnUrl/-PturnUser/-PturnPass) stays
 * as the offline fallback. STUN-only cannot punch symmetric NATs — exactly
 * the mobile-data viewer case — so without any TURN those viewers never
 * connect. A buildsystem-injected static secret still ships in the APK;
 * prefer the server endpoint before public release.
 */
internal object RtcIce {
    /** Set by [com.unrealstar.live.api.UnrealStarApiClient.fetchIceServers]. */
    @Volatile
    var runtime: List<PeerConnection.IceServer>? = null

    fun iceServers(): List<PeerConnection.IceServer> {
        runtime?.takeIf { it.isNotEmpty() }?.let { return it }
        val servers = ArrayList<PeerConnection.IceServer>(2)
        servers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer(),
        )
        try {
            val url = BuildConfig.TURN_URL
            val user = BuildConfig.TURN_USER
            val pass = BuildConfig.TURN_PASS
            if (url.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                servers.add(
                    PeerConnection.IceServer.builder(url)
                        .setUsername(user)
                        .setPassword(pass)
                        .createIceServer(),
                )
            }
        } catch (_: Throwable) {
            // BuildConfig missing in unit tests — STUN-only is fine there.
        }
        return servers
    }

    /** Convert fetched API entries; unparseable entries are dropped. */
    fun applyServerEntries(entries: List<com.unrealstar.live.models.IceServerEntry>) {
        try {
            val mapped = entries.mapNotNull { e ->
                try {
                    if (e.urls.isEmpty()) return@mapNotNull null
                    val builder = PeerConnection.IceServer.builder(e.urls)
                    if (!e.username.isNullOrBlank() && !e.credential.isNullOrBlank()) {
                        builder.setUsername(e.username).setPassword(e.credential)
                    }
                    builder.createIceServer()
                } catch (_: Throwable) {
                    null
                }
            }
            if (mapped.isNotEmpty()) runtime = mapped
        } catch (_: Throwable) {}
    }
}
