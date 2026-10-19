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
 * Face geometry in upright visual-frame fractions (x right as displayed, y
 * down). Front-camera frames are already mirrored by the tracker, so the
 * coordinates match both the live preview and the mirrored poster still.
 *
 * [frameW]/[frameH] are the upright frame dimensions the fractions refer to;
 * the live preview center-crops this frame, so consumers that draw into the
 * view must map through [MatteGeometry.cropToView]. Stills show the full
 * frame and use the fractions directly.
 */
data class FaceGeometry(
    val centerX: Float,
    val centerY: Float,
    val widthFrac: Float,
    val heightFrac: Float,
    val eyeL: Pair<Float, Float>?,
    val eyeR: Pair<Float, Float>?,
    val mouthL: Pair<Float, Float>?,
    val mouthR: Pair<Float, Float>?,
    val frameW: Int,
    val frameH: Int
) {
    val mouthCenter: Pair<Float, Float>?
        get() {
            val l = mouthL ?: return null
            val r = mouthR ?: return null
            return Pair((l.first + r.first) / 2f, (l.second + r.second) / 2f)
        }
}

/**
 * Throttled ML Kit face tracker feeding makeup anchoring, AR sticker
 * placement, and the GPU reshape warp.
 *
 * Pipeline ownership: the activity owns the single CameraX analyzer and calls
 * [offer] synchronously while the [ImageProxy] is open. The frame is copied
 * to a small Bitmap (throttled to 1 per [THROTTLE_MS]) and detection runs
 * async — the proxy is always closed by the caller, never here.
 */
class FaceTracker(
    var mirrorFront: Boolean = true
) {
    interface Listener {
        fun onFace(face: FaceGeometry)
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
                // Landmarks drive the makeup anchor, the big-eyes warp, and
                // the sticker position; contour mode would cost a lot more.
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build()
            detector = FaceDetection.getClient(opts)
        } catch (t: Throwable) {
            // No GMS / broken vendor build: stickers stay center-anchored,
            // makeup stays off (never mis-anchored).
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
        // Upright-first: rotate the small bitmap to visual-upright BEFORE
        // detection and pass rotation=0 to ML Kit. Detection coordinates are
        // then unambiguously in upright-frame fractions — no rotatePoint /
        // mapPoint math, no double-rotation risk, no sign to verify on
        // device. (Whether ML Kit returns pre- or post-rotation coordinates
        // for fromBitmap(lp, rotation != 0) is version-dependent; rotation=0
        // has exactly one interpretation.)
        val raw = nv21ToSmallBitmap(proxy) ?: return
        val rotation = ((proxy.imageInfo.rotationDegrees % 360) + 360) % 360
        val upright = if (rotation == 0) {
            raw
        } else {
            try {
                val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                if (r !== raw) raw.recycle()
                r
            } catch (t: Throwable) {
                Log.w(TAG, "upright rotate failed", t)
                raw.recycle()
                return
            }
        }
        val frameW = upright.width
        val frameH = upright.height
        if (frameW <= 0 || frameH <= 0) {
            upright.recycle()
            return
        }
        try {
            val image = InputImage.fromBitmap(upright, 0)
            det.process(image)
                .addOnSuccessListener { faces ->
                    upright.recycle()
                    val f = faces.firstOrNull()
                    val box = f?.boundingBox
                    if (f == null || box == null || box.width() <= 0 || box.height() <= 0) {
                        main.post { listener?.onNoFace() }
                        return@addOnSuccessListener
                    }
                    // Upright-frame fractions; front camera mirrors for display.
                    val mirror = mirrorFront
                    fun mx(x: Float): Float {
                        val c = (x / frameW).coerceIn(0f, 1f)
                        return if (mirror) 1f - c else c
                    }
                    val cx = mx(box.centerX().toFloat())
                    val cy = (box.centerY().toFloat() / frameH).coerceIn(0f, 1f)
                    val widthFrac = (box.width().toFloat() / frameW).coerceIn(0.05f, 1f)
                    val heightFrac = (box.height().toFloat() / frameH).coerceIn(0.05f, 1.5f)
                    fun pt(p: android.graphics.PointF?): Pair<Float, Float>? {
                        if (p == null) return null
                        return Pair(mx(p.x), (p.y / frameH).coerceIn(0f, 1f))
                    }
                    val eyeL = pt(f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position)
                    val eyeR = pt(f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position)
                    val mouthL = pt(f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position)
                    val mouthR = pt(f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position)
                    // ML Kit labels eyes from the subject's perspective; a
                    // mirrored front frame swaps them on screen. Makeup is
                    // near-symmetric, but the big-eyes warp is not — swap so
                    // eyeL/eyeR are screen-left/screen-right as consumers
                    // assume.
                    val geo = FaceGeometry(
                        centerX = cx, centerY = cy,
                        widthFrac = widthFrac, heightFrac = heightFrac,
                        eyeL = if (mirror) eyeR else eyeL,
                        eyeR = if (mirror) eyeL else eyeR,
                        mouthL = mouthL, mouthR = mouthR,
                        frameW = frameW, frameH = frameH
                    )
                    main.post { listener?.onFace(geo) }
                }
                .addOnFailureListener {
                    upright.recycle()
                    main.post { listener?.onNoFace() }
                }
        } catch (t: Throwable) {
            upright.recycle()
            Log.w(TAG, "detect failed", t)
        }
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
                // ~640x480 upright input: far above the detector's internal
                // input size, while keeping transient bitmap churn small at
                // the 5Hz cadence (full-frame decodes + rotation copies cost
                // ~50MB/s of GC churn on this class of SoC).
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
        // 5Hz: half the previous latency for anchors, still far below the
        // detector's cost budget on a midrange SoC.
        private const val THROTTLE_MS = 200L
    }
}
