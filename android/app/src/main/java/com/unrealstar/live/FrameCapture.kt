package com.unrealstar.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * One-shot full-resolution frame grab for poster capture.
 *
 * Two-phase by design: [grabCopy] runs on the analyzer thread and does
 * nothing but a plane memcpy (~1ms), so the WebRTC pump and face tracker
 * never stall. [decode] does the expensive JPEG + rotate + mirror work and
 * must run on a background dispatcher. Rotation comes from frame metadata;
 * front-camera frames are mirrored so the poster matches what the user saw.
 */
object FrameCapture {
    private const val TAG = "FrameCapture"

    @Volatile var armed: Boolean = false

    data class FrameCopy(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val mirror: Boolean
    )

    /** Analyzer thread: copy NV21 bytes only. Null unless armed. */
    fun grabCopy(proxy: ImageProxy, mirror: Boolean): FrameCopy? {
        if (!armed) return null
        armed = false
        return try {
            val w = proxy.width
            val h = proxy.height
            if (w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0) return null
            val nv21 = CameraFrame.toNv21(proxy) ?: return null
            FrameCopy(nv21, w, h, proxy.imageInfo.rotationDegrees, mirror)
        } catch (t: Throwable) {
            Log.w(TAG, "frame grab failed", t)
            null
        }
    }

    /** Background thread: JPEG decode + upright. Caller owns closing nothing. */
    fun decode(copy: FrameCopy): Bitmap? {
        return try {
            val yuv = YuvImage(copy.nv21, ImageFormat.NV21, copy.width, copy.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, copy.width, copy.height), 92, out)
            val bytes = out.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (copy.rotation != 0) {
                val m = Matrix().apply { postRotate(copy.rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }
            if (copy.mirror) {
                val m = Matrix().apply { postScale(-1f, 1f, bmp.width / 2f, bmp.height / 2f) }
                val mirrored = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (mirrored !== bmp) bmp.recycle()
                bmp = mirrored
            }
            bmp
        } catch (t: Throwable) {
            Log.w(TAG, "poster frame decode failed", t)
            null
        }
    }

    /** Legacy one-shot path (grab + decode inline). Prefer grab/decode split. */
    fun takeFullFrame(proxy: ImageProxy, mirror: Boolean): Bitmap? {
        val copy = grabCopy(proxy, mirror) ?: return null
        return decode(copy)
    }
}
