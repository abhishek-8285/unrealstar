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

    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var matteAdapter: MatteItemAdapter

    private var screenState: LiveScreenState = LiveScreenState.SETUP
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChatList()
        setupFilterPanel()
        setupFaceTracking()
        setupListeners()
        setScreenState(LiveScreenState.SETUP)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
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
    }

    /** ML Kit tracker -> sticker overlay follows the face; silent if GMS is missing. */
    private fun setupFaceTracking() {
        val tracker = FaceTracker(
            mirrorFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        )
        tracker.listener = object : FaceTracker.Listener {
            override fun onFace(centerX: Float, centerY: Float, widthFrac: Float) {
                trackedFace = Triple(centerX, centerY, widthFrac)
                positionSticker(centerX, centerY)
            }
            override fun onNoFace() {
                trackedFace = null
            }
        }
        faceTracker = tracker
    }

    private fun positionSticker(cx: Float, cy: Float) {
        val sticker = binding.tvStickerOverlay
        if (sticker.visibility != View.VISIBLE) return
        binding.previewView.post {
            val pw = binding.previewView.width.takeIf { it > 0 } ?: return@post
            val ph = binding.previewView.height.takeIf { it > 0 } ?: return@post
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
                startPublishing()
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
            val room = UnrealStarApiClient.createRoom("Unreal Star Live").getOrNull()
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

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
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    analysis,
                )
                // Single activity-owned analyzer: fan frames out to the WebRTC
                // pump (when live) + the throttled face tracker. The publisher
                // must NOT overwrite this via attachCameraFrames.
                val exec = (faceExecutor?.takeIf { !it.isShutdown })
                    ?: java.util.concurrent.Executors.newSingleThreadExecutor().also { faceExecutor = it }
                faceTracker?.mirrorFront = lensFacing == CameraSelector.LENS_FACING_FRONT
                analysis.setAnalyzer(exec) { proxy ->
                    try {
                        try { rtcPublisher?.onCameraFrame(proxy) } catch (_: Throwable) {}
                        try { faceTracker?.offer(proxy) } catch (_: Throwable) {}
                    } finally {
                        try { proxy.close() } catch (_: Throwable) {}
                    }
                }
                updateOverlay()
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
            filterEngine.applyLive(INSTAGRAM_FILTERS[pos], binding.vFilterTint)
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

        // Beauty sliders: smooth + whiten drive the live soft-glow overlay;
        // slim + big-eyes are stored for the GPU mesh-warp Phase 2 hook.
        fun beautyChanged() {
            filterEngine.applyBeautyLive(filterManager.smooth, filterManager.whiten, binding.vBeautySoft)
        }
        binding.sbSmooth.setOnSeekBarChangeListener(simpleSeek { filterManager.smooth = it / 100f; beautyChanged() })
        binding.sbWhiten.setOnSeekBarChangeListener(simpleSeek { filterManager.whiten = it / 100f; beautyChanged() })
        binding.sbSlim.setOnSeekBarChangeListener(simpleSeek { filterManager.slimFace = it / 100f })
        binding.sbBigEyes.setOnSeekBarChangeListener(simpleSeek { filterManager.bigEyes = it / 100f })
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
            setScreenState(LiveScreenState.COUNTDOWN)
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
            sendSampleGift()
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
            Toast.makeText(this, "Connection speed: auto", Toast.LENGTH_SHORT).show()
        }
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
}
