package com.unrealstar.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream

/**
 * Throttled ML Kit face tracker feeding AR sticker placement.
 *
 * Pipeline ownership: the activity owns the single CameraX analyzer and calls
 * [offer] synchronously while the [ImageProxy] is open. The frame is copied
 * to a small Bitmap (throttled to 1 per [THROTTLE_MS]) and detection runs
 * async — the proxy is always closed by the caller, never here.
 *
 * Mapping is approximate: sensor fractions are rotated into screen space and
 * the front camera mirrors X. Preview centerCrop means edges can be off by a
 * few percent; the sticker falls back to center-anchored when untracked.
 */
class FaceTracker(
    var mirrorFront: Boolean = true
) {
    interface Listener {
        /** Fractions 0..1 in screen space + face width fraction. Eyes null when untracked. */
        fun onFace(
            centerX: Float, centerY: Float, widthFrac: Float,
            eyeL: Pair<Float, Float>?, eyeR: Pair<Float, Float>?
        )
        fun onNoFace()
    }

    var listener: Listener? = null
    var enabled: Boolean = true

    private val main = Handler(Looper.getMainLooper())
    private var lastRun = 0L
    private var detector: com.google.mlkit.vision.face.FaceDetector? = null
    private var unsupported = false

    init {
        try {
            val opts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                // Landmarks cost a little but drive the big-eyes warp; contour
                // mode would cost a lot more and is deliberately off.
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build()
            detector = FaceDetection.getClient(opts)
        } catch (t: Throwable) {
            // No GMS / broken vendor build: stickers stay center-anchored.
            Log.w(TAG, "face detector unavailable, tracking off", t)
            unsupported = true
        }
    }

    fun offer(proxy: ImageProxy) {
        if (!enabled || unsupported) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRun < THROTTLE_MS) return
        lastRun = now
        val det = detector ?: return
        val bmp = nv21ToSmallBitmap(proxy) ?: return
        val rotation = proxy.imageInfo.rotationDegrees
        try {
            val image = InputImage.fromBitmap(bmp, rotation)
            det.process(image)
                .addOnSuccessListener { faces ->
                    val f = faces.firstOrNull()
                    if (f == null) {
                        main.post { listener?.onNoFace() }
                        return@addOnSuccessListener
                    }
                    val box = f.boundingBox
                    if (box.width() <= 0 || box.height() <= 0) {
                        main.post { listener?.onNoFace() }
                        return@addOnSuccessListener
                    }
                    // Fractions in the (possibly rotated) bitmap space.
                    var cx = (box.centerX().toFloat() / bmp.width).coerceIn(0f, 1f)
                    var cy = (box.centerY().toFloat() / bmp.height).coerceIn(0f, 1f)
                    val w = (box.width().toFloat() / bmp.width).coerceIn(0.05f, 1f)
                    // InputImage rotation is applied by ML Kit, but the box
                    // stays in bitmap pixels — rotate the point back for screen.
                    val (sx, sy) = rotatePoint(cx, cy, rotation)
                    if (mirrorFront) cx = 1f - sx else cx = sx
                    cy = sy
                    // Eye landmarks go through the identical mapping so the
                    // warp shader and the sticker agree on geometry.
                    val eyeL = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)
                        ?.position?.let { mapPoint(it.x / bmp.width, it.y / bmp.height, rotation) }
                    val eyeR = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)
                        ?.position?.let { mapPoint(it.x / bmp.width, it.y / bmp.height, rotation) }
                    main.post { listener?.onFace(cx, cy, w, eyeL, eyeR) }
                }
                .addOnFailureListener {
                    main.post { listener?.onNoFace() }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "detect failed", t)
        }
    }

    private fun rotatePoint(x: Float, y: Float, deg: Int): Pair<Float, Float> {
        return when ((deg % 360 + 360) % 360) {
            90 -> Pair(1f - y, x)
            180 -> Pair(1f - x, 1f - y)
            270 -> Pair(y, 1f - x)
            else -> Pair(x, y)
        }
    }

    /** Bitmap fraction -> screen fraction (rotate, then front-camera mirror). */
    private fun mapPoint(fx: Float, fy: Float, rotation: Int): Pair<Float, Float> {
        val (rx, ry) = rotatePoint(
            fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f), rotation
        )
        val mx = if (mirrorFront) 1f - rx else rx
        return Pair(mx, ry)
    }

    private fun nv21ToSmallBitmap(proxy: ImageProxy): Bitmap? {
        return try {
            val w = proxy.width
            val h = proxy.height
            if (w <= 0 || h <= 0) return null
            val nv21 = CameraFrame.toNv21(proxy) ?: return null
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, w, h), 60, out)
            val bytes = out.toByteArray()
            val opts = BitmapFactory.Options().apply {
                inSampleSize = if (w > 640) 2 else 1
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (t: Throwable) {
            Log.w(TAG, "frame copy failed", t)
            null
        }
    }

    fun close() {
        try { detector?.close() } catch (_: Throwable) {}
        detector = null
    }

    companion object {
        private const val TAG = "FaceTracker"
        private const val THROTTLE_MS = 400L
    }
}
