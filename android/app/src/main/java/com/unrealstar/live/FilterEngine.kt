package com.unrealstar.live

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.widget.TextView

/**
 * Picks the render path per device and applies Instagram-style filters.
 *
 * - LIGHTWEIGHT: fullscreen tint overlay View over PreviewView. No per-frame
 *   allocation, safe on low-RAM / old-GPU phones. This is the live path.
 * - GPU: same overlay PLUS hardware-accelerated still-capture processing
 *   ([processBitmap]) and reserved GLSL hooks for a future GLSurfaceView
 *   pipeline. Selected on capable devices only.
 *
 * PreviewView is a SurfaceView — a ColorMatrix cannot be applied to it
 * directly, hence the overlay approach for live preview. Captured stills
 * go through the real matrix so saved photos match the selected look.
 */
class FilterEngine(private val context: Context) {

    enum class Mode { LIGHTWEIGHT, GPU }

    val mode: Mode by lazy { decideMode() }

    var activeFilter: CameraFilter = INSTAGRAM_FILTERS[0]
        private set

    private fun decideMode(): Mode {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null && am.isLowRamDevice) return Mode.LIGHTWEIGHT
        val memMb = am?.memoryClass ?: 128
        val glEs = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0x20000
        val cores = Runtime.getRuntime().availableProcessors()
        // GPU needs GLES3 + decent RAM + multi-core; otherwise stay lightweight.
        return if (glEs >= 0x30000 && memMb >= 192 && cores >= 6) Mode.GPU else Mode.LIGHTWEIGHT
    }

    /** Live path: tint overlay over the camera preview. */
    fun applyLive(filter: CameraFilter, tintView: View) {
        activeFilter = filter
        if (filter.tintAlpha <= 0f) {
            tintView.visibility = View.GONE
            tintView.background = null
            return
        }
        tintView.visibility = View.VISIBLE
        tintView.setBackgroundColor(filter.tintColor)
        tintView.alpha = filter.tintAlpha
    }

    /** Still-capture path: GPU baker on capable devices, Canvas matrix otherwise. */
    fun processBitmap(src: Bitmap, filter: CameraFilter = activeFilter): Bitmap {
        if (filter.id == "normal") return src
        return if (mode == Mode.GPU) {
            // Off main thread by contract (EGL blocks ~30-60ms at 1080p).
            GpuFilterProcessor.apply(src, filter)
        } else {
            canvasBake(src, filter)
        }
    }

    private fun canvasBake(src: Bitmap, filter: CameraFilter): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(filter.colorMatrix))
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * Beauty soft-focus approximation for the live path: a low-alpha white
     * overlay over the preview simulates whiten + smoothing glow. True
     * bilateral skin smoothing needs GPU frame processing (Phase 2 hook).
     */
    fun applyBeautyLive(smooth: Float, whiten: Float, softView: View) {
        val alpha = (smooth * 0.10f + whiten * 0.16f).coerceIn(0f, 0.26f)
        if (alpha <= 0.005f) {
            softView.visibility = View.GONE
            return
        }
        softView.visibility = View.VISIBLE
        softView.setBackgroundColor(0xFFFFF4EC.toInt())
        softView.alpha = alpha
    }

    /** Sticker overlay: emoji/text sticker pinned above preview, no tracking yet. */
    fun applySticker(sticker: FaceSticker?, stickerView: TextView) {
        if (sticker == null) {
            stickerView.visibility = View.GONE
            return
        }
        stickerView.visibility = View.VISIBLE
        stickerView.text = sticker.emoji
        stickerView.textSize = 64f
    }
}
