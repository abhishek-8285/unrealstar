package com.unrealstar.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.view.isVisible
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
import com.unrealstar.live.models.ChatMessage
import com.unrealstar.live.models.SendGiftRequest
import com.google.android.material.chip.Chip
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var trackedFace: FaceGeometry? = null
    /** True while the GLES live preview owns the camera stream (GPU-mode devices). */
    private var gpuActive: Boolean = false
    /** Latched when the GLES bind fails so the fallback doesn't retry-loop. */
    private var gpuDisabled: Boolean = false
    /** Pre-live room mode, chosen on the setup tabs and sent as categoryId. */
    private var selectedMode: String = "live"
    /** Poster capture generation: invalidates the watchdog on arrival. */
    private var posterGen: Int = 0
    /** True once the armed capture produced a frame (grab stage). */
    private var posterArrived: Boolean = false
    /** Latest displayed poster (upload source); null until first capture/pick. */
    private var lastPosterBitmap: android.graphics.Bitmap? = null
    /** Uploaded cover URL attached to the next createRoom; null = default. */
    private var pendingCoverUrl: String? = null
    private var posterUploadJob: kotlinx.coroutines.Job? = null    /** Bound camera for exposure control; re-set on every (re)bind. */
    private var camera: androidx.camera.core.Camera? = null
    /** Exposure compensation state (device range discovered after bind). */
    private var exposureIndex: Int = 0
    private var exposureLower: Int = 0
    private var exposureUpper: Int = 0
    /** Guards the Go Live button against double-tap room creation. */
    private var goingLive: Boolean = false
    /** Set on every successful bind; used to release the camera on pause. */
    private var cameraProviderRef: ProcessCameraProvider? = null
    /** Monotonic bind generation: stale async binds (rapid flips) abort. */
    private var cameraBindGen: Int = 0
    /** True while a ProcessCameraProvider bind is attaching its surfaces. */
    private var bindInFlight: Boolean = false
    /** A startCamera arrived mid-bind; rerun once that bind settles. */
    private var rebindPending: Boolean = false
    /** True when onPause released a live camera that onResume must restore. */
    private var pausedWithCamera: Boolean = false

    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var matteAdapter: MatteItemAdapter
    /** Color-filter row adapter; held so the one-shot thumb bakes can post. */
    private var colorFilterAdapter: FilterItemAdapter? = null
    /** One-shot guard: filter preview bakes run once per activity session. */
    private var filterThumbBakeStarted = false
    /** True while the armed grab is earmarked for the filter-thumbnail bake. */
    @Volatile private var thumbGrabPending = false

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
    /** Peak reported viewers this session (local fallback for ended stats). */
    private var peakViewers: Long = 0
    /** Active PK battle id, if the server started one in this room. */
    private var activePkId: String? = null
    /** Server close_room stats for the ended screen (null = offline close). */
    private var endedStats: com.unrealstar.live.models.CloseRoomResponse? = null
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
            Toast.makeText(this, getString(R.string.live_mic_denied), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.live_camera_required), Toast.LENGTH_LONG).show()
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
            binding.btnGoLive.text = getString(R.string.live_shorts_record_short)
        }

        setupChatList()
        setupFilterPanel()
        setupFaceTracking()
        setupListeners()
        setupPreviewTap()
        setScreenState(LiveScreenState.SETUP)

        // System back first dismisses the open overlay (Sticker/Beauty panel,
        // host chat row); only a second back leaves the live screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                android.util.Log.i(
                    "CameraLive",
                    "back: panel=${binding.filterPanel.isVisible}" +
                        " chatRow=${binding.chatInputRow.isVisible}"
                )
                when {
                    binding.filterPanel.isVisible ->
                        binding.filterPanel.isVisible = false
                    binding.chatInputRow.isVisible -> closeHostChat()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

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
        // A paused countdown must not fire while hidden.
        countdownTimer?.cancel()
        if (screenState == LiveScreenState.COUNTDOWN && !isFinishing) {
            goingLive = false
            try {
                binding.btnGoLive.isEnabled = true
                binding.btnGoLive.alpha = 1f
            } catch (_: Throwable) {}
            setScreenState(LiveScreenState.SETUP)
        }
        // Release the camera: no background capture (privacy, battery), and
        // the GPU consumer is paused so its buffer queue would otherwise
        // stall and resume black.
        if (camera != null) {
            pausedWithCamera = true
            cameraBindGen++ // abort any in-flight bind
            rebindPending = false
            try { analysisUseCase?.clearAnalyzer() } catch (_: Throwable) {}
            try { cameraProviderRef?.unbindAll() } catch (_: Throwable) {}
            camera = null
        }
    }

    override fun onResume() {
        super.onResume()
        try { binding.gpuPreviewView.onResume() } catch (_: Throwable) {}
        if (pausedWithCamera && !isFinishing && !isDestroyed) {
            pausedWithCamera = false
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
        liveDurationJob?.cancel()
        publishJob?.cancel()
        try { signalClient?.close() } catch (_: Exception) {}
        signalClient = null
        cameraBindGen++ // abort any in-flight bind
        overlayJob?.cancel()
        try { analysisUseCase?.clearAnalyzer() } catch (_: Throwable) {}
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
                // Accessibility: touch-only custom views must announce clicks.
                v.performClick()
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
            override fun onFace(face: FaceGeometry) {
                trackedFace = face
                // The GPU view maps frame fractions internally; the sticker
                // overlay needs view fractions mapped here.
                if (gpuActive) {
                    binding.gpuPreviewView.face = face
                }
                val (vcx, vcy) = toViewSpace(face, face.centerX, face.centerY)
                positionSticker(vcx, vcy)
            }
            override fun onNoFace() {
                trackedFace = null
                if (gpuActive) {
                    binding.gpuPreviewView.face = null
                }
            }
        }
        faceTracker = tracker
    }

    /** Upright-frame fractions -> preview-view fractions (center-crop aware). */
    private fun toViewSpace(face: FaceGeometry, fx: Float, fy: Float): Pair<Float, Float> {
        val vw = binding.ivFilterOverlay.width.takeIf { it > 0 }?.toFloat()
        val vh = binding.ivFilterOverlay.height.takeIf { it > 0 }?.toFloat()
        if (vw == null || vh == null) return Pair(fx, fy)
        return MatteGeometry.cropToView(
            fx, fy, face.frameW.toFloat(), face.frameH.toFloat(), vw, vh
        )
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
        closeHostChat()

        when (state) {
            LiveScreenState.SETUP -> {
                liveDurationJob?.cancel()
                countdownTimer?.cancel()
            }
            LiveScreenState.COUNTDOWN -> startCountdown()
            LiveScreenState.LIVE -> {
                startLiveDuration()
                binding.tvSpeedChip.text = connectionLabel()
                // Fresh session counters: stats from a previous stream must
                // never leak into this one's ended screen. (pendingCoverUrl
                // survives: it points at an immutable upload and is
                // overwritten by the next capture/pick.)
                peakViewers = 0
                activePkId = null
                endedStats = null
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
                // Stop the frame pump BEFORE disposing the publisher: the
                // activity owns the analyzer and in-flight frames must never
                // hit a disposed video source.
                try { analysisUseCase?.clearAnalyzer() } catch (_: Throwable) {}
                try { rtcPublisher?.close() } catch (_: Exception) {}
                rtcPublisher = null
                showEndedSummary()
            }
        }
    }

    /**
     * Publish setup failed: tell the user, release the Go-Live button, and
     * return to setup. Never strand the user on a dead LIVE HUD.
     */
    private fun failPublish(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {}
        goingLive = false
        try {
            binding.btnGoLive.isEnabled = true
            binding.btnGoLive.alpha = 1f
        } catch (_: Throwable) {}
        if (!isFinishing && !isDestroyed) {
            setScreenState(LiveScreenState.SETUP)
        }
    }

    private fun startCountdown() {
        binding.tvCountdownNumber.text = "3"
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt() + 1
                binding.tvCountdownNumber.text = getString(R.string.live_countdown_seconds, seconds)
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
        binding.tvViewersBadge.text = getString(R.string.live_rec)
        binding.tvPkBadge.text = getString(R.string.live_shorts_timer)
        lifecycleScope.launch {
            for (sec in 1..15) {
                kotlinx.coroutines.delay(1000)
                binding.tvPkBadge.text = String.format(java.util.Locale.US, "🔴 00:%02d / 00:15", sec)
            }
            Toast.makeText(this@CameraLiveActivity, getString(R.string.live_shorts_done), Toast.LENGTH_LONG).show()
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
            if (!SessionStore.isSignedIn) {
                failPublish("Sign in required to go live")
                return@launch
            }
            val title = binding.etStreamTitle.text.toString().trim().ifEmpty { "Unreal Star Live" }
            val room = UnrealStarApiClient.createRoom(title, selectedMode, pendingCoverUrl).getOrNull()
            if (room == null) {
                failPublish("Couldn't create room — check connection and retry")
                return@launch
            }
            liveRoomId = room.room_id
            // Best-effort ICE refresh (time-limited TURN) before any viewer
            // PC is built; failure keeps the static list, never blocks live.
            try {
                UnrealStarApiClient.fetchIceServers().onSuccess { RtcIce.applyServerEntries(it.servers) }
            } catch (_: Throwable) {}
            val client = RtcSignalClient(room.room_id, object : RtcSignalClient.Listener {
                override fun onConnected() {
                    signalClient?.sendJoin()
                }

                override fun onChat(fromUserId: Long, nickname: String, text: String) {
                    addChatMessage(fromUserId, nickname, text)
                }

                override fun onViewerCount(viewers: Long) {
                    binding.tvViewersBadge.text = getString(R.string.live_viewers_eye, viewers)
                    peakViewers = maxOf(peakViewers, viewers)
                }

                override fun onPkStateChanged(pkId: String, pointsA: Long, pointsB: Long) {
                    activePkId = pkId
                    runOnUiThread {
                        binding.tvPkBadge.text = getString(R.string.live_pk_score, pointsA, pointsB)
                        binding.tvLiveEdgeBadge.text = getString(R.string.live_pk_live_badge)
                        addChatMessage(0L, "System", getString(R.string.live_pk_chat, pointsA, pointsB))
                    }
                }

                override fun onGift(senderId: Long, nickname: String, giftId: String, count: Int) {
                    val who = nickname.ifEmpty { "Someone" }
                    addChatMessage(senderId, who, "🎁 sent $giftId x$count!")
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
            if (!client.connect()) {
                failPublish("Couldn't reach the live server — check connection and retry")
                return@launch
            }
            signalClient = client
            // Join goes out exactly once, from onConnected — connect() is
            // async and outbound is held until the socket opens.
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
                // WebRTC builds) must never crash the call screen:
                // NoClassDefFoundError is an Error, not Exception. But a LIVE
                // HUD that can never publish is a lie — back out to setup.
                android.util.Log.w("UnrealStarRtc", "publish unavailable", t)
                try { client.close() } catch (_: Exception) {}
                signalClient = null
                try { rtcPublisher?.close() } catch (_: Exception) {}
                rtcPublisher = null
                failPublish("Live engine unavailable on this device")
            }
        }
    }

    private fun showEndedSummary() {
        // Server stats first (real duration/peak/diamonds), local counters
        // when the close call never landed (offline). Fans and shares have
        // no data source yet: an em-dash, never a fabricated zero.
        val stats = endedStats
        val totalSecs = stats?.duration_secs ?: liveDurationSeconds.toLong()
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        binding.tvStatDurationValue.text =
            if (mins > 0) getString(R.string.live_stat_duration_value, mins)
            else getString(R.string.live_stat_duration_secs_value, secs)
        val peak = stats?.peak_viewers ?: peakViewers
        binding.tvStatViewersValue.text = getString(R.string.live_viewers_eye, peak)
        binding.tvStatGemsValue.text = getString(R.string.live_stat_gems_value, stats?.diamonds_earned ?: 0)
        binding.tvStatFansValue.text = "—"
        binding.tvStatSharesValue.text = "—"
    }

    /**
     * Camera preview stream, both engines. The preview is drawn full-bleed
     * under a 1080x2412 panel: fill-center upscales whatever stream resolves,
     * so displayed sharpness is bounded by the stream's pixel columns.
     * Probed on-device 2026-10-18: with the YUV analysis stream active the
     * 4:3 pair ladder caps at 1440x1088 (requesting 2304x1728 still
     * negotiated 1440x1088 — a ~1.68x fill upscale over only ~59% of the
     * columns: the "blurry preview"), while the 16:9 ladder pairs without
     * the cap. Pin 16:9 and request its top rung (2160x1080 is the sensor's
     * own preferred-preview-size-for-video); CLOSEST_LOWER_THEN_HIGHER
     * steps to the 1920x10xx rung if the pair is rejected. The analysis
     * selector is pinned 16:9 as well (see below), so the pair stays
     * aspect-matched.
     */
    private fun previewResolutionSelector() =
        androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                androidx.camera.core.resolutionselector.AspectRatioStrategy
                    .RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    android.util.Size(2160, 1080),
                    androidx.camera.core.resolutionselector.ResolutionStrategy
                        .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                )
            )
            .build()

    /**
     * Asks for a camera rebind (resume, GL-surface heal, failed bind). Runs
     * at most one bind at a time and gives the HAL ~250 ms to detach the
     * previous attempt's surfaces: attaching a replacement pair before the
     * old one is released makes CameraX count both against the stream
     * combination budget and reject the new pair.
     */
    private fun scheduleRebind() {
        if (isFinishing || isDestroyed) return
        rebindPending = true
        if (bindInFlight) return
        rebindPending = false
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed &&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            ) {
                startCamera()
            }
        }, 250)
    }

    private fun startCamera() {
        if (isFinishing || isDestroyed) return
        if (bindInFlight) {
            // Coalesce overlapping requests (resume rebind, GL-surface heal).
            // Binding while another bind is still attaching its surfaces makes
            // CameraX count both pairs against the HAL's stream-combination
            // budget and reject the new one ("No supported surface
            // combination ... bind too many use cases").
            rebindPending = true
            return
        }
        bindInFlight = true
        val bindGen = ++cameraBindGen
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (isFinishing || isDestroyed || bindGen != cameraBindGen) {
                bindInFlight = false
                return@addListener
            }
            val cameraProvider = cameraProviderFuture.get()
            cameraProviderRef = cameraProvider
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
            // and pins the fallback ladder explicitly. The 16:9 strategy is
            // load-bearing: the default 4:3 ladder silently jumped the
            // 1280x720 request up to 1280x960, which both fattened every
            // NV21 pump frame (1.84 MB) and pinned the preview pair to the
            // 4:3 ceiling (see previewResolutionSelector). Pinned 16:9 the
            // request resolves exactly 1280x720.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            androidx.camera.core.resolutionselector.AspectRatioStrategy
                                .RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        )
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
                var analyzerSizeLogged = false
                val fanOut: (androidx.camera.core.ImageProxy) -> Unit = { proxy ->
                    try {
                        if (!analyzerSizeLogged) {
                            analyzerSizeLogged = true
                            android.util.Log.i(
                                "CameraLive",
                                "analyzer frame ${proxy.width}x${proxy.height} rot=${proxy.imageInfo.rotationDegrees}"
                            )
                        }
                        try { rtcPublisher?.onCameraFrame(proxy) } catch (_: Throwable) {}
                        try { faceTracker?.offer(proxy) } catch (_: Throwable) {}
                        // Poster grab copies bytes only (~1ms); decode + bake
                        // run on Dispatchers.Default so the pump never stalls.
                        try {
                            FrameCapture.grabCopy(
                                proxy,
                                lensFacing == CameraSelector.LENS_FACING_FRONT
                            )?.let { processPosterCopy(it) }
                        } catch (_: Throwable) {}
                    } finally {
                        try { proxy.close() } catch (_: Throwable) {}
                    }
                }
                if (!useGpu) {
                    val preview = Preview.Builder()
                        .setResolutionSelector(previewResolutionSelector())
                        .build().also {
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
                    reapplyLooks()
                    updateExposureRange()
                } else {
                    // GLES live path: CameraX streams into our SurfaceTexture;
                    // each frame renders through the active filter shader.
                    binding.gpuPreviewView.mirrorFront =
                        lensFacing == CameraSelector.LENS_FACING_FRONT
                    binding.gpuPreviewView.filter = filterEngine.activeFilter
                    binding.gpuPreviewView.onSurfaceReset = {
                        // A recreated GL context killed the surface pair the
                        // session was using (screen off/on, or the entrance
                        // transition racing the first bind). Rebind onto the
                        // fresh pair so the preview recovers instead of
                        // staying black; overlapping binds are harmless, the
                        // last one wins.
                        if (gpuActive) {
                            scheduleRebind()
                        }
                    }
                    binding.gpuPreviewView.useSurface { surf ->
                        try {
                            val preview = Preview.Builder()
                                .setTargetRotation(binding.gpuPreviewView.display?.rotation ?: Surface.ROTATION_0)
                                .setResolutionSelector(previewResolutionSelector())
                                .build()
                            preview.setSurfaceProvider(exec) { request ->
                                try {
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
                                } catch (t: Throwable) {
                                    // The pair can be released by a context
                                    // recreation between hand-off and wiring;
                                    // recover by rebinding instead of letting
                                    // the executor thread die or the bind
                                    // silently hang on a dead surface.
                                    android.util.Log.w("GpuPreview", "surface provide failed; rebinding", t)
                                    runOnUiThread {
                                        if (gpuActive) scheduleRebind()
                                    }
                                }
                            }
                            camera = cameraProvider.bindToLifecycle(
                                this,
                                cameraSelector,
                                preview,
                                analysis,
                            )
                            analysis.setAnalyzer(exec, fanOut)
                            updateOverlay()
                            reapplyLooks()
                            updateExposureRange()
                        } catch (t: Throwable) {
                            android.util.Log.w("GpuPreview", "GPU bind failed, falling back to PreviewView", t)
                            runOnUiThread {
                                gpuActive = false
                                gpuDisabled = true
                                binding.gpuPreviewView.visibility = View.GONE
                                binding.previewView.visibility = View.VISIBLE
                                scheduleRebind()
                            }
                        }
                    }
                }
            } catch (exc: Exception) {
                Toast.makeText(this, getString(R.string.live_camera_failed, exc.message), Toast.LENGTH_SHORT).show()
            }

            // This bind has settled (attached or failed); a coalesced request
            // that arrived mid-bind runs after the HAL has detached this
            // attempt's surfaces.
            bindInFlight = false
            if (rebindPending) scheduleRebind()
        }, ContextCompat.getMainExecutor(this))
    }

    private var overlayJob: kotlinx.coroutines.Job? = null
    private var overlayBitmap: android.graphics.Bitmap? = null

    private fun updateOverlay() {
        overlayJob?.cancel()
        // Dimensions read on main; compositing happens off-main, debounced.
        // The overlay bitmap is reused across face updates (erase + redraw),
        // so the 400ms tracking cadence churns zero allocations.
        val w = binding.ivFilterOverlay.width.takeIf { it > 0 } ?: 750
        val h = binding.ivFilterOverlay.height.takeIf { it > 0 } ?: 1334
        val face = trackedFace
        val ctx = applicationContext
        val canonical = filterManager.catalog?.face ?: CanonicalFace.EMPTY
        overlayJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.delay(80)
            val layers = try {
                filterManager.layers(ctx)
            } catch (_: Throwable) {
                emptyList()
            }
            val sim = face?.let {
                try {
                    MatteGeometry.similarityFor(it, canonical, w.toFloat(), h.toFloat(), true)
                } catch (_: Throwable) {
                    null
                }
            }
            var out = overlayBitmap
            if (out == null || out.width != w || out.height != h || out.isRecycled) {
                out?.let { runCatching { it.recycle() } }
                out = try {
                    createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                } catch (_: Throwable) {
                    null
                } ?: return@launch
            }
            val target = out
            if (sim != null && layers.isNotEmpty()) {
                try {
                    MatteCompositor.renderOverlayInto(target, layers, sim)
                } catch (_: Throwable) {
                    target.eraseColor(android.graphics.Color.TRANSPARENT)
                }
            } else {
                target.eraseColor(android.graphics.Color.TRANSPARENT)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isActive) return@withContext
                overlayBitmap = target
                try {
                    // Same object each time; setImageBitmap invalidates the view.
                    binding.ivFilterOverlay.setImageBitmap(target)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Pushes the active makeup layers to the GPU shader (ref swap only).
     * Resolved off-main; asset decode is cached after first load.
     */
    private fun pushGpuMattes() {
        if (!gpuActive) return
        val ctx = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                binding.gpuPreviewView.mattes = filterManager.layers(ctx)
                binding.gpuPreviewView.canonical =
                    filterManager.catalog?.face ?: CanonicalFace.EMPTY
            } catch (_: Throwable) {}
        }
    }

    /**
     * Re-applies the selected filter + beauty to whichever preview path is
     * active. Called after every (re)bind so a camera switch or GPU↔lite
     * fallback never silently resets the live look.
     */
    private fun reapplyLooks() {
        val f = filterEngine.activeFilter
        if (gpuActive) {
            binding.vFilterTint.visibility = View.GONE
            binding.vBeautySoft.visibility = View.GONE
            binding.gpuPreviewView.filter = f
            binding.gpuPreviewView.gradeStrength = filterEngine.gradeStrength(f)
            binding.gpuPreviewView.setBeauty(filterManager.smooth, filterManager.whiten)
            binding.gpuPreviewView.setReshape(filterManager.slimFace, filterManager.bigEyes)
            pushGpuMattes()
        } else {
            filterEngine.applyLive(f, binding.vFilterTint)
            filterEngine.applyBeautyLive(filterManager.smooth, filterManager.whiten, binding.vBeautySoft)
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
            binding.sbExposure.isEnabled = binding.sbExposure.max > 0
            exposureIndex = exposureIndex.coerceIn(exposureLower, exposureUpper)
            binding.sbExposure.progress = exposureIndex - exposureLower
            binding.tvExposureValue.text = getString(R.string.live_exposure_level, exposureIndex)
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
        posterArrived = false
        val gen = ++posterGen
        Toast.makeText(this, getString(R.string.live_poster_capturing), Toast.LENGTH_SHORT).show()
        // Watchdog: armed is consumed at grab time, so a failed decode/bake
        // would otherwise leave the user hanging — watch arrival instead.
        binding.tvChangePoster.postDelayed({
            if (gen == posterGen && !posterArrived) {
                FrameCapture.armed = false
                Toast.makeText(this, getString(R.string.live_poster_failed), Toast.LENGTH_SHORT).show()
            }
        }, 3000)
    }

    /** Gallery upload: decode capped at 1080px, used as-is (no beauty bake). */
    private fun onPosterPicked(uri: android.net.Uri) {
        // Cancel any armed camera capture: the gallery pick wins, and the
        // next analyzer frame must not overwrite it. A pending thumbnail
        // grab is dropped with it (the rows keep their tint swatches).
        FrameCapture.armed = false
        thumbGrabPending = false
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
                    Toast.makeText(this@CameraLiveActivity, getString(R.string.live_poster_updated), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@CameraLiveActivity, getString(R.string.live_poster_bad_image), Toast.LENGTH_SHORT).show()
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
        // Eager cover upload: compress + POST in background so Go-Live can
        // attach the URL without waiting. Silent failure — the room simply
        // opens with the default cover.
        lastPosterBitmap = bmp
        posterUploadJob?.cancel()
        posterUploadJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                val bytes = out.toByteArray()
                if (bytes.isEmpty() || bytes.size > 3 * 1024 * 1024) return@launch
                UnrealStarApiClient.uploadPoster(bytes).onSuccess { pendingCoverUrl = it }
            } catch (_: Throwable) {
            }
        }
    }

    private fun onPosterFrame(raw: android.graphics.Bitmap) {
        posterArrived = true
        val appCtx = applicationContext
        val face = trackedFace
        val layers = try {
            filterManager.layers(appCtx)
        } catch (_: Throwable) {
            emptyList()
        }
        val canonical = filterManager.catalog?.face ?: CanonicalFace.EMPTY
        val beauty = BeautyParams(
            smooth = filterManager.smooth,
            whiten = filterManager.whiten,
            slimFace = filterManager.slimFace,
            bigEyes = filterManager.bigEyes,
            sharpen = filterManager.sharpen,
            contrast = filterManager.contrast,
            saturation = filterManager.saturation,
            ruddy = filterManager.ruddy,
            eyeTeeth = filterManager.eyeTeeth
        )
        // Lite path folds its tint overlay into the still; the GPU path
        // already shows the grade live and leaves it out for the same reason.
        val bakeTint = !gpuActive
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val baked = try {
                filterEngine.processBitmap(
                    raw,
                    filterEngine.activeFilter,
                    beauty,
                    face,
                    layers,
                    canonical,
                    bakeTint
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
                    Toast.makeText(this@CameraLiveActivity, getString(R.string.live_poster_updated), Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                } finally {
                    if (baked !== raw) runCatching { raw.recycle() }
                }
            }
        }
    }

    /**
     * Decode + bake path for a grabbed analyzer copy. Runs the expensive
     * half off the analyzer thread; a decode failure surfaces through the
     * arm watchdog (posterArrived stays false).
     */
    private fun processPosterCopy(copy: FrameCapture.FrameCopy) {
        // The analyzer fan-out cannot tell a poster grab from the filter-
        // thumbnail grab; the arming side tags the copy here.
        val forThumbs = thumbGrabPending
        thumbGrabPending = false
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val raw = try {
                FrameCapture.decode(copy)
            } catch (t: Throwable) {
                android.util.Log.w("PosterCapture", "decode failed", t)
                null
            }
            if (raw != null) {
                if (forThumbs) bakeFilterThumbs(raw) else onPosterFrame(raw)
            }
        }
    }

    /**
     * Arms the one-shot analyzer grab that feeds the filter-row previews.
     * [FrameCapture.grabCopy] hands the copy to the fan-out's poster path, so
     * [processPosterCopy] routes it to [bakeFilterThumbs] while
     * [thumbGrabPending] is set. Runs once per activity session; if no frame
     * ever arrives the rows simply keep their tint swatches.
     */
    private fun armFilterThumbCapture() {
        if (filterThumbBakeStarted || thumbGrabPending) return
        filterThumbBakeStarted = true
        thumbGrabPending = true
        // Adopt a still-pending arm from a previous (destroyed) instance: it
        // would otherwise land in this session's poster path instead.
        if (!FrameCapture.armed) FrameCapture.armed = true
    }

    /**
     * Filter-row previews from the one-shot analyzer frame: downscale once,
     * then bake every [FilterEngine.filters] look through the same still path
     * the poster uses ([FilterEngine.processBitmap], off-main — its EGL lock
     * serializes the batch). Each finished cell is posted as it lands; a
     * failed bake leaves that cell's tint swatch.
     */
    private suspend fun bakeFilterThumbs(raw: android.graphics.Bitmap) {
        val targetW = 160
        val targetH = (raw.height * (targetW.toFloat() / raw.width)).toInt().coerceAtLeast(1)
        val small = try {
            raw.scale(targetW, targetH, true)
        } catch (t: Throwable) {
            android.util.Log.w("FilterThumbs", "downscale failed", t)
            null
        }
        // createScaledBitmap hands back the source when the size already matches.
        if (small == null || small !== raw) raw.recycle()
        val adapter = colorFilterAdapter
        if (small == null || adapter == null) return
        val bakeTint = !gpuActive
        val filters = filterEngine.filters
        for (index in filters.indices) {
            val f = filters[index]
            val baked = try {
                filterEngine.processBitmap(small, f, bakeTint = bakeTint)
            } catch (t: Throwable) {
                android.util.Log.w("FilterThumbs", "bake failed for ${f.id}", t)
                null
            } ?: continue
            // Throws on scope cancellation (activity destroyed) → bakes stop.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                adapter.setThumb(index, baked)
            }
        }
    }

    private fun setupFilterPanel() {
        binding.tvFilterEngineMode.text = if (filterEngine.mode == FilterEngine.Mode.GPU) {
            "GPU mode — full color engine"
        } else {
            "Lite mode — battery-friendly filters"
        }

        // Tab switching: Filters | Beauty | Stickers. The chip pill, text color
        // and weight all follow the selection — a static layout background left
        // the pill stranded on Filters after switching tabs.
        val tabButtons = listOf(binding.btnTabFilters, binding.btnTabBeauty, binding.btnTabStickers)
        fun selectTab(tab: Int) {
            binding.pageFilters.visibility = if (tab == 0) View.VISIBLE else View.GONE
            binding.pageBeauty.visibility = if (tab == 1) View.VISIBLE else View.GONE
            binding.pageStickers.visibility = if (tab == 2) View.VISIBLE else View.GONE
            tabButtons.forEachIndexed { index, tv ->
                val selected = index == tab
                tv.setTextColor(
                    if (selected) getColor(android.R.color.white) else getColor(android.R.color.darker_gray))
                tv.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                tv.background =
                    if (selected) androidx.appcompat.content.res.AppCompatResources.getDrawable(this@CameraLiveActivity, R.drawable.bg_chip_translucent) else null
            }
        }
        binding.btnTabFilters.setOnClickListener { selectTab(0) }
        binding.btnTabBeauty.setOnClickListener { selectTab(1) }
        binding.btnTabStickers.setOnClickListener { selectTab(2) }
        selectTab(0)

        // Instagram swipe filters: FilterEngine attaches the REA-grade
        // recipes (curve strips + overlay masks) from assets/grade/catalog.json.
        val litePath = filterEngine.mode != FilterEngine.Mode.GPU
        if (litePath) {
            // Slim/Big-eyes and the retouch/look knobs have no lite renderer:
            // hiding beats a silent no-op.
            binding.rowSlim.visibility = View.GONE
            binding.rowBigEyes.visibility = View.GONE
            binding.rowSharpen.visibility = View.GONE
            binding.rowContrast.visibility = View.GONE
            binding.rowSaturation.visibility = View.GONE
            binding.rowRuddy.visibility = View.GONE
            binding.rowEyesTeeth.visibility = View.GONE
        }
        // Carousel install is isolated so a runtime filter pack refresh can
        // rebuild the row (see refreshGrades call below).
        installFilterCarousel()
        armFilterThumbCapture()

        // Grade intensity seekbar: scales the active filter's curve mix and
        // overlay opacities together (GPU uniform, CPU bake, lite tint).
        binding.sbGradeStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val strength = progress / 100f
                binding.tvGradeValue.text = getString(R.string.live_percent_value, progress)
                val f = filterEngine.activeFilter
                if (f.recipe == null) return
                filterEngine.setGradeStrength(f, strength)
                if (gpuActive) {
                    binding.gpuPreviewView.gradeStrength = strength
                } else {
                    // Lite: re-apply the (scaled) tint approximation.
                    filterEngine.applyLive(f, binding.vFilterTint, strength)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Default selection is Normal (no recipe) — the row starts hidden and
        // appears with the first recipe-carrying filter.
        binding.rowGradeStrength.visibility = View.GONE

        // Stickers
        val stickerAdapter = FilterItemAdapter(
            BUILTIN_STICKERS.map { if (it.emoji.isEmpty()) it.name else "${it.emoji}\n${it.name}" },
            2
        ) { pos ->
            selectedSticker = if (BUILTIN_STICKERS[pos].id == "none") null else BUILTIN_STICKERS[pos]
            filterEngine.applySticker(selectedSticker, binding.tvStickerOverlay)
            // Snap to the last tracked face; otherwise the overlay stays
            // center-anchored until the next detection arrives.
            trackedFace?.let { face ->
                val (vcx, vcy) = toViewSpace(face, face.centerX, face.centerY)
                positionSticker(vcx, vcy)
            }
        }
        // Two-line label (emoji above name) + a per-sticker accent swatch.
        stickerAdapter.tintProvider = { pos -> BUILTIN_STICKERS[pos].tintColor }
        binding.rvStickers.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvStickers.adapter = stickerAdapter

        // Beauty makeup mattes: real catalog textures anchored by face tracking.
        if (filterManager.catalog == null) {
            filterManager.catalog = MatteCatalog.load(this)
        }
        var activeMatteCategory: MatteCategory? = null
        binding.chipGroupCategories.removeAllViews()
        val matteCats = filterManager.categories
        matteCats.forEachIndexed { index, cat ->
            if (index == 0) activeMatteCategory = cat
            val chip = Chip(this).apply {
                text = cat.name
                isCheckable = true
                isChecked = index == 0
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        activeMatteCategory = cat
                        matteAdapter.updateItems(cat.items)
                    }
                }
            }
            binding.chipGroupCategories.addView(chip)
        }

        matteAdapter = MatteItemAdapter(matteCats.firstOrNull()?.items ?: emptyList()) { item ->
            activeMatteCategory?.let { filterManager.select(it, item) }
            // Selecting adopts the item's default strength — mirror it on the
            // slider so its position never lies about the live intensity.
            binding.sbFilterStrength.progress = Math.round(filterManager.intensity * 100f)
            pushGpuMattes()
            updateOverlay()
        }
        binding.rvMatteItems.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvMatteItems.adapter = matteAdapter

        binding.sbFilterStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                filterManager.setIntensity(progress / 100f)
                binding.tvStrengthValue.text = getString(R.string.live_percent_value, progress)
                pushGpuMattes()
                updateOverlay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Beauty sliders. GPU path: real in-shader smoothing/retouch/look
        // knobs. Lite path: soft-glow overlay + stored params (previous
        // behavior); the retouch/look rows are hidden there.
        fun beautyChanged() {
            if (gpuActive) {
                binding.vBeautySoft.visibility = View.GONE
                binding.gpuPreviewView.setBeauty(
                    filterManager.smooth, filterManager.whiten,
                    filterManager.sharpen, filterManager.contrast,
                    filterManager.saturation, filterManager.ruddy,
                    filterManager.eyeTeeth
                )
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
            binding.tvSmoothValue.text = getString(R.string.live_percent_value, it)
            beautyChanged()
        })
        binding.sbWhiten.setOnSeekBarChangeListener(simpleSeek {
            filterManager.whiten = it / 100f
            binding.tvWhitenValue.text = getString(R.string.live_percent_value, it)
            beautyChanged()
        })
        binding.sbSlim.setOnSeekBarChangeListener(simpleSeek {
            filterManager.slimFace = it / 100f
            binding.tvSlimValue.text = getString(R.string.live_percent_value, it)
            reshapeChanged()
        })
        binding.sbBigEyes.setOnSeekBarChangeListener(simpleSeek {
            filterManager.bigEyes = it / 100f
            binding.tvBigEyesValue.text = getString(R.string.live_percent_value, it)
            reshapeChanged()
        })
        binding.sbSharpen.setOnSeekBarChangeListener(simpleSeek {
            filterManager.sharpen = it / 100f
            binding.tvSharpenValue.text = getString(R.string.live_percent_value, it)
            beautyChanged()
        })
        binding.sbContrast.setOnSeekBarChangeListener(simpleSeek {
            filterManager.contrast = it / 100f
            binding.tvContrastValue.text = getString(R.string.live_percent_value, it)
            beautyChanged()
        })
        binding.sbSaturation.setOnSeekBarChangeListener(simpleSeek {
            filterManager.saturation = it / 100f
            binding.tvSaturationValue.text = getString(R.string.live_percent_value, it)
            beautyChanged()
        })
        binding.sbRuddy.setOnSeekBarChangeListener(simpleSeek {
            filterManager.ruddy = it / 100f
            binding.tvRuddyValue.text = getString(R.string.live_percent_value, it)
            beautyChanged()
        })
        binding.sbEyesTeeth.setOnSeekBarChangeListener(simpleSeek {
            filterManager.eyeTeeth = it / 100f
            binding.tvEyesTeethValue.text = getString(R.string.live_percent_value, it)
            beautyChanged()
        })
        binding.sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Programmatic sets (rebind) must not re-fire the camera IPC.
                if (!fromUser) return
                exposureIndex = exposureLower + progress
                binding.tvExposureValue.text = getString(R.string.live_exposure_level, exposureIndex)
                applyExposure()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Runtime filter pack (Snap's file_url contract, GradePackLoader):
        // one background refresh per screen; a changed pack rebuilds the
        // carousel (new filters / overridden recipes) and re-bakes thumbs.
        lifecycleScope.launch {
            val refreshed = try {
                filterEngine.refreshGrades()
            } catch (_: Throwable) {
                -1
            }
            if (refreshed > 0) {
                installFilterCarousel()
                // Restart the one-shot thumb capture for the new entries.
                filterThumbBakeStarted = false
                thumbGrabPending = false
                armFilterThumbCapture()
            }
        }
    }

    /**
     * Installs (or re-installs) the color-filter row: rebuildable so a pack
     * refresh can swap the list. Selection is carried over by filter id;
     * thumbs restart empty and are re-baked by the one-shot frame.
     */
    private fun installFilterCarousel() {
        val filters = filterEngine.filters
        val startIdx = filters.indexOfFirst { it.id == filterEngine.activeFilter.id }
            .takeIf { it >= 0 } ?: 0
        val colorAdapter = FilterItemAdapter(filters.map { it.name }) { pos ->
            val f = filterEngine.filters[pos]
            filterEngine.applyLive(f, binding.vFilterTint)
            // Grade strength: per-filter (IG customization-param semantics).
            // Sync the slider and push the value into the active render path.
            val strength = filterEngine.gradeStrength(f)
            binding.rowGradeStrength.visibility = if (f.recipe == null) View.GONE else View.VISIBLE
            binding.sbGradeStrength.progress = Math.round(strength * 100f)
            binding.tvGradeValue.text = getString(R.string.live_percent_value, Math.round(strength * 100f))
            if (gpuActive) {
                // The shader does the work on the GLES path — the tint
                // overlay would double-apply the look.
                binding.vFilterTint.visibility = View.GONE
                binding.gpuPreviewView.filter = f
                binding.gpuPreviewView.gradeStrength = strength
            } else if (f.id == "inkwell" || f.id == "moon") {
                // A tint overlay cannot desaturate: live stays approximate on
                // lite devices, the baked capture is the real look.
                Toast.makeText(this, getString(R.string.live_bw_capture_only), Toast.LENGTH_SHORT).show()
            }
        }
        // Tint the thumb so each row previews its look.
        colorAdapter.tintProvider = { pos -> filterEngine.filters[pos].tintColor }
        colorAdapter.select(startIdx)
        // Sync the Intensity row with the restored selection (select() does
        // not fire the click handler).
        val restored = filterEngine.filters[startIdx]
        binding.rowGradeStrength.visibility = if (restored.recipe == null) View.GONE else View.VISIBLE
        val rStrength = filterEngine.gradeStrength(restored)
        binding.sbGradeStrength.progress = Math.round(rStrength * 100f)
        binding.tvGradeValue.text = getString(R.string.live_percent_value, Math.round(rStrength * 100f))
        if (gpuActive) binding.gpuPreviewView.gradeStrength = rStrength
        binding.rvColorFilters.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvColorFilters.adapter = colorAdapter
        colorFilterAdapter = colorAdapter
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    /** Host chat row toggle (opened from the Broadcast Tools menu). */
    private fun toggleHostChat() {
        if (binding.chatInputRow.isVisible) {
            closeHostChat()
        } else {
            binding.chatInputRow.visibility = View.VISIBLE
            binding.etChatInput.requestFocus()
            try {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(binding.etChatInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Throwable) {}
        }
    }

    /** Hides the host chat row and its keyboard (✕ button, system back). */
    private fun closeHostChat() {
        binding.chatInputRow.visibility = View.GONE
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.etChatInput.windowToken, 0)
        } catch (_: Throwable) {}
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
            // Host close: the server computes real stats (duration, peak,
            // diamonds) and fans the RoomClosed broadcast. Offline failure
            // still ends locally with device counters — never strand LIVE.
            binding.btnCloseStream.isEnabled = false
            val rid = liveRoomId
            lifecycleScope.launch {
                try {
                    if (rid > 0) {
                        UnrealStarApiClient.closeRoom(rid).onSuccess { endedStats = it }
                    }
                } catch (_: Throwable) {
                } finally {
                    try { binding.btnCloseStream.isEnabled = true } catch (_: Throwable) {}
                    if (!isFinishing && !isDestroyed) {
                        setScreenState(LiveScreenState.ENDED)
                    }
                }
            }
        }
        binding.btnCloseEnded.setOnClickListener { finish() }

        binding.btnToggleFilters.setOnClickListener {
            binding.filterPanel.isVisible = !binding.filterPanel.isVisible
        }

        fun sendHostChat() {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isEmpty()) return
            // Local echo always; broadcast when connected.
            addChatMessage(SessionStore.userId, "You", text)
            try { signalClient?.sendChat(text) } catch (_: Throwable) {}
            binding.etChatInput.setText("")
        }
        binding.btnSendChat.setOnClickListener { sendHostChat() }
        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendHostChat()
                true
            } else false
        }

        binding.btnToggleGifts.setOnClickListener {
            val sheet = GiftBottomSheet(
                roomId = if (liveRoomId > 0L) liveRoomId else 1L,
                recipientId = 9999L
            ) { gift, count, comboSeq, _ ->
                addChatMessage(SessionStore.userId, "System", getString(R.string.live_gift_chat_host, count, gift.name, comboSeq))
                signalClient?.sendChat("🎁 Host sent ${count}x ${gift.name}!")
            }
            sheet.show(supportFragmentManager, GiftBottomSheet.TAG)
        }


        binding.btnToggleWheel.setOnClickListener {
            supportFragmentManager.setFragmentResultListener(LuckyWheelDialog.RESULT_KEY, this) { _, bundle ->
                val remainingCoins = bundle.getLong(LuckyWheelDialog.RESULT_COINS, -1L)
                if (remainingCoins >= 0) {
                    addChatMessage(SessionStore.userId, "System", "🎡 Wheel balance updated: $remainingCoins Coins")
                }
            }
            LuckyWheelDialog.newInstance(1L).show(supportFragmentManager, LuckyWheelDialog.TAG)
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
                    Toast.makeText(this, getString(R.string.live_invite_sent), Toast.LENGTH_SHORT).show()
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
                "✨ Special Beauty Filters",
                "💬 Host Chat"
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
                        2 -> Toast.makeText(this, getString(R.string.live_automod), Toast.LENGTH_SHORT).show()
                        3 -> {
                            RedPacketBottomSheet(roomId = if (liveRoomId > 0) liveRoomId else 1L) {
                                addChatMessage(SessionStore.userId, "System", "🧧 Dropped a Lucky Red Packet!")
                            }.show(supportFragmentManager, RedPacketBottomSheet.TAG)
                        }
                        4 -> {
                            binding.filterPanel.visibility = View.VISIBLE
                        }
                        5 -> {
                            toggleHostChat()
                        }
                    }
                }
                .show()
        }

        binding.tvTopupBadge.setOnClickListener {
            CoinStoreBottomSheet().show(supportFragmentManager, CoinStoreBottomSheet.TAG)
        }

        binding.tvSpeedChip.setOnClickListener {
            showConnectionDialog()
        }

        binding.tvViewersBadge.setOnClickListener {
            Toast.makeText(this, getString(R.string.live_audience, binding.tvViewersBadge.text), Toast.LENGTH_SHORT).show()
        }

        binding.hourStarRow.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⭐ Hourly Star Challenge")
                .setMessage("Collect gifts to level up your star rating! Current progress: Lv. 0/5 Star. Top broadcasters receive trending badge & bonus diamonds.")
                .setPositiveButton("Got It", null)
                .show()
        }

        binding.ivHourStarGift.setOnClickListener {
            GiftBottomSheet(
                roomId = if (liveRoomId > 0) liveRoomId else 1L,
                recipientId = 9999L
            ).show(supportFragmentManager, GiftBottomSheet.TAG)
        }

        binding.hostChipRow.setOnClickListener {
            Toast.makeText(this, getString(R.string.live_host_label, binding.tvHostName.text), Toast.LENGTH_SHORT).show()
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
            binding.filterPanel.isVisible = !binding.filterPanel.isVisible
        }

        binding.btnCloseFilters.setOnClickListener {
            binding.filterPanel.visibility = View.GONE
        }

        binding.btnCloseChatRow.setOnClickListener { closeHostChat() }

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
            Toast.makeText(this, getString(R.string.live_app_missing, appName), Toast.LENGTH_SHORT).show()
        }
    }

    /** Real connection info: transport + link speed, from ConnectivityManager. */
    private fun connectionLabel(): String {
        return try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val transport = when {
                caps == null -> "Offline"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                else -> "Online"
            }
            val down = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 }
                ?.let { " ${it / 1000}M" } ?: ""
            "$transport$down"
        } catch (_: Throwable) {
            "Auto"
        }
    }

    private fun showConnectionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.live_connection_speed)
            .setMessage("${connectionLabel()} • Streaming quality adapts automatically.")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private var sampleGiftInFlight: Boolean = false

    private fun sendSampleGift() {
        if (sampleGiftInFlight) return
        sampleGiftInFlight = true
        val key = "gift_${UUID.randomUUID()}"
        lifecycleScope.launch {
            try {
                val giftReq = SendGiftRequest(
                    room_id = 1L,
                    recipient_id = 9999L,
                    gift_id = "gift_sparkler",
                    count = 1,
                    combo_seq = 1,
                    idempotency_key = key
                )
                val result = UnrealStarApiClient.sendGift(giftReq)
                result.onSuccess {
                    Toast.makeText(this@CameraLiveActivity, getString(R.string.live_gift_sent_host, it.remaining_coins), Toast.LENGTH_SHORT).show()
                    addChatMessage(SessionStore.userId, "System", "🎁 You sent Diwali Sparkler!")
                    // Luxury full-screen effect when the art bundle ships
                    // (assets/sparkler.pag); no-op until then.
                    try {
                        PagGiftPlayer.playOnce(this@CameraLiveActivity, binding.pagGiftEffect, "sparkler.pag")
                    } catch (_: Exception) {}
                }.onFailure {
                    Toast.makeText(this@CameraLiveActivity, getString(R.string.live_gift_note, it.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                sampleGiftInFlight = false
            }
        }
    }

    private fun triggerPkMatch() {
        lifecycleScope.launch {
            Toast.makeText(this@CameraLiveActivity, getString(R.string.live_pk_starting), Toast.LENGTH_SHORT).show()
            val res = UnrealStarApiClient.requestPkMatch(liveRoomId)
            res.onSuccess { ticket ->
                addChatMessage(0L, "System", "⚔️ PK Battle search active (Ticket: $ticket)")
                signalClient?.sendChat("⚔️ PK Battle Challenge is LIVE!")
            }.onFailure {
                Toast.makeText(this@CameraLiveActivity, getString(R.string.live_pk_error, it.message), Toast.LENGTH_SHORT).show()
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
                holder.binding.tvVipTag.text = getString(R.string.chat_vip_tag, msg.vipLevel)
                holder.binding.tvVipTag.visibility = View.VISIBLE
            } else {
                holder.binding.tvVipTag.visibility = View.GONE
            }
            holder.binding.tvSender.text = getString(R.string.chat_sender_format, msg.nickname)
            holder.binding.tvMessage.text = msg.message
        }

        override fun getItemCount() = list.size
    }

    inner class MatteItemAdapter(
        private var list: List<MatteItem>,
        private val onSelect: (MatteItem) -> Unit
    ) : RecyclerView.Adapter<MatteItemAdapter.MatteViewHolder>() {

        inner class MatteViewHolder(val binding: ItemBeautyFilterBinding) : RecyclerView.ViewHolder(binding.root)

        fun updateItems(newList: List<MatteItem>) {
            val oldSize = list.size
            list = newList
            notifyListReplaced(oldSize, list.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatteViewHolder {
            val b = ItemBeautyFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MatteViewHolder(b)
        }

        override fun onBindViewHolder(holder: MatteViewHolder, position: Int) {
            val item = list[position]
            holder.binding.tvMatteName.text = item.name
            // Real swatch color from the catalog (replaces the generic icon).
            try {
                holder.binding.ivMatteThumb.setImageDrawable(null)
                holder.binding.ivMatteThumb.setBackgroundColor(item.color)
            } catch (_: Throwable) {}
            holder.itemView.setOnClickListener {
                onSelect(item)
            }
        }

        override fun getItemCount() = list.size
    }

    /** Generic horizontal row for color filters + stickers (reuses the matte thumb layout). */
    inner class FilterItemAdapter(
        private val labels: List<String>,
        private val labelMaxLines: Int = 1,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<FilterItemAdapter.FilterViewHolder>() {

        var tintProvider: ((Int) -> Int)? = null

        /** Baked previews by position; empty until the one-shot frame bakes. */
        private val thumbs = HashMap<Int, android.graphics.Bitmap>()
        private var selected = 0

        inner class FilterViewHolder(val binding: ItemBeautyFilterBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
            val b = ItemBeautyFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return FilterViewHolder(b)
        }

        /** Main thread: swap in a baked preview for one cell. */
        fun setThumb(position: Int, bmp: android.graphics.Bitmap) {
            thumbs[position] = bmp
            notifyItemChanged(position)
        }

        /** Programmatic selection — rebuilt carousel keeps the active filter. */
        fun select(position: Int) {
            if (selected == position) return
            val prev = selected
            selected = position
            if (prev in labels.indices) notifyItemChanged(prev)
            if (position in labels.indices) notifyItemChanged(position)
        }

        override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
            holder.binding.tvMatteName.text = labels[position]
            holder.binding.tvMatteName.maxLines = labelMaxLines
            // Clear first: recycled holders would otherwise keep the previous
            // cell's drawable and tint (visible on Normal/None).
            holder.binding.ivMatteThumb.setImageDrawable(null)
            holder.binding.ivMatteThumb.setBackgroundResource(R.drawable.gradient_scrim_bottom)
            thumbs[position]?.let { holder.binding.ivMatteThumb.setImageBitmap(it) }
            tintProvider?.invoke(position)?.let { tint ->
                if (tint != android.graphics.Color.TRANSPARENT) {
                    holder.binding.ivMatteThumb.setBackgroundColor(tint)
                }
            }
            holder.binding.ivMatteThumb.alpha = if (position == selected) 1f else 0.55f
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    select(pos)
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
