package com.unrealstar.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.api.SessionStore
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import com.unrealstar.live.databinding.ActivityCameraLiveBinding
import com.unrealstar.live.databinding.ItemBeautyFilterBinding
import com.unrealstar.live.databinding.ItemChatMessageBinding
import com.unrealstar.live.models.BeautyItem
import com.unrealstar.live.models.ChatMessage
import com.unrealstar.live.models.SendGiftRequest
import com.google.android.material.chip.Chip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class CameraLiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraLiveBinding
    private val filterManager = BeautyFilterManager()
    private val filterEngine by lazy { FilterEngine(this) }
    private var selectedSticker: FaceSticker? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    // Face tracking: activity owns the single analyzer and fans frames out to
    // the WebRTC pump + the throttled ML Kit tracker (one analyzer only).
    private var faceTracker: FaceTracker? = null
    private var faceExecutor: java.util.concurrent.ExecutorService? = null
    private var trackedFace: Triple<Float, Float, Float>? = null
    /** True while the GLES live preview owns the camera stream (GPU-mode devices). */
    private var gpuActive: Boolean = false
    /** Latched when the GLES bind fails so the fallback doesn't retry-loop. */
    private var gpuDisabled: Boolean = false
    /** Pre-live room mode, chosen on the setup tabs and sent as categoryId. */
    private var selectedMode: String = "live"
    /** Poster capture generation: invalidates the watchdog on arrival. */
    private var posterGen: Int = 0    /** Bound camera for exposure control; re-set on every (re)bind. */
    private var camera: androidx.camera.core.Camera? = null
    /** Exposure compensation state (device range discovered after bind). */
    private var exposureIndex: Int = 0
    private var exposureLower: Int = 0
    private var exposureUpper: Int = 0
    /** Guards the Go Live button against double-tap room creation. */
    private var goingLive: Boolean = false

    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var matteAdapter: MatteItemAdapter

    private var screenState: LiveScreenState = LiveScreenState.SETUP
    private var isShortsMode = false
    private var countdownTimer: CountDownTimer? = null
    private var liveDurationJob: kotlinx.coroutines.Job? = null
    private var liveDurationSeconds = 0
    // WebRTC publisher: created on Go-LIVE, torn down on End. Signalling runs
    // over /ws/rooms; media flow needs the ImageAnalysis pump below.
    private var rtcPublisher: RtcPublisher? = null
    private var signalClient: RtcSignalClient? = null
    private var publishJob: kotlinx.coroutines.Job? = null
    private var liveRoomId: Long = 0
    // Analyzer use case feeding the publisher's video source; created in
    // startCamera, re-created on camera switch.
    private var analysisUseCase: ImageAnalysis? = null

    private enum class LiveScreenState { SETUP, COUNTDOWN, LIVE, ENDED }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val camera = grants[Manifest.permission.CAMERA] == true
        val audio = grants[Manifest.permission.RECORD_AUDIO] == true
        if (camera && audio) {
            startCamera()
        } else if (camera) {
            // Preview-only: audio pipeline stays disabled until mic is granted.
            startCamera()
            Toast.makeText(this, "Mic denied — streaming without audio", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Camera permission is required for live streaming", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Gallery upload for the poster (system picker — no storage permission needed). */
    private val galleryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) onPosterPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isShortsMode = intent.getStringExtra(EXTRA_MODE) == MODE_SHORTS
        if (isShortsMode) {
            binding.btnGoLive.text = "⚡ Record Short (15s)"
        }

        setupChatList()
        setupFilterPanel()
        setupFaceTracking()
        setupListeners()
        setupPreviewTap()
        setScreenState(LiveScreenState.SETUP)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onPause() {
        super.onPause()
        try { binding.gpuPreviewView.onPause() } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        try { binding.gpuPreviewView.onResume() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
        liveDurationJob?.cancel()
        publishJob?.cancel()
        try { signalClient?.close() } catch (_: Exception) {}
        signalClient = null
        try { rtcPublisher?.close() } catch (_: Exception) {}
        rtcPublisher = null
        try { faceTracker?.close() } catch (_: Exception) {}
        faceTracker = null
        try { faceExecutor?.shutdownNow() } catch (_: Exception) {}
        faceExecutor = null
        try { binding.gpuPreviewView.release() } catch (_: Throwable) {}
    }

    /**
     * Tap-to-meter: tapping the preview meters AE (+AF on rear cameras) at
     * that point for 3s, with a fading focus ring. In mixed lighting (bright
     * lamp + dim room) this exposes the face instead of the lamp — the
     * direct fix for a washed-out preview. Front cameras are fixed-focus,
     * so only AE/AWB run there.
     */
    private fun setupPreviewTap() {
        val onTouch = { v: View, ev: android.view.MotionEvent ->
            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                meterAt(v, ev.x, ev.y)
                showFocusRing(ev.x, ev.y)
            }
            true
        }
        binding.previewView.setOnTouchListener(onTouch)
        binding.gpuPreviewView.setOnTouchListener(onTouch)
    }

    private fun meterAt(view: View, x: Float, y: Float) {
        val cam = camera ?: return
        if (view.width <= 0 || view.height <= 0) return
        try {
            val info = cam.cameraInfo
            val factory = androidx.camera.core.DisplayOrientedMeteringPointFactory(
                view.display, info, view.width.toFloat(), view.height.toFloat()
            )
            val point = factory.createPoint(x, y)
            val front = info.lensFacing == CameraSelector.LENS_FACING_FRONT
            val flags = if (front) {
                androidx.camera.core.FocusMeteringAction.FLAG_AE or
                    androidx.camera.core.FocusMeteringAction.FLAG_AWB
            } else {
                androidx.camera.core.FocusMeteringAction.FLAG_AF or
                    androidx.camera.core.FocusMeteringAction.FLAG_AE or
                    androidx.camera.core.FocusMeteringAction.FLAG_AWB
            }
            val action = androidx.camera.core.FocusMeteringAction.Builder(point, flags)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (t: Throwable) {
            android.util.Log.w("CameraLive", "metering failed", t)
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        try {
            val density = resources.displayMetrics.density
            val size = (72 * density).toInt()
            val ring = View(this)
            ring.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
                setColor(android.graphics.Color.TRANSPARENT)
            }
            val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(size, size).apply {
                leftToLeft = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                leftMargin = (x - size / 2).toInt().coerceAtLeast(0)
                topMargin = (y - size / 2).toInt().coerceAtLeast(0)
            }
            binding.root.addView(ring, lp)
            ring.animate().alpha(0f).setDuration(700).withEndAction {
                try { binding.root.removeView(ring) } catch (_: Throwable) {}
            }.start()
        } catch (_: Throwable) {}
    }

    /** ML Kit tracker -> sticker overlay follows the face; silent if GMS is missing. */
    private fun setupFaceTracking() {
        val tracker = FaceTracker(
            mirrorFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        )
        tracker.listener = object : FaceTracker.Listener {
            override fun onFace(
                centerX: Float, centerY: Float, widthFrac: Float,
                eyeL: Pair<Float, Float>?, eyeR: Pair<Float, Float>?
            ) {
                trackedFace = Triple(centerX, centerY, widthFrac)
                if (gpuActive) {
                    binding.gpuPreviewView.setFace(centerX, centerY, widthFrac, eyeL, eyeR)
                }
                positionSticker(centerX, centerY)
            }
            override fun onNoFace() {
                trackedFace = null
                if (gpuActive) {
                    binding.gpuPreviewView.clearFace()
                }
            }
        }
        faceTracker = tracker
    }

    private fun positionSticker(cx: Float, cy: Float) {
        val sticker = binding.tvStickerOverlay
        if (sticker.visibility != View.VISIBLE) return
        // Anchor on the always-laid-out makeup overlay: PreviewView is GONE
        // on the GPU path and would measure zero.
        val anchor = binding.ivFilterOverlay
        anchor.post {
            val pw = anchor.width.takeIf { it > 0 } ?: return@post
            val ph = anchor.height.takeIf { it > 0 } ?: return@post
            val sw = sticker.width.takeIf { it > 0 } ?: 160
            val sh = sticker.height.takeIf { it > 0 } ?: 160
            // Emoji sits above the forehead: face center minus ~1.2 sticker heights.
            sticker.translationX = (pw * cx - sw / 2f) - sticker.left
            sticker.translationY = (ph * cy - sh * 1.6f) - sticker.top
        }
    }

    private fun setScreenState(state: LiveScreenState) {
        screenState = state
        binding.preLiveControls.visibility = if (state == LiveScreenState.SETUP) View.VISIBLE else View.GONE
        binding.countdownOverlay.visibility = if (state == LiveScreenState.COUNTDOWN) View.VISIBLE else View.GONE
        binding.liveHud.visibility = if (state == LiveScreenState.LIVE) View.VISIBLE else View.GONE
        binding.endedSummary.visibility = if (state == LiveScreenState.ENDED) View.VISIBLE else View.GONE
        binding.filterPanel.visibility = View.GONE

        when (state) {
            LiveScreenState.SETUP -> {
                liveDurationJob?.cancel()
                countdownTimer?.cancel()
            }
            LiveScreenState.COUNTDOWN -> startCountdown()
            LiveScreenState.LIVE -> {
                startLiveDuration()
                if (isShortsMode) {
                    startShortsRecording()
                } else {
                    startPublishing()
                }
            }
            LiveScreenState.ENDED -> {
                liveDurationJob?.cancel()
                countdownTimer?.cancel()
                publishJob?.cancel()
                try { signalClient?.close() } catch (_: Exception) {}
                signalClient = null
                try { rtcPublisher?.close() } catch (_: Exception) {}
                rtcPublisher = null
                showEndedSummary()
            }
        }
    }

    private fun startCountdown() {
        binding.tvCountdownNumber.text = "3"
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt() + 1
                binding.tvCountdownNumber.text = seconds.toString()
            }
            override fun onFinish() {
                setScreenState(LiveScreenState.LIVE)
            }
        }.start()
    }

    private fun startLiveDuration() {
        liveDurationSeconds = 0
        liveDurationJob?.cancel()
        liveDurationJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                liveDurationSeconds++
            }
        }
    }

    private fun startShortsRecording() {
        binding.tvViewersBadge.text = "REC"
        binding.tvPkBadge.text = "🔴 00:00 / 00:15"
        lifecycleScope.launch {
            for (sec in 1..15) {
                kotlinx.coroutines.delay(1000)
                binding.tvPkBadge.text = String.format(java.util.Locale.US, "🔴 00:%02d / 00:15", sec)
            }
            Toast.makeText(this@CameraLiveActivity, "🎉 15s Short Video Recorded with Beauty Filters!", Toast.LENGTH_LONG).show()
            val postSheet = CreatePostBottomSheet(
                initialCaption = "My latest short clip with beauty filters! ✨ #Trending #Shorts #CamStar",
                initialCover = "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=600"
            ) {
                finish()
            }
            postSheet.show(supportFragmentManager, CreatePostBottomSheet.TAG)
        }
    }

    /**
     * Host publish path: create a room, join its WS channel, bring up the
     * WebRTC stack, then wait for viewers. Viewers signal kind="request" and
     * get a dedicated PeerConnection each — one shared PC cannot hold the
     * answers of multiple viewers. Any step may fail (signed out, offline) —
     * the local preview show goes on regardless.
     */
    private fun startPublishing() {
        publishJob?.cancel()
        publishJob = lifecycleScope.launch {
            if (!SessionStore.isSignedIn) return@launch
            val title = binding.etStreamTitle.text.toString().trim().ifEmpty { "Unreal Star Live" }
            val room = UnrealStarApiClient.createRoom(title, selectedMode).getOrNull()
                ?: return@launch
            liveRoomId = room.room_id
            val client = RtcSignalClient(room.room_id, object : RtcSignalClient.Listener {
                override fun onConnected() {
                    signalClient?.sendJoin()
                }

                override fun onChat(fromUserId: Long, nickname: String, text: String) {
                    addChatMessage(fromUserId, nickname, text)
                }

                override fun onViewerCount(viewers: Long) {
                    binding.tvViewersBadge.text = "👁 $viewers"
                }

                override fun onRtcSignal(fromUserId: Long, kind: String, sdp: String) {
                    val publisher = rtcPublisher ?: return
                    when (kind) {
                        "request" -> lifecycleScope.launch {
                            try {
                                val offer = publisher.openViewerSession(fromUserId)
                                signalClient?.sendSignal(fromUserId, "offer", offer.description)
                            } catch (t: Throwable) {
                                android.util.Log.w("UnrealStarRtc", "offer to $fromUserId failed", t)
                            }
                        }
                        "answer" -> lifecycleScope.launch {
                            try {
                                publisher.applyViewerAnswer(fromUserId, sdp)
                            } catch (t: Throwable) {
                                // Never silently: a rejected answer is exactly
                                // when you need the reason.
                                android.util.Log.w("UnrealStarRtc", "answer from $fromUserId rejected", t)
                                rtcPublisher?.closeViewerSession(fromUserId)
                            }
                        }
                        "ice" -> RtcPublisher.parseIce(sdp)?.let { (mid, mline, cand) ->
                            publisher.addRemoteIce(fromUserId, mid, mline, cand)
                        }
                    }
                }
            })
            if (!client.connect()) return@launch
            signalClient = client
            client.sendJoin()
            try {
                val publisher = RtcPublisher(
                    this@CameraLiveActivity,
                    object : RtcPublisherListener {
                        override fun onViewerIce(viewerId: Long, candidate: IceCandidate) {
                            signalClient?.sendSignal(
                                viewerId,
                                "ice",
                                "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}",
                            )
                        }

                        override fun onViewerConnectionChange(
                            viewerId: Long,
                            state: PeerConnection.IceConnectionState,
                        ) {
                            android.util.Log.d("UnrealStarRtc", "viewer $viewerId ICE $state")
                        }

                        override fun onPublisherError(where: String, message: String) {
                            android.util.Log.w("UnrealStarRtc", "publisher $where: $message")
                        }
                    },
                )
                publisher.init()
                // No attachCameraFrames: the activity-owned analyzer in
                // startCamera already forwards every frame to rtcPublisher via
                // onCameraFrame, so media flows without overwriting the face
                // tracker fan-out.
                rtcPublisher = publisher
                android.util.Log.d(
                    "UnrealStarRtc",
                    "publishing ready room=${room.room_id}, waiting for viewer requests",
                )
            } catch (t: Throwable) {
                // Media-stack failures (missing native symbols, broken vendor
                // WebRTC builds) must degrade to preview-only, never crash the
                // call screen: NoClassDefFoundError is an Error, not Exception.
                android.util.Log.w("UnrealStarRtc", "publish unavailable, preview-only", t)
                try { client.close() } catch (_: Exception) {}
                signalClient = null
                try { rtcPublisher?.close() } catch (_: Exception) {}
                rtcPublisher = null
            }
        }
    }

    private fun showEndedSummary() {
        val mins = liveDurationSeconds / 60
        val secs = liveDurationSeconds % 60
        binding.tvStatDurationValue.text = if (mins > 0) "${mins}min" else "${secs}s"
        binding.tvStatViewersValue.text = binding.tvViewersBadge.text.toString()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val useGpu = filterEngine.mode == FilterEngine.Mode.GPU && !gpuDisabled
            gpuActive = useGpu
            binding.gpuPreviewView.visibility = if (useGpu) View.VISIBLE else View.GONE
            binding.previewView.visibility = if (useGpu) View.GONE else View.VISIBLE

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            // Analyzer at a streamable resolution; frames flow into the
            // WebRTC publisher when live (harmless else). 720p keeps the
            // encoder's bitrate target sane on mobile uplinks.
            // ResolutionSelector replaces the deprecated setTargetResolution
            // and pins the fallback ladder explicitly.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                android.util.Size(1280, 720),
                                androidx.camera.core.resolutionselector.ResolutionStrategy
                                    .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase = analysis

            try {
                cameraProvider.unbindAll()
                // Single activity-owned analyzer: fan frames out to the WebRTC
                // pump (when live) + the throttled face tracker. The publisher
                // must NOT overwrite this via attachCameraFrames.
                val exec = (faceExecutor?.takeIf { !it.isShutdown })
                    ?: java.util.concurrent.Executors.newSingleThreadExecutor().also { faceExecutor = it }
                faceTracker?.mirrorFront = lensFacing == CameraSelector.LENS_FACING_FRONT
                val fanOut: (androidx.camera.core.ImageProxy) -> Unit = { proxy ->
                    try {
                        try { rtcPublisher?.onCameraFrame(proxy) } catch (_: Throwable) {}
                        try { faceTracker?.offer(proxy) } catch (_: Throwable) {}
                        try {
                            FrameCapture.takeFullFrame(
                                proxy,
                                lensFacing == CameraSelector.LENS_FACING_FRONT
                            )?.let { onPosterFrame(it) }
                        } catch (_: Throwable) {}
                    } finally {
                        try { proxy.close() } catch (_: Throwable) {}
                    }
                }
                if (!useGpu) {
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }
                    camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        analysis,
                    )
                    analysis.setAnalyzer(exec, fanOut)
                    updateOverlay()
                    updateExposureRange()
                } else {
                    // GLES live path: CameraX streams into our SurfaceTexture;
                    // each frame renders through the active filter shader.
                    binding.gpuPreviewView.mirrorFront =
                        lensFacing == CameraSelector.LENS_FACING_FRONT
                    binding.gpuPreviewView.filter = filterEngine.activeFilter
                    binding.gpuPreviewView.useSurface { surf ->
                        try {
                            val preview = Preview.Builder()
                                .setTargetRotation(binding.gpuPreviewView.display?.rotation ?: 0)
                                .build()
                            preview.setSurfaceProvider(exec) { request ->
                                binding.gpuPreviewView.setStreamSize(
                                    request.resolution.width, request.resolution.height
                                )
                                request.setTransformationInfoListener(exec) { info ->
                                    binding.gpuPreviewView.setDisplayRotation(info.rotationDegrees)
                                }
                                request.provideSurface(
                                    surf, exec,
                                    androidx.core.util.Consumer { /* invalidated on rebind; next useSurface replaces */ }
                                )
                            }
                            camera = cameraProvider.bindToLifecycle(
                                this,
                                cameraSelector,
                                preview,
                                analysis,
                            )
                            analysis.setAnalyzer(exec, fanOut)
                            updateOverlay()
                            updateExposureRange()
                        } catch (t: Throwable) {
                            android.util.Log.w("GpuPreview", "GPU bind failed, falling back to PreviewView", t)
                            runOnUiThread {
                                gpuActive = false
                                gpuDisabled = true
                                binding.gpuPreviewView.visibility = View.GONE
                                binding.previewView.visibility = View.VISIBLE
                                startCamera()
                            }
                        }
                    }
                }
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateOverlay() {
        binding.ivFilterOverlay.post {
            val w = binding.ivFilterOverlay.width.takeIf { it > 0 } ?: 750
            val h = binding.ivFilterOverlay.height.takeIf { it > 0 } ?: 1334
            val composite = filterManager.applyCompositeFilter(w, h)
            binding.ivFilterOverlay.setImageBitmap(composite)
        }
    }

    /**
     * Exposure range is device-specific: discover it after every bind and
     * size the slider to it. Unsupported devices lose the row instead of a
     * dead slider. The stored index survives camera switches.
     */
    private fun updateExposureRange() {
        val cam = camera ?: return
        val state = try { cam.cameraInfo.exposureState } catch (_: Throwable) { null } ?: return
        val supported = try { state.isExposureCompensationSupported } catch (_: Throwable) { false }
        runOnUiThread {
            if (!supported) {
                binding.rowExposure.visibility = View.GONE
                return@runOnUiThread
            }
            binding.rowExposure.visibility = View.VISIBLE
            val range = state.exposureCompensationRange
            exposureLower = range.lower
            exposureUpper = range.upper
            binding.sbExposure.max = (exposureUpper - exposureLower).coerceAtLeast(0)
            exposureIndex = exposureIndex.coerceIn(exposureLower, exposureUpper)
            binding.sbExposure.progress = exposureIndex - exposureLower
            binding.tvExposureValue.text = exposureIndex.toString()
            applyExposure()
        }
    }

    private fun applyExposure() {
        val cam = camera ?: return
        try {
            cam.cameraControl.setExposureCompensationIndex(exposureIndex)
        } catch (t: Throwable) {
            android.util.Log.w("CameraLive", "exposure set failed", t)
        }
    }

    /**
     * Poster bake: runs on Dispatchers.Default (EGL/CPU blur block), then
     * swaps the thumbnail on main. The raw frame is recycled unless the bake
     * returned it as-is (no-op path).
     */
    /** Arms a one-shot analyzer capture for the poster (with failure watchdog). */
    private fun armPosterCapture() {
        if (FrameCapture.armed) return
        FrameCapture.armed = true
        val gen = ++posterGen
        Toast.makeText(this, "Capturing poster…", Toast.LENGTH_SHORT).show()
        // Watchdog: a failed decode would otherwise leave the user hanging.
        binding.tvChangePoster.postDelayed({
            if (gen == posterGen && FrameCapture.armed) {
                FrameCapture.armed = false
                Toast.makeText(this, "Capture failed — try again", Toast.LENGTH_SHORT).show()
            }
        }, 3000)
    }

    /** Gallery upload: decode capped at 1080px, used as-is (no beauty bake). */
    private fun onPosterPicked(uri: android.net.Uri) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val bmp = try {
                decodePosterUri(uri)
            } catch (t: Throwable) {
                android.util.Log.w("PosterUpload", "decode failed", t)
                null
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (bmp != null) {
                    posterGen++
                    showPoster(bmp)
                    Toast.makeText(this@CameraLiveActivity, "Poster updated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@CameraLiveActivity, "Couldn't load that image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodePosterUri(uri: android.net.Uri): android.graphics.Bitmap? {
        val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val maxSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        if (maxSide <= 0) return null
        var sample = 1
        while (maxSide / sample > 1080) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /** Swaps the thumbnail from placeholder styling to a filled photo. */
    private fun showPoster(bmp: android.graphics.Bitmap) {
        binding.ivPosterThumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        binding.ivPosterThumb.setPadding(0, 0, 0, 0)
        binding.ivPosterThumb.imageTintList = null
        binding.ivPosterThumb.setImageBitmap(bmp)
    }

    private fun onPosterFrame(raw: android.graphics.Bitmap) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val baked = try {
                filterEngine.processBitmap(
                    raw,
                    filterEngine.activeFilter,
                    BeautyParams(filterManager.smooth, filterManager.whiten)
                )
            } catch (t: Throwable) {
                android.util.Log.w("PosterCapture", "bake failed, using raw frame", t)
                raw
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    posterGen++
                    // Drop the placeholder styling: real photo fills the frame.
                    showPoster(baked)
                    Toast.makeText(this@CameraLiveActivity, "Poster updated", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                } finally {
                    if (baked !== raw) runCatching { raw.recycle() }
                }
            }
        }
    }

    private fun setupFilterPanel() {
        binding.tvFilterEngineMode.text = if (filterEngine.mode == FilterEngine.Mode.GPU) {
            "GPU mode — full color engine"
        } else {
            "Lite mode — battery-friendly filters"
        }

        // Tab switching: Filters | Beauty | Stickers
        fun selectTab(tab: Int) {
            binding.pageFilters.visibility = if (tab == 0) View.VISIBLE else View.GONE
            binding.pageBeauty.visibility = if (tab == 1) View.VISIBLE else View.GONE
            binding.pageStickers.visibility = if (tab == 2) View.VISIBLE else View.GONE
            binding.btnTabFilters.setTextColor(
                if (tab == 0) getColor(android.R.color.white) else getColor(android.R.color.darker_gray))
            binding.btnTabBeauty.setTextColor(
                if (tab == 1) getColor(android.R.color.white) else getColor(android.R.color.darker_gray))
            binding.btnTabStickers.setTextColor(
                if (tab == 2) getColor(android.R.color.white) else getColor(android.R.color.darker_gray))
        }
        binding.btnTabFilters.setOnClickListener { selectTab(0) }
        binding.btnTabBeauty.setOnClickListener { selectTab(1) }
        binding.btnTabStickers.setOnClickListener { selectTab(2) }

        // Instagram swipe filters
        val colorAdapter = FilterItemAdapter(INSTAGRAM_FILTERS.map { it.name }) { pos ->
            val f = INSTAGRAM_FILTERS[pos]
            filterEngine.applyLive(f, binding.vFilterTint)
            if (gpuActive) {
                // The shader does the work on the GLES path — the tint
                // overlay would double-apply the look.
                binding.vFilterTint.visibility = View.GONE
                binding.gpuPreviewView.filter = f
            }
        }
        // Tint the thumb so each row previews its look.
        colorAdapter.tintProvider = { pos -> INSTAGRAM_FILTERS[pos].tintColor }
        binding.rvColorFilters.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvColorFilters.adapter = colorAdapter

        // Stickers
        val stickerAdapter = FilterItemAdapter(
            BUILTIN_STICKERS.map { if (it.emoji.isEmpty()) it.name else "${it.emoji}\n${it.name}" }
        ) { pos ->
            selectedSticker = if (BUILTIN_STICKERS[pos].id == "none") null else BUILTIN_STICKERS[pos]
            filterEngine.applySticker(selectedSticker, binding.tvStickerOverlay)
            // Snap to the last tracked face; otherwise the overlay stays
            // center-anchored until the next detection arrives.
            trackedFace?.let { (cx, cy, _) -> positionSticker(cx, cy) }
        }
        binding.rvStickers.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvStickers.adapter = stickerAdapter

        // Beauty makeup mattes (existing 5-category pipeline)
        binding.chipGroupCategories.removeAllViews()
        filterManager.categories.forEachIndexed { index, cat ->
            val chip = Chip(this).apply {
                text = cat.name
                isCheckable = true
                isChecked = index == 0
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        matteAdapter.updateItems(cat.items)
                    }
                }
            }
            binding.chipGroupCategories.addView(chip)
        }

        matteAdapter = MatteItemAdapter(filterManager.categories[0].items) { item ->
            filterManager.selectedItem = item
            updateOverlay()
        }
        binding.rvMatteItems.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvMatteItems.adapter = matteAdapter

        binding.sbFilterStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                filterManager.intensity = progress / 100f
                binding.tvStrengthValue.text = "$progress%"
                updateOverlay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Beauty sliders. GPU path: real in-shader smoothing/whiten/reshape.
        // Lite path: soft-glow overlay + stored params (previous behavior).
        fun beautyChanged() {
            if (gpuActive) {
                binding.vBeautySoft.visibility = View.GONE
                binding.gpuPreviewView.setBeauty(filterManager.smooth, filterManager.whiten)
            } else {
                filterEngine.applyBeautyLive(filterManager.smooth, filterManager.whiten, binding.vBeautySoft)
            }
        }
        fun reshapeChanged() {
            if (gpuActive) {
                binding.gpuPreviewView.setReshape(filterManager.slimFace, filterManager.bigEyes)
            }
        }
        binding.sbSmooth.setOnSeekBarChangeListener(simpleSeek {
            filterManager.smooth = it / 100f
            binding.tvSmoothValue.text = "$it%"
            beautyChanged()
        })
        binding.sbWhiten.setOnSeekBarChangeListener(simpleSeek {
            filterManager.whiten = it / 100f
            binding.tvWhitenValue.text = "$it%"
            beautyChanged()
        })
        binding.sbSlim.setOnSeekBarChangeListener(simpleSeek {
            filterManager.slimFace = it / 100f
            binding.tvSlimValue.text = "$it%"
            reshapeChanged()
        })
        binding.sbBigEyes.setOnSeekBarChangeListener(simpleSeek {
            filterManager.bigEyes = it / 100f
            binding.tvBigEyesValue.text = "$it%"
            reshapeChanged()
        })
        binding.sbExposure.setOnSeekBarChangeListener(simpleSeek {
            exposureIndex = exposureLower + it
            binding.tvExposureValue.text = exposureIndex.toString()
            applyExposure()
        })
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun setupChatList() {
        chatAdapter = ChatAdapter(chatMessages)
        binding.rvChatMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        addChatMessage(1002, "Aarav", "Welcome everyone to the stream! 🚀")
        addChatMessage(1003, "Priya", "Loving the video clarity and filters! ✨")
    }

    private fun addChatMessage(senderId: Long, nickname: String, text: String) {
        chatMessages.add(ChatMessage(senderId, nickname, text))
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        binding.rvChatMessages.scrollToPosition(chatMessages.size - 1)
    }

    private fun setupListeners() {
        binding.btnGoLive.setOnClickListener {
            if (goingLive) return@setOnClickListener
            goingLive = true
            binding.btnGoLive.isEnabled = false
            binding.btnGoLive.alpha = 0.6f
            setScreenState(LiveScreenState.COUNTDOWN)
        }

        // Poster: capture from camera or upload from gallery.
        binding.tvChangePoster.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.live_change_poster)
                .setItems(arrayOf("Capture from camera", "Upload from gallery")) { _, which ->
                    if (which == 0) armPosterCapture() else galleryPickerLauncher.launch("image/*")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnCloseSetup.setOnClickListener { finish() }
        binding.btnCloseCountdown.setOnClickListener {
            countdownTimer?.cancel()
            finish()
        }
        binding.btnCloseStream.setOnClickListener {
            setScreenState(LiveScreenState.ENDED)
        }
        binding.btnCloseEnded.setOnClickListener { finish() }

        binding.btnToggleFilters.setOnClickListener {
            binding.filterPanel.visibility = if (binding.filterPanel.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                addChatMessage(SessionStore.userId, "You", text)
                signalClient?.sendChat(text)
                binding.etChatInput.setText("")
            }
        }

        binding.btnToggleGifts.setOnClickListener {
            val sheet = GiftBottomSheet(
                roomId = if (liveRoomId > 0L) liveRoomId else 1L,
                recipientId = 9999L
            ) { gift, count, comboSeq, _ ->
                addChatMessage(SessionStore.userId, "System", "🎁 Sent ${count}x ${gift.name} (Combo x$comboSeq)")
                signalClient?.sendChat("🎁 Host sent ${count}x ${gift.name}!")
            }
            sheet.show(supportFragmentManager, GiftBottomSheet.TAG)
        }


        binding.btnToggleWheel.setOnClickListener {
            val dialog = LuckyWheelDialog(roomId = 1L) { remainingCoins ->
                addChatMessage(SessionStore.userId, "System", "🎡 Wheel balance updated: $remainingCoins Coins")
            }
            dialog.show(supportFragmentManager, LuckyWheelDialog.TAG)
        }

        binding.tvLiveEdgeBadge.setOnClickListener { triggerPkMatch() }
        binding.tvPkBadge.setOnClickListener { triggerPkMatch() }
        binding.btnPk.setOnClickListener { triggerPkMatch() }

        binding.btnEnvelope.setOnClickListener {
            DirectMessageBottomSheet(1001L, "Trendo Official")
                .show(supportFragmentManager, "dm_chat")
        }

        binding.btnPhone.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📞 Co-Host & Guest Connection")
                .setMessage("Invite active viewers or friends to join multi-guest video or audio seats.")
                .setPositiveButton("Invite Guest") { _, _ ->
                    Toast.makeText(this, "📡 Invitation broadcasted to viewers", Toast.LENGTH_SHORT).show()
                    addChatMessage(SessionStore.userId, "System", "📞 Co-host invitation sent to viewers")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnMore.setOnClickListener {
            val options = arrayOf(
                "🔄 Flip Camera",
                "⚙️ Stream Settings & Bitrate",
                "🛡️ Room Moderation & Banned Words",
                "🧧 Drop Red Packet",
                "✨ Special Beauty Filters"
            )
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Broadcast Tools")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                CameraSelector.LENS_FACING_BACK
                            } else {
                                CameraSelector.LENS_FACING_FRONT
                            }
                            startCamera()
                        }
                        1 -> showConnectionDialog()
                        2 -> Toast.makeText(this, "🛡️ AI Auto-Moderation Active (Safe Mode)", Toast.LENGTH_SHORT).show()
                        3 -> {
                            RedPacketBottomSheet(roomId = if (liveRoomId > 0) liveRoomId else 1L) {
                                addChatMessage(SessionStore.userId, "System", "🧧 Dropped a Lucky Red Packet!")
                            }.show(supportFragmentManager, RedPacketBottomSheet.TAG)
                        }
                        4 -> {
                            binding.filterPanel.visibility = View.VISIBLE
                        }
                    }
                }
                .show()
        }

        binding.tvTopupBadge.setOnClickListener {
            CoinStoreBottomSheet().show(supportFragmentManager, CoinStoreBottomSheet.TAG)
        }

        binding.hostChipRow.setOnClickListener {
            Toast.makeText(this, "👤 Host: ${binding.tvHostName.text}", Toast.LENGTH_SHORT).show()
        }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            startCamera()
        }

        binding.btnStickerBeauty.setOnClickListener {
            binding.filterPanel.visibility = View.VISIBLE
        }

        binding.btnConnectionSpeed.setOnClickListener {
            showConnectionDialog()
        }

        // ---- Pre-live setup controls (all were dead clicks before) ----
        fun selectMode(mode: String) {
            selectedMode = mode
            val white = getColor(android.R.color.white)
            val dim = ContextCompat.getColor(this, R.color.live_mode_dim)
            val pill = ContextCompat.getDrawable(this, R.drawable.bg_chip_translucent)
            binding.tabLive.setTextColor(if (mode == "live") white else dim)
            binding.tabChatParty.setTextColor(if (mode == "party") white else dim)
            binding.tabVideoParty.setTextColor(if (mode == "video") white else dim)
            binding.tabLive.setTypeface(null, if (mode == "live") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            binding.tabChatParty.setTypeface(null, if (mode == "party") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            binding.tabVideoParty.setTypeface(null, if (mode == "video") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            binding.tabLive.background = if (mode == "live") pill else null
            binding.tabChatParty.background = if (mode == "party") pill else null
            binding.tabVideoParty.background = if (mode == "video") pill else null
        }
        binding.tabLive.setOnClickListener { selectMode("live") }
        binding.tabChatParty.setOnClickListener { selectMode("party") }
        binding.tabVideoParty.setOnClickListener { selectMode("video") }
        selectMode(selectedMode)

        binding.tvHidePosterTip.setOnClickListener {
            binding.tvPosterTip.visibility = View.GONE
            binding.tvHidePosterTip.visibility = View.GONE
        }

        binding.tvShareTwitter.setOnClickListener {
            shareInvite("com.twitter.android", "X")
        }
        binding.tvShareFacebook.setOnClickListener {
            shareInvite("com.facebook.katana", "Facebook")
        }

        val talentInfo = {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.live_official_talent)
                .setMessage("Official Talent and exposure grades are awarded from your streaming history — hours live, gifts earned, and standing. Keep streaming to climb. Applications are not open in this build.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.btnOfficialTalent.setOnClickListener { talentInfo() }
        binding.gradeCard.setOnClickListener { talentInfo() }
    }

    /** Invite share: targeted app first, generic chooser as fallback. */
    private fun shareInvite(targetPackage: String, appName: String) {
        val text = "Watch me live on Unreal Star — join my room!"
        val targeted = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            setPackage(targetPackage)
        }
        val pm = packageManager
        val handles = pm.queryIntentActivities(targeted, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        if (handles.isNotEmpty()) {
            try {
                startActivity(targeted)
                return
            } catch (_: Exception) {
            }
        }
        try {
            startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    },
                    "Invite via…"
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, "$appName is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Real connection info: transport + link speed, from ConnectivityManager. */
    private fun showConnectionDialog() {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "Offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
        val down = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 }
            ?.let { "${it / 1000} Mbps down" } ?: "speed unknown"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.live_connection_speed)
            .setMessage("$transport • $down\nStreaming quality adapts automatically.")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sendSampleGift() {
        lifecycleScope.launch {
            val giftReq = SendGiftRequest(
                room_id = 1L,
                recipient_id = 9999L,
                gift_id = "gift_sparkler",
                count = 1,
                combo_seq = 1,
                idempotency_key = "gift_${UUID.randomUUID()}"
            )
            val result = UnrealStarApiClient.sendGift(giftReq)
            result.onSuccess {
                Toast.makeText(this@CameraLiveActivity, "✨ Diwali Sparkler sent! (Remaining coins: ${it.remaining_coins})", Toast.LENGTH_SHORT).show()
                addChatMessage(SessionStore.userId, "System", "🎁 You sent Diwali Sparkler!")
                // Luxury full-screen effect when the art bundle ships
                // (assets/sparkler.pag); no-op until then.
                try {
                    PagGiftPlayer.playOnce(this@CameraLiveActivity, binding.pagGiftEffect, "sparkler.pag")
                } catch (_: Exception) {}
            }.onFailure {
                Toast.makeText(this@CameraLiveActivity, "Gifting note: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerPkMatch() {
        lifecycleScope.launch {
            Toast.makeText(this@CameraLiveActivity, "⚔️ Initiating PK Battle Match...", Toast.LENGTH_SHORT).show()
            val res = UnrealStarApiClient.requestPkMatch(liveRoomId)
            res.onSuccess { ticket ->
                addChatMessage(0L, "System", "⚔️ PK Battle search active (Ticket: $ticket)")
                signalClient?.sendChat("⚔️ PK Battle Challenge is LIVE!")
            }.onFailure {
                Toast.makeText(this@CameraLiveActivity, "PK: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class ChatAdapter(private val list: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {
        inner class ChatViewHolder(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val b = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ChatViewHolder(b)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val msg = list[position]
            if (!msg.familyBadge.isNullOrEmpty()) {
                holder.binding.tvFamilyTag.text = msg.familyBadge
                holder.binding.tvFamilyTag.visibility = View.VISIBLE
            } else {
                holder.binding.tvFamilyTag.visibility = View.GONE
            }
            if (msg.vipLevel > 0) {
                holder.binding.tvVipTag.text = "VIP ${msg.vipLevel}"
                holder.binding.tvVipTag.visibility = View.VISIBLE
            } else {
                holder.binding.tvVipTag.visibility = View.GONE
            }
            holder.binding.tvSender.text = "${msg.nickname}:"
            holder.binding.tvMessage.text = msg.message
        }

        override fun getItemCount() = list.size
    }

    inner class MatteItemAdapter(
        private var list: List<BeautyItem>,
        private val onSelect: (BeautyItem) -> Unit
    ) : RecyclerView.Adapter<MatteItemAdapter.MatteViewHolder>() {

        inner class MatteViewHolder(val binding: ItemBeautyFilterBinding) : RecyclerView.ViewHolder(binding.root)

        fun updateItems(newList: List<BeautyItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatteViewHolder {
            val b = ItemBeautyFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MatteViewHolder(b)
        }

        override fun onBindViewHolder(holder: MatteViewHolder, position: Int) {
            val item = list[position]
            holder.binding.tvMatteName.text = item.name
            holder.itemView.setOnClickListener {
                onSelect(item)
            }
        }

        override fun getItemCount() = list.size
    }

    /** Generic horizontal row for color filters + stickers (reuses the matte thumb layout). */
    inner class FilterItemAdapter(
        private val labels: List<String>,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<FilterItemAdapter.FilterViewHolder>() {

        var tintProvider: ((Int) -> Int)? = null
        private var selected = 0

        inner class FilterViewHolder(val binding: ItemBeautyFilterBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
            val b = ItemBeautyFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return FilterViewHolder(b)
        }

        override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
            holder.binding.tvMatteName.text = labels[position]
            tintProvider?.invoke(position)?.let { tint ->
                if (tint != android.graphics.Color.TRANSPARENT) {
                    holder.binding.ivMatteThumb.setBackgroundColor(tint)
                }
            }
            holder.binding.ivMatteThumb.alpha = if (position == selected) 1f else 0.55f
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    selected = pos
                    notifyDataSetChanged()
                    onSelect(pos)
                }
            }
        }

        override fun getItemCount() = labels.size
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_LIVE = "mode_live"
        const val MODE_SHORTS = "mode_shorts"
    }
}
