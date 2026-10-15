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

    private var renderer: SurfaceViewRenderer? = null
    private var videoTrack: VideoTrack? = null
    private var answered = false

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(true)
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
                val track = receiver.track() ?: streams.firstNotNullOfOrNull { s ->
                    s.videoTracks.firstOrNull() ?: s.audioTracks.firstOrNull()
                }
                if (track is VideoTrack && videoTrack == null) {
                    videoTrack = track
                    renderer?.let { track.addSink(it) }
                    listener.onVideoReady()
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
     * track sinks into it the moment onTrack fires.
     */
    fun attachRenderer(surface: SurfaceViewRenderer) {
        surface.init(eglBase.eglBaseContext, null)
        surface.setEnableHardwareScaler(true)
        renderer = surface
        videoTrack?.addSink(surface)
    }

    /**
     * Accept the host's offer, produce the answer (targeted back at the host
     * by the caller), and mark this session answered so duplicate offers are
     * rejected instead of corrupting the negotiated state. Errors propagate
     * to the caller — a failed answer must be visible, not swallowed.
     */
    suspend fun acceptHostOffer(hostId: Long, offerSdp: String): SessionDescription {
        val pc = peerConnection ?: throw IllegalStateException("viewer not initialised")
        if (answered) {
            throw IllegalStateException("duplicate offer from $hostId — session already answered")
        }
        pc.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            },
            SessionDescription(SessionDescription.Type.OFFER, offerSdp),
        )
        answered = true

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
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer(),
            ),
        ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    fun close() {
        // Capture first: release order matters and one missing piece must not
        // short-circuit the native teardown below.
        val sinkRenderer = renderer
        videoTrack?.let { track -> sinkRenderer?.let { r -> try { track.removeSink(r) } catch (_: Throwable) {} } }
        videoTrack = null
        try { sinkRenderer?.release() } catch (_: Throwable) {}
        renderer = null
        // Throwable on purpose: native teardown can raise Errors from broken
        // vendor WebRTC builds and must never crash the watch screen.
        try { peerConnection?.close() } catch (_: Throwable) {}
        try { factory?.dispose() } catch (_: Throwable) {}
        try { eglBase.release() } catch (_: Throwable) {}
        peerConnection = null
        factory = null
    }

}
