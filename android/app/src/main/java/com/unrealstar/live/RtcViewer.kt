package com.unrealstar.live

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Viewer-side WebRTC stack for 1:1 rooms. One recv-only PeerConnection that
 * answers the host's viewer-targeted offer, consumes trickled ICE, and sinks
 * the first arriving video track into a [SurfaceViewRenderer].
 *
 * Signalling contract over [RtcSignalClient] (mirrors [RtcPublisher]):
 *   viewer -> host kind="request"  ("I want video"; sdp is a placeholder)
 *   host   -> viewer kind="offer"  (target = this viewer's id)
 *   viewer -> host kind="answer"   (target = host id)
 *   both   -> kind="ice", wire "sdpMid|sdpMLineIndex|candidate"
 */
interface RtcViewerListener {
    fun onHostIce(candidate: IceCandidate) {}
    fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    fun onVideoReady() {}
    fun onViewerError(where: String, message: String) {}
}

class RtcViewer(
    context: Context,
    private val listener: RtcViewerListener = object : RtcViewerListener {},
) {
    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var renderer: SurfaceViewRenderer? = null
    @Volatile
    private var videoTrack: VideoTrack? = null
    @Volatile
    private var answered = false
    /** Set once the remote offer is applied; gates the ICE drain. */
    private var remoteDescSet = false
    /** Trickled ICE that arrived before the remote description. Main-thread only. */
    private val pendingIce = ArrayDeque<IceCandidate>()
    private var closed = false

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(com.unrealstar.live.BuildConfig.DEBUG)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        peerConnection = factory?.createPeerConnection(rtcConfig(), object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onHostIce(candidate)
            }

            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<org.webrtc.MediaStream>) {
                if (closed) return
                val track = receiver.track() ?: streams.firstNotNullOfOrNull { s ->
                    s.videoTracks.firstOrNull() ?: s.audioTracks.firstOrNull()
                }
                if (track is VideoTrack) {
                    val prev = videoTrack
                    if (prev !== track) {
                        // Renegotiation may replace the track: swap the sink
                        // instead of dropping the new track (frozen video).
                        val sink = renderer
                        if (prev != null && sink != null) {
                            try { prev.removeSink(sink) } catch (_: Throwable) {}
                        }
                        videoTrack = track
                        if (sink != null) {
                            try { track.addSink(sink) } catch (t: Throwable) {
                                Log.w(TAG, "video sink swap failed", t)
                            }
                        }
                        listener.onVideoReady()
                    }
                }
                // Audio tracks need no sink: the platform plays them once the
                // transceiver is live.
                if (track is AudioTrack) {
                    track.setVolume(1.0)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                listener.onIceConnectionChange(state)
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: throw IllegalStateException("peer connection creation failed")

        // recv-only m-lines so the host offer always has both directions.
        peerConnection?.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        peerConnection?.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
    }

    /**
     * Bind the render surface before the host offer arrives so the video
     * track sinks into it the moment onTrack fires. Idempotent: rebinding
     * the same surface is a no-op, and a different surface releases the old
     * one first (double-init() throws IllegalStateException otherwise).
     */
    fun attachRenderer(surface: SurfaceViewRenderer) {
        if (closed) return
        if (renderer === surface) return
        val old = renderer
        videoTrack?.let { track ->
            old?.let { o -> try { track.removeSink(o) } catch (_: Throwable) {} }
        }
        old?.let { o -> try { o.release() } catch (_: Throwable) {} }
        try {
            surface.init(eglBase.eglBaseContext, null)
        } catch (t: Throwable) {
            Log.w(TAG, "renderer init failed", t)
            return
        }
        surface.setEnableHardwareScaler(true)
        renderer = surface
        videoTrack?.let { track ->
            try { track.addSink(surface) } catch (t: Throwable) {
                Log.w(TAG, "addSink failed", t)
            }
        }
    }

    /**
     * Accept the host's offer and produce the answer targeted back at the
     * host. The remote description is awaited (not fire-and-forget) and its
     * failure propagates instead of poisoning the session. Re-offers
     * renegotiate the same connection instead of throwing: a second offer
     * replaces the remote description and yields a fresh answer.
     */
    suspend fun acceptHostOffer(hostId: Long, offerSdp: String): SessionDescription {
        val pc = peerConnection ?: throw IllegalStateException("viewer not initialised")
        if (closed) throw IllegalStateException("viewer closed")
        setRemoteDescriptionAwait(
            pc,
            SessionDescription(SessionDescription.Type.OFFER, offerSdp),
        )
        answered = true
        remoteDescSet = true
        drainPendingIce(pc)

        val answer = suspendCancellableCoroutine { cont ->
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            if (!cont.isCompleted) cont.resume(desc)
                        }

                        override fun onSetFailure(error: String?) {
                            if (!cont.isCompleted) {
                                cont.resumeWithException(IllegalStateException(error))
                            }
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }

                override fun onCreateFailure(error: String?) {
                    if (!cont.isCompleted) {
                        cont.resumeWithException(IllegalStateException(error))
                    }
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
        return answer
    }

    fun addRemoteIce(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        if (closed) return
        if (sdpMid.isEmpty() || candidate.isEmpty() || sdpMLineIndex < 0) {
            Log.w(TAG, "dropping malformed ICE candidate")
            return
        }
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (!remoteDescSet) {
            pendingIce.addLast(ice)
            return
        }
        val pc = peerConnection ?: return
        try {
            if (pc.addIceCandidate(ice) == false) {
                Log.w(TAG, "addIceCandidate rejected post-answer, requeueing")
                pendingIce.addLast(ice)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "addIceCandidate threw", t)
        }
    }

    private fun drainPendingIce(pc: PeerConnection) {
        while (true) {
            val ice = pendingIce.removeFirstOrNull() ?: break
            try {
                pc.addIceCandidate(ice)
            } catch (t: Throwable) {
                Log.w(TAG, "pending ICE drain failed", t)
            }
        }
    }

    private suspend fun setRemoteDescriptionAwait(pc: PeerConnection, sdp: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(
                object : SdpObserver {
                    override fun onSetSuccess() {
                        if (!cont.isCompleted) cont.resume(Unit)
                    }

                    override fun onSetFailure(error: String?) {
                        if (!cont.isCompleted) {
                            cont.resumeWithException(
                                IllegalStateException("setRemoteDescription failed: $error")
                            )
                        }
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                },
                sdp,
            )
        }

    private fun rtcConfig(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(RtcIce.iceServers()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    fun close() {
        if (closed) return
        closed = true
        // PC first so in-flight decoder frames stop before their sink dies;
        // every step isolated so one failure cannot skip the rest.
        // Throwable on purpose: native teardown can raise Errors from broken
        // vendor WebRTC builds and must never crash the watch screen.
        try { peerConnection?.close() } catch (_: Throwable) {}
        try { peerConnection?.dispose() } catch (_: Throwable) {}
        peerConnection = null
        val sinkRenderer = renderer
        videoTrack?.let { track -> sinkRenderer?.let { r -> try { track.removeSink(r) } catch (_: Throwable) {} } }
        videoTrack = null
        try { sinkRenderer?.release() } catch (_: Throwable) {}
        renderer = null
        try { factory?.dispose() } catch (_: Throwable) {}
        try { eglBase.release() } catch (_: Throwable) {}
        factory = null
        pendingIce.clear()
        remoteDescSet = false
        answered = false
    }

    companion object {
        private const val TAG = "UnrealStarRtcViewer"
    }
}
