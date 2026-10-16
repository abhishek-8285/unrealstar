package com.unrealstar.live

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.TextView

/**
 * Picks the render path per device and applies Instagram-style filters.
 *
 * - LIGHTWEIGHT: fullscreen tint overlay View over PreviewView. No per-frame
 *   allocation, safe on low-RAM / old-GPU phones. This is the live path.
 * - GPU: live GLES preview ([GpuPreviewView]) with per-pixel shaders, plus
 *   offscreen still-capture baking ([processBitmap]). Selected on capable
 *   devices only.
 *
 * PreviewView is a SurfaceView — a ColorMatrix cannot be applied to it
 * directly, hence the overlay approach for live preview on lite devices.
 * Captured stills go through the full bake so posters match the live look.
 */
data class BeautyParams(val smooth: Float = 0f, val whiten: Float = 0f)

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

    /** Still-capture path: full bake (grade + beauty) for poster snapshots. */
    fun processBitmap(
        src: Bitmap,
        filter: CameraFilter = activeFilter,
        beauty: BeautyParams = BeautyParams()
    ): Bitmap {
        val s = beauty.smooth.coerceIn(0f, 1f)
        val w = beauty.whiten.coerceIn(0f, 1f)
        if (filter.id == "normal" && s <= 0.003f && w <= 0.003f) return src
        return if (mode == Mode.GPU) {
            // Off main thread by contract (EGL blocks ~30-60ms at 1080p).
            GpuFilterProcessor.apply(src, filter, s, w)
        } else {
            cpuBake(src, filter, s, w)
        }
    }

    /**
     * Lite-device bake delegates to the shared CPU path (same smooth →
     * grade → whiten order as the GPU shader).
     */
    private fun cpuBake(src: Bitmap, filter: CameraFilter, smooth: Float, whiten: Float): Bitmap =
        GpuFilterProcessor.cpuBake(src, filter, smooth, whiten)


    /**
     * Beauty soft-focus for the lite live path: a low-alpha warm overlay over
     * the preview simulates whiten + smoothing glow. (GPU path uses real
     * in-shader bilateral smoothing instead; see GpuPreviewView.)
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
