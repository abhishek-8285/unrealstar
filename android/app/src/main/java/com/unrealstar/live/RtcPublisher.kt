package com.unrealstar.live

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.NV21Buffer
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Host-side WebRTC stack. Owns the factory, one shared camera video source
 * (fed by a CameraX ImageAnalysis pump) and one mic audio source, and fans
 * them out across a per-viewer [PeerConnection] map. One single PC that every
 * answer is applied against cannot serve more than one viewer — each viewer
 * gets its own connection instead.
 *
 * Signalling contract (over [RtcSignalClient]):
 *   viewer -> host kind="request"  ("I want video")
 *   host   -> viewer kind="offer"  (target = that viewer's id)
 *   viewer -> host kind="answer"   (target = host id or 0)
 *   both   -> kind="ice", wire "sdpMid|sdpMLineIndex|candidate"
 */
interface RtcPublisherListener {
    fun onViewerIce(viewerId: Long, candidate: IceCandidate) {}
    fun onViewerConnectionChange(viewerId: Long, state: PeerConnection.IceConnectionState) {}
    fun onPublisherError(where: String, message: String) {}
}

class RtcPublisher(
    context: Context,
    private val listener: RtcPublisherListener = object : RtcPublisherListener {},
) {
    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null

    private var pumpAnalysis: ImageAnalysis? = null
    // Single-threaded pump: the YUV→NV21 copy is ~1.4 MB per frame at 30fps
    // and must never run on the UI thread (main-thread jank, analyzer drops).
    private val pumpExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "camstar-nv21-pump").apply { isDaemon = true }
    }
    // onCapturerStarted is a once-per-session signal; re-signalling it mid-
    // stream (camera switch) can reset encoder pacing state.
    private var captureStarted = false

    /** viewerId -> dedicated peer connection sharing the host tracks. */
    private val viewerConnections = ConcurrentHashMap<Long, PeerConnection>()
    /** viewerIds whose answer was applied (negotiation complete). */
    private val negotiated = ConcurrentHashMap.newKeySet<Long>()
    /** Trickled ICE held until the matching answer is applied. */
    private val pendingIce =
        ConcurrentHashMap<Long, java.util.concurrent.ConcurrentLinkedQueue<IceCandidate>>()

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(com.unrealstar.live.BuildConfig.DEBUG)
                .createInitializationOptions(),
        )
        val rf = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            ?: throw IllegalStateException("peer connection factory creation failed")
        factory = rf

        // Mic: plain platform capture. Failure (permission dropped between ask
        // and go-live) disables the audio track, but the host UI is told so
        // viewers can distinguish "host muted" from a bug.
        try {
            val source = rf.createAudioSource(MediaConstraints())
            audioTrack = rf.createAudioTrack(AUDIO_TRACK_ID, source)
            audioSource = source
        } catch (t: Throwable) {
            Log.w(TAG, "mic unavailable — streaming video-only", t)
            listener.onPublisherError("mic", "Microphone unavailable — streaming video-only")
        }

        // Camera: the source is fed by [attachCameraFrames]; the track exists
        // here so viewer PC creation does not race capture start.
        videoSource = rf.createVideoSource(false /* not screencast */)
        videoTrack = rf.createVideoTrack(VIDEO_TRACK_ID, videoSource)
    }

    /**
     * Pump CameraX frames into the WebRTC video source. Reuses the activity's
     * existing CameraX binding instead of opening a second Camera2 client,
     * which would collide with CameraX on most camera HALs.
     *
     * The activity owns the single analyzer (face tracking fan-out) and calls
     * [onCameraFrame] directly; this helper stays for code paths that let the
     * publisher own the analyzer. Either way the proxy is closed here, never
     * by a tap listener.
     */
    fun attachCameraFrames(analysis: ImageAnalysis, executor: Executor) {
        pumpAnalysis?.clearAnalyzer()
        pumpAnalysis = analysis
        // Single-frame pump contract: the caller may pass any executor, the
        // publisher-owned pump thread stays out of it (frameTap fan-out runs
        // where the frames arrive).
        analysis.setAnalyzer(executor) { proxy ->
            try {
                onCameraFrame(proxy)
                try { frameTap?.invoke(proxy) } catch (_: Throwable) {}
            } finally {
                try { proxy.close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Synchronous tap observing each pumped frame while the proxy is open.
     * Must NOT close the proxy and must not block (copy what you need).
     */
    var frameTap: ((ImageProxy) -> Unit)? = null

    /** Single-frame pump without lifecycle ownership (activity-owned analyzer). */
    fun onCameraFrame(proxy: ImageProxy) {
        pumpFrame(proxy)
    }

    private fun pumpFrame(proxy: ImageProxy) {
        val source = videoSource ?: return
        try {
            if (!captureStarted) {
                captureStarted = true
                // The source is frame-fed (no CameraCapturer); signalling
                // capture start lets encoders initialise their pacing state
                // once, on the first real frame.
                source.capturerObserver.onCapturerStarted(true)
            }
            val nv21 = CameraFrame.toNv21(proxy, ::acquireNv21) ?: return
            // libwebrtc VideoFrame takes NANOSECONDS. CameraX timestamps are
            // already capture-pipeline monotonic ns; falling back to a
            // monotonic clock only if the sensor clock is zeroed/unavailable.
            val timestampNs = proxy.imageInfo.timestamp.takeIf { it > 0 }
                ?: SystemClock.elapsedRealtimeNanos()
            // CameraX rotationDegrees is counterclockwise-to-upright,
            // libwebrtc VideoFrame rotation is clockwise-to-upright — the
            // inverse angles. Passing CameraX's value verbatim landed remote
            // video rotated 180° on back cameras and sideways on front.
            val rotation = (360 - proxy.imageInfo.rotationDegrees) % 360
            source.capturerObserver.onFrameCaptured(
                VideoFrame(
                    NV21Buffer(nv21, proxy.width, proxy.height, Runnable {
                        // Release callback: libwebrtc fires this when no
                        // consumer references the buffer anymore, making
                        // recycling correct (no use-after-encode hazards).
                        if (framePool.size < POOL_MAX) {
                            framePool.add(nv21)
                        }
                    }),
                    rotation,
                    timestampNs,
                ),
            )
        } catch (t: Throwable) {
            // A single malformed frame must not kill the analyzer pipeline.
            Log.w(TAG, "frame drop: ${t.message}")
        }
    }

    /**
     * Reusable NV21 backing arrays. Without this the pump allocates
     * ~1.4 MB per frame at 720p/30fps (~40 MB/s of GC pressure) and mobile
     * encoders trigger GC pauses visible as stutter.
     */
    private val framePool = ConcurrentLinkedQueue<ByteArray>()

    private fun acquireNv21(needed: Int): ByteArray {
        while (true) {
            val candidate = framePool.poll() ?: return ByteArray(needed)
            // Wrong-size buffers appear after a camera switch changed the
            // stream resolution; they are simply unused and abandoned.
            if (candidate.size == needed) return candidate
        }
    }

    /**
     * Bring up a dedicated connection for one viewer and produce the offer
     * targeted at them. The shared tracks are attached send-only so the
     * connection never negotiates a receive channel for host media.
     */
    suspend fun openViewerSession(viewerId: Long): SessionDescription {
        val rf = factory ?: throw IllegalStateException("publisher not initialised")
        // A reconnecting viewer re-requests: never orphan the old PC — close
        // and dispose it before it is overwritten and becomes unleakable.
        viewerConnections.remove(viewerId)?.let { old ->
            try { old.close() } catch (_: Throwable) {}
            try { old.dispose() } catch (_: Throwable) {}
        }
        negotiated.remove(viewerId)
        pendingIce.remove(viewerId)
        val pc = rf.createPeerConnection(rtcConfig(), PeerObserver(viewerId))
            ?: throw IllegalStateException("peer connection creation failed")
        viewerConnections[viewerId] = pc

        videoTrack?.let { track ->
            pc.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
            )
        }
        audioTrack?.let { track ->
            pc.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
            )
        }

        try {
            return createOffer(pc)
        } catch (t: Throwable) {
            // A failed offer must not leave a ghost PC behind.
            viewerConnections.remove(viewerId, pc)
            try { pc.close() } catch (_: Throwable) {}
            try { pc.dispose() } catch (_: Throwable) {}
            throw t
        }
    }

    /**
     * Apply a viewer's answer to their own connection. Suspend + explicit
     * failure propagation: the old void version swallowed setRemoteDescription
     * errors and left a dead media path with zero diagnostics. On success the
     * session is marked negotiated and any early trickled ICE is drained.
     */
    suspend fun applyViewerAnswer(viewerId: Long, sdp: String) {
        val pc = viewerConnections[viewerId]
            ?: throw IllegalStateException("no viewer session for $viewerId")
        setRemoteAnswer(pc, sdp)
        negotiated.add(viewerId)
        drainPendingIce(viewerId, pc)
    }

    fun addRemoteIce(viewerId: Long, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        if (sdpMid.isEmpty() || candidate.isEmpty() || sdpMLineIndex < 0) {
            Log.w(TAG, "dropping malformed ICE for viewer $viewerId")
            return
        }
        val pc = viewerConnections[viewerId]
        if (pc == null) {
            Log.w(TAG, "dropping ICE for unknown viewer $viewerId")
            return
        }
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (!negotiated.contains(viewerId)) {
            pendingIce
                .getOrPut(viewerId) { java.util.concurrent.ConcurrentLinkedQueue() }
                .add(ice)
            return
        }
        try {
            if (pc.addIceCandidate(ice) == false) {
                Log.w(TAG, "ICE rejected for negotiated viewer $viewerId, requeueing")
                pendingIce
                    .getOrPut(viewerId) { java.util.concurrent.ConcurrentLinkedQueue() }
                    .add(ice)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ICE add failed for viewer $viewerId", t)
        }
    }

    private fun drainPendingIce(viewerId: Long, pc: PeerConnection) {
        val queue = pendingIce.remove(viewerId) ?: return
        while (true) {
            val ice = queue.poll() ?: break
            try {
                pc.addIceCandidate(ice)
            } catch (t: Throwable) {
                Log.w(TAG, "pending ICE drain failed for viewer $viewerId", t)
            }
        }
    }

    /** Tear down a single viewer's connection (viewer left / disconnected). */
    fun closeViewerSession(viewerId: Long) {
        negotiated.remove(viewerId)
        pendingIce.remove(viewerId)
        viewerConnections.remove(viewerId)?.let { pc ->
            try { pc.close() } catch (_: Throwable) {}
            try { pc.dispose() } catch (_: Throwable) {}
        }
    }

    fun activeViewerCount(): Int = viewerConnections.size

    private suspend fun createOffer(pc: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { cont ->
            pc.createOffer(object : SdpObserver {
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

    private suspend fun setRemoteAnswer(pc: PeerConnection, sdp: String) =
        suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(
                object : SdpObserver {
                    override fun onSetSuccess() {
                        if (!cont.isCompleted) cont.resume(Unit)
                    }

                    override fun onSetFailure(error: String?) {
                        if (!cont.isCompleted) {
                            cont.resumeWithException(IllegalStateException(error))
                        }
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                },
                SessionDescription(SessionDescription.Type.ANSWER, sdp),
            )
        }

    private fun rtcConfig(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(RtcIce.iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

    private inner class PeerObserver(private val viewerId: Long) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onViewerIce(viewerId, candidate)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            listener.onViewerConnectionChange(viewerId, state)
            // Auto-clean dead viewer connections so the map does not grow
            // forever with ghosts (viewer left app mid-stream, stale stale
            // ICE candidates from the network held references). Posted off
            // the observer callback: close/dispose on the PC's own thread
            // risks native re-entrancy on some WebRTC builds.
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            ) {
                try {
                    pumpExecutor.execute { closeViewerSession(viewerId) }
                } catch (_: Throwable) {
                    closeViewerSession(viewerId)
                }
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<org.webrtc.MediaStream>) {}
    }

    companion object {
        private const val TAG = "UnrealStarRtc"
        private const val VIDEO_TRACK_ID = "camstar-video0"
        private const val AUDIO_TRACK_ID = "camstar-audio0"
        /** One in-flight frame + a few queued up ahead; small on purpose. */
        private const val POOL_MAX = 4

        /**
         * Trickled-ICE wire format shared with [RtcSignalClient]:
         * "sdpMid|sdpMLineIndex|candidate". Returns null when malformed.
         */
        fun parseIce(wire: String): Triple<String, Int, String>? {
            val parts = wire.split('|', limit = 3)
            if (parts.size != 3) return null
            if (parts[0].isEmpty() || parts[2].isEmpty()) return null
            val mLine = parts[1].toIntOrNull() ?: return null
            if (mLine < 0) return null
            return Triple(parts[0], mLine, parts[2])
        }
    }

    fun close() {
        try {
            pumpAnalysis?.clearAnalyzer()
        } catch (_: Throwable) {}
        pumpAnalysis = null
        try {
            videoSource?.capturerObserver?.onCapturerStopped()
        } catch (_: Throwable) {}
        captureStarted = false
        pumpExecutor.shutdown()

        for ((_, pc) in viewerConnections) {
            try { pc.close() } catch (_: Throwable) {}
            try { pc.dispose() } catch (_: Throwable) {}
        }
        viewerConnections.clear()
        negotiated.clear()
        pendingIce.clear()

        // Throwable on purpose: native teardown can raise Errors
        // (UnsatisfiedLinkError, NoClassDefFoundError from broken vendor
        // builds) and teardown must never crash the call screen.
        try { videoSource?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        try { factory?.dispose() } catch (_: Throwable) {}
        try { eglBase.release() } catch (_: Throwable) {}
        videoSource = null
        audioSource = null
        videoTrack = null
        audioTrack = null
        factory = null
    }
}

/**
 * CameraX YUV_420_888 -> WebRTC NV21 conversion. CameraX only guarantees the
 * generic semi-planar layout, so planes are copied honoring rowStride and
 * pixelStride; CameraX 1.3 lacks the built-in OUTPUT_IMAGE_FORMAT_NV21 (that
 * landed in 1.4).
 */
object CameraFrame {
    /**
     * YUV_420_888 -> NV21. Plane buffers are duplicated and rewound so
     * absolute reads honor the buffer's initial position (CameraX planes may
     * carry non-zero offsets); bounds are checked against limit(), never
     * capacity(). Odd dimensions are rejected explicitly instead of
     * producing short/chroma-shifted output.
     */
    fun toNv21(image: ImageProxy, allocate: (Int) -> ByteArray = { ByteArray(it) }): ByteArray? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        if (width % 2 != 0 || height % 2 != 0) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val ySize = width * height
        val nv21 = allocate(ySize + ySize / 2)

        // 1) Luma.
        val yb = yPlane.buffer.duplicate()
        val yOff = yb.position()
        val yRs = yPlane.rowStride
        val yPs = yPlane.pixelStride.coerceAtLeast(1)
        if (yPs == 1 && yRs == width && yb.remaining() >= ySize) {
            yb.get(nv21, 0, ySize)
        } else {
            var dst = 0
            for (r in 0 until height) {
                val rowBase = yOff + r * yRs
                if (rowBase < 0 || rowBase + (width - 1) * yPs >= yb.limit()) return null
                if (yPs == 1) {
                    yb.position(rowBase)
                    yb.get(nv21, dst, width)
                } else {
                    for (c in 0 until width) nv21[dst + c] = yb.get(rowBase + c * yPs)
                }
                dst += width
            }
        }

        // 2) Chroma, interleaved V,U per 2x2 block.
        val cw = width / 2
        val ch = height / 2
        val ub = uPlane.buffer.duplicate()
        val vb = vPlane.buffer.duplicate()
        val uOff = ub.position()
        val vOff = vb.position()
        val uRs = uPlane.rowStride
        val vRs = vPlane.rowStride
        val uPs = uPlane.pixelStride.coerceAtLeast(1)
        val vPs = vPlane.pixelStride.coerceAtLeast(1)
        var dst = ySize
        if (uPs == 1 && vPs == 1 && uRs == cw && vRs == cw &&
            uOff + (ch - 1) * uRs + (cw - 1) < ub.limit() &&
            vOff + (ch - 1) * vRs + (cw - 1) < vb.limit()
        ) {
            // Tight rows: copy each plane row, then interleave.
            val uRow = ByteArray(cw)
            val vRow = ByteArray(cw)
            for (r in 0 until ch) {
                ub.position(uOff + r * uRs)
                ub.get(uRow, 0, cw)
                vb.position(vOff + r * vRs)
                vb.get(vRow, 0, cw)
                for (c in 0 until cw) {
                    nv21[dst++] = vRow[c]
                    nv21[dst++] = uRow[c]
                }
            }
        } else {
            for (r in 0 until ch) {
                for (c in 0 until cw) {
                    val vIdx = vOff + r * vRs + c * vPs
                    val uIdx = uOff + r * uRs + c * uPs
                    if (vIdx >= vb.limit() || uIdx >= ub.limit()) return null
                    nv21[dst++] = vb.get(vIdx)
                    nv21[dst++] = ub.get(uIdx)
                }
            }
        }
        return nv21
    }
}
