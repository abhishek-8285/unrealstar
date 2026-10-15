package com.unrealstar.live

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Offscreen (Pbuffer) OpenGL ES 2.0 filter baker for still captures.
 *
 * Each [CameraFilter.colorMatrix] (4x5 row-major) is split into four row
 * vec4s + a bias vec4 and applied per-pixel in the fragment shader. This is
 * the real GPU path for saved photos; the live preview keeps the zero-cost
 * overlay tint (PreviewView is a SurfaceView and cannot take a shader).
 * Any GL failure falls back to the Canvas ColorMatrix path — captures never
 * fail because the GPU did.
 *
 * One EGL context per call: slower than a retained context (~30-60ms at
 * 1080p) but leak-proof. Call off the main thread.
 */
object GpuFilterProcessor {

    private const val TAG = "GpuFilter"

    private const val VERT = """
        attribute vec4 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() { gl_Position = aPos; vUV = aUV; }
    """

    private const val FRAG = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        uniform vec4 uR;
        uniform vec4 uG;
        uniform vec4 uB;
        uniform vec4 uA;
        uniform vec4 uBias;
        void main() {
            vec4 c = texture2D(uTex, vUV);
            gl_FragColor = vec4(
                dot(c, uR) + uBias.x,
                dot(c, uG) + uBias.y,
                dot(c, uB) + uBias.z,
                dot(c, uA) + uBias.w);
        }
    """

    fun apply(src: Bitmap, filter: CameraFilter): Bitmap {
        if (filter.id == "normal") return src
        return try {
            renderOffscreen(src, filter)
        } catch (t: Throwable) {
            Log.w(TAG, "GPU bake failed, Canvas fallback", t)
            canvasFallback(src, filter)
        }
    }

    private fun canvasFallback(src: Bitmap, filter: CameraFilter): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(filter.colorMatrix)
        )
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun renderOffscreen(src: Bitmap, filter: CameraFilter): Bitmap {
        val w = src.width
        val h = src.height
        require(w > 0 && h > 0)

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "eglInitialize" }
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "chooseConfig" }
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "createContext" }
            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "pbuffer" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "makeCurrent" }
            try {
                val result = draw(src, filter, w, h)
                check(EGL14.eglSwapBuffers(display, surface))
                return result
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }

    private fun draw(src: Bitmap, filter: CameraFilter, w: Int, h: Int): Bitmap {
        val prog = linkProgram(VERT, FRAG)
        GLES20.glUseProgram(prog)

        // Fullscreen quad (triangle strip).
        val pos: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        // Flip V: GL texture origin is bottom-left, Bitmap top-left.
        val uv: FloatBuffer = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUV = GLES20.glGetAttribLocation(prog, "aUV")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, uv)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)

        // 4x5 row-major -> rows + bias (bias stored 0..255 in matrix defs).
        val m = filter.colorMatrix
        fun row(r: Int) = floatArrayOf(m[r * 5], m[r * 5 + 1], m[r * 5 + 2], m[r * 5 + 3])
        val bias = floatArrayOf(m[4] / 255f, m[9] / 255f, m[14] / 255f, m[19])
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uR"), 1, row(0), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uG"), 1, row(1), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uB"), 1, row(2), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uA"), 1, row(3), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uBias"), 1, bias, 0)

        GLES20.glViewport(0, 0, w, h)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw")

        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        checkGl("readPixels")

        // GL returns bottom-up RGBA; flip rows into ARGB_8888.
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rowBytes = w * 4
        val tmp = ByteArray(rowBytes)
        val arr = ByteArray(w * h * 4)
        pixels.rewind()
        pixels.get(arr)
        val flipped = ByteArray(arr.size)
        for (y in 0 until h) {
            System.arraycopy(arr, y * rowBytes, tmp, 0, rowBytes)
            System.arraycopy(tmp, 0, flipped, (h - 1 - y) * rowBytes, rowBytes)
        }
        // RGBA -> ARGB.
        val px = IntArray(w * h)
        for (i in px.indices) {
            val r = flipped[i * 4].toInt() and 0xFF
            val g = flipped[i * 4 + 1].toInt() and 0xFF
            val b = flipped[i * 4 + 2].toInt() and 0xFF
            val a = flipped[i * 4 + 3].toInt() and 0xFF
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(px, 0, w, 0, 0, w, h)

        GLES20.glDeleteTextures(1, tex, 0)
        GLES20.glDeleteProgram(prog)
        return out
    }

    private fun linkProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "link: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "compile: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private fun checkGl(op: String) {
        val e = GLES20.glGetError()
        check(e == GLES20.GL_NO_ERROR) { "$op: GL error 0x${Integer.toHexString(e)}" }
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(a); position(0) }
}
