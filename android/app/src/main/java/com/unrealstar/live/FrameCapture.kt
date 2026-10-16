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
 * The activity arms it ([armed] = true); the next analyzer frame is decoded
 * to an upright Bitmap on the analyzer thread and handed back — the caller
 * owns closing the proxy as usual. Rotation comes from the frame metadata;
 * front-camera frames are mirrored so the poster matches what the user saw.
 */
object FrameCapture {
    private const val TAG = "FrameCapture"

    @Volatile var armed: Boolean = false

    fun takeFullFrame(proxy: ImageProxy, mirror: Boolean): Bitmap? {
        if (!armed) return null
        armed = false
        return try {
            val w = proxy.width
            val h = proxy.height
            if (w <= 0 || h <= 0) return null
            val nv21 = CameraFrame.toNv21(proxy) ?: return null
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, w, h), 92, out)
            val bytes = out.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val rot = proxy.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }
            if (mirror) {
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
}
