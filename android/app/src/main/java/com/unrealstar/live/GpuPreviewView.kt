package com.unrealstar.live

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live GPU camera preview: CameraX pumps frames into our SurfaceTexture and
 * each frame is drawn through the active [CameraFilter] fragment shader.
 *
 * Orientation is safe without device-specific sign guessing: the
 * SurfaceTexture transform matrix already encodes the HAL buffer rotation
 * (CameraX sets the output transform from the target rotation), so the
 * renderer only composes ST * Mirror * CenterCrop. Rotation degrees from
 * CameraX TransformationInfo are used solely to decide whether stream width /
 * height must be swapped for the crop math — no UV sign involved.
 *
 * RenderMode is WHEN_DIRTY (one render per camera frame) for battery.
 * Use [useSurface] for exactly one camera session; it hands out a fresh
 * Surface per call so camera switches never reuse an invalidated one.
 */
class GpuPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    @Volatile var filter: CameraFilter = INSTAGRAM_FILTERS[0]
    @Volatile var mirrorFront: Boolean = true

    private val renderer = FilterRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Fresh Surface for one camera session; callback fires on the main thread. */
    fun useSurface(cb: (Surface) -> Unit) {
        renderer.requestSurface { surf ->
            mainHandler.post { cb(surf) }
        }
    }

    fun setStreamSize(w: Int, h: Int) {
        renderer.streamW = w
        renderer.streamH = h
    }

    /** Display rotation from CameraX TransformationInfo; aspect math only. */
    fun setDisplayRotation(deg: Int) {
        renderer.displayRotation = deg
    }

    fun release() {
        try { renderer.releaseSurfaces() } catch (_: Throwable) {}
    }

    private inner class FilterRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {

        @Volatile var streamW: Int = 1280
        @Volatile var streamH: Int = 720
        @Volatile var displayRotation: Int = 0

        private var program: Int = 0
        private var oesTexId: Int = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var surfaceGen: Int = 0
        private var pendingCb: ((Surface) -> Unit)? = null
        private var pendingGen: Int = 0
        private var viewW: Int = 0
        private var viewH: Int = 0
        private val stMatrix = FloatArray(16)

        private val quadPos = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val quadUV = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        fun requestSurface(cb: (Surface) -> Unit) {
            // Everything on the GL thread: no races, one fresh surface per session.
            queueEvent {
                ensureSurfaceLocked()
                val s: Surface?
                val gen: Int
                synchronized(this) {
                    surfaceGen++
                    gen = surfaceGen
                    pendingGen = gen
                    pendingCb = cb
                    s = surface
                }
                if (s != null) {
                    mainHandler.post { deliverIfCurrent(gen, s) }
                }
            }
        }

        private fun deliverIfCurrent(gen: Int, s: Surface) {
            val cb = synchronized(this) {
                if (gen != surfaceGen) null
                else {
                    val c = pendingCb
                    pendingCb = null
                    c
                }
            }
            if (cb != null) runCatching { cb(s) }
        }

        fun releaseSurfaces() {
            queueEvent {
                try { surface?.release() } catch (_: Throwable) {}
                surface = null
                try { surfaceTexture?.release() } catch (_: Throwable) {}
                surfaceTexture = null
                if (oesTexId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0)
                    oesTexId = 0
                }
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = linkProgram(VERT, FRAG)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width
            viewH = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return
            try {
                st.updateTexImage()
            } catch (t: Throwable) {
                Log.w(TAG, "updateTexImage", t)
                return
            }
            st.getTransformMatrix(stMatrix)

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aUV = GLES20.glGetAttribLocation(program, "aUV")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
            GLES20.glEnableVertexAttribArray(aUV)
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, quadUV)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)

            val f = filter
            val m = f.colorMatrix
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uR"), m[0], m[1], m[2], m[3])
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uG"), m[5], m[6], m[7], m[8])
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uB"), m[10], m[11], m[12], m[13])
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uA"), m[15], m[16], m[17], m[18])
            GLES20.glUniform4f(
                GLES20.glGetUniformLocation(program, "uBias"),
                m[4] / 255f, m[9] / 255f, m[14] / 255f, m[19]
            )

            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(program, "uTexMat"), 1, false, computeTexMat(), 0
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUV)
        }

        /** ST * Mirror * CenterCrop, all sign-safe (no manual UV rotation). */
        private fun computeTexMat(): FloatArray {
            val swapped = ((displayRotation % 360 + 360) % 360 == 90) ||
                ((displayRotation % 360 + 360) % 360 == 270)
            val sw = if (swapped) streamH else streamW
            val sh = if (swapped) streamW else streamH
            val vw = viewW.takeIf { it > 0 } ?: sw
            val vh = viewH.takeIf { it > 0 } ?: sh

            // CenterCrop in stream-UV space.
            val s = maxOf(vw.toFloat() / sw, vh.toFloat() / sh)
            val visW = vw / s / sw
            val visH = vh / s / sh
            val crop = FloatArray(16)
            Matrix.setIdentityM(crop, 0)
            Matrix.translateM(crop, 0, (1f - visW) / 2f, (1f - visH) / 2f, 0f)
            Matrix.scaleM(crop, 0, visW, visH, 1f)

            val mirror = FloatArray(16)
            Matrix.setIdentityM(mirror, 0)
            if (mirrorFront) {
                Matrix.translateM(mirror, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(mirror, 0, -1f, 1f, 1f)
                Matrix.translateM(mirror, 0, -0.5f, -0.5f, 0f)
            }

            val tmp = FloatArray(16)
            Matrix.multiplyMM(tmp, 0, mirror, 0, crop, 0)
            val out = FloatArray(16)
            Matrix.multiplyMM(out, 0, stMatrix, 0, tmp, 0)
            return out
        }

        private fun ensureSurfaceLocked() {
            if (surfaceTexture != null) return
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            oesTexId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            val st = SurfaceTexture(oesTexId)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st
            surface = Surface(st)
        }

        override fun onFrameAvailable(st: SurfaceTexture) {
            requestRender()
        }

        private fun linkProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "link: ${GLES20.glGetProgramInfoLog(p)}" }
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

    }

    companion object {
        private const val TAG = "GpuPreview"
        private const val VERT = """
                attribute vec4 aPos;
                attribute vec2 aUV;
                uniform mat4 uTexMat;
                varying vec2 vUV;
                void main() { gl_Position = aPos; vUV = (uTexMat * vec4(aUV, 0.0, 1.0)).xy; }
            """
        private const val FRAG = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vUV;
                uniform samplerExternalOES uTex;
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

        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }
    }
}
