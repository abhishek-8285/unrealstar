package com.camstar.live

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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.camstar.live.api.CamStarApiClient
import com.camstar.live.api.SessionStore
import com.camstar.live.databinding.ActivityCameraLiveBinding
import com.camstar.live.databinding.ItemBeautyFilterBinding
import com.camstar.live.databinding.ItemChatMessageBinding
import com.camstar.live.models.BeautyItem
import com.camstar.live.models.ChatMessage
import com.camstar.live.models.SendGiftRequest
import com.google.android.material.chip.Chip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class CameraLiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraLiveBinding
    private val filterManager = BeautyFilterManager()
    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var matteAdapter: MatteItemAdapter

    private var screenState: LiveScreenState = LiveScreenState.SETUP
    private var countdownTimer: CountDownTimer? = null
    private var liveDurationJob: kotlinx.coroutines.Job? = null
    private var liveDurationSeconds = 0

    private enum class LiveScreenState { SETUP, COUNTDOWN, LIVE, ENDED }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
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
        setupListeners()
        setScreenState(LiveScreenState.SETUP)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
        liveDurationJob?.cancel()
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
            LiveScreenState.LIVE -> startLiveDuration()
            LiveScreenState.ENDED -> {
                liveDurationJob?.cancel()
                countdownTimer?.cancel()
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

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
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

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            startCamera()
        }

        binding.btnStickerBeauty.setOnClickListener {
            Toast.makeText(this, "Stickers & Beauty — coming soon", Toast.LENGTH_SHORT).show()
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
            val result = CamStarApiClient.sendGift(giftReq)
            result.onSuccess {
                Toast.makeText(this@CameraLiveActivity, "✨ Diwali Sparkler sent! (Remaining coins: ${it.remaining_coins})", Toast.LENGTH_SHORT).show()
                addChatMessage(SessionStore.userId, "System", "🎁 You sent Diwali Sparkler!")
            }.onFailure {
                Toast.makeText(this@CameraLiveActivity, "Gifting note: ${it.message}", Toast.LENGTH_SHORT).show()
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
}
