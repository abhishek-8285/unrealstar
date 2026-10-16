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

    // ---- Live beauty (driven by the Beauty sliders on the GPU path) ----
    @Volatile var smooth: Float = 0f
    @Volatile var whiten: Float = 0f
    @Volatile var slim: Float = 0f
    @Volatile var bigEyes: Float = 0f
    // Face geometry in view fractions (from FaceTracker); valid flags gate warp.
    @Volatile var faceOn: Boolean = false
    @Volatile var faceCx: Float = 0.5f
    @Volatile var faceCy: Float = 0.5f
    @Volatile var faceW: Float = 0.3f
    @Volatile var eyesOn: Boolean = false
    @Volatile var eyeLx: Float = 0.4f
    @Volatile var eyeLy: Float = 0.4f
    @Volatile var eyeRx: Float = 0.6f
    @Volatile var eyeRy: Float = 0.4f

    fun setBeauty(s: Float, w: Float) {
        smooth = s.coerceIn(0f, 1f)
        whiten = w.coerceIn(0f, 1f)
    }

    fun setReshape(slimFace: Float, eyes: Float) {
        slim = slimFace.coerceIn(0f, 1f)
        bigEyes = eyes.coerceIn(0f, 1f)
    }

    fun setFace(
        cx: Float, cy: Float, w: Float,
        eyeL: Pair<Float, Float>?, eyeR: Pair<Float, Float>?
    ) {
        faceOn = true
        faceCx = cx
        faceCy = cy
        faceW = w
        if (eyeL != null && eyeR != null) {
            eyesOn = true
            eyeLx = eyeL.first
            eyeLy = eyeL.second
            eyeRx = eyeR.first
            eyeRy = eyeR.second
        } else {
            eyesOn = false
        }
    }

    fun clearFace() {
        faceOn = false
        eyesOn = false
    }

    private val renderer = FilterRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setEGLContextClientVersion(2)
        // 32-bit color: the GLSurfaceView default is RGB_565, which banding
        // turns smooth gradients (walls, skin, sky) hazy. Must precede setRenderer.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
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
            cacheLocations()
        }

        // Cached uniform locations (program links once per GL init).
        private var lTex = -1
        private var lR = -1
        private var lG = -1
        private var lB = -1
        private var lA = -1
        private var lBias = -1
        private var lTexMat = -1
        private var lTexel = -1
        private var lSmooth = -1
        private var lWhiten = -1
        private var lFace = -1
        private var lEyeL = -1
        private var lEyeR = -1
        private var lReshape = -1

        private fun cacheLocations() {
            lTex = GLES20.glGetUniformLocation(program, "uTex")
            lR = GLES20.glGetUniformLocation(program, "uR")
            lG = GLES20.glGetUniformLocation(program, "uG")
            lB = GLES20.glGetUniformLocation(program, "uB")
            lA = GLES20.glGetUniformLocation(program, "uA")
            lBias = GLES20.glGetUniformLocation(program, "uBias")
            lTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
            lTexel = GLES20.glGetUniformLocation(program, "uTexel")
            lSmooth = GLES20.glGetUniformLocation(program, "uSmooth")
            lWhiten = GLES20.glGetUniformLocation(program, "uWhiten")
            lFace = GLES20.glGetUniformLocation(program, "uFace")
            lEyeL = GLES20.glGetUniformLocation(program, "uEyeL")
            lEyeR = GLES20.glGetUniformLocation(program, "uEyeR")
            lReshape = GLES20.glGetUniformLocation(program, "uReshape")
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
            GLES20.glUniform1i(lTex, 0)

            val f = filter
            val m = f.colorMatrix
            GLES20.glUniform4f(lR, m[0], m[1], m[2], m[3])
            GLES20.glUniform4f(lG, m[5], m[6], m[7], m[8])
            GLES20.glUniform4f(lB, m[10], m[11], m[12], m[13])
            GLES20.glUniform4f(lA, m[15], m[16], m[17], m[18])
            GLES20.glUniform4f(
                lBias,
                m[4] / 255f, m[9] / 255f, m[14] / 255f, m[19]
            )

            GLES20.glUniformMatrix4fv(lTexMat, 1, false, computeTexMat(), 0)
            GLES20.glUniform2f(
                lTexel,
                1f / streamW.coerceAtLeast(1), 1f / streamH.coerceAtLeast(1)
            )
            GLES20.glUniform1f(lSmooth, smooth)
            GLES20.glUniform1f(lWhiten, whiten)
            GLES20.glUniform4f(
                lFace, faceCx, faceCy, faceW, if (faceOn) 1f else 0f
            )
            GLES20.glUniform4f(
                lEyeL, eyeLx, eyeLy, if (eyesOn) 1f else 0f, 0f
            )
            GLES20.glUniform4f(
                lEyeR, eyeRx, eyeRy, if (eyesOn) 1f else 0f, 0f
            )
            GLES20.glUniform2f(lReshape, slim, bigEyes)

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
                varying vec2 vView;
                void main() { gl_Position = aPos; vView = aUV; }
            """
        private const val FRAG = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vView;
                uniform samplerExternalOES uTex;
                uniform mat4 uTexMat;
                uniform vec4 uR;
                uniform vec4 uG;
                uniform vec4 uB;
                uniform vec4 uA;
                uniform vec4 uBias;
                uniform vec2 uTexel;
                uniform float uSmooth;
                uniform float uWhiten;
                uniform vec4 uFace;
                uniform vec4 uEyeL;
                uniform vec4 uEyeR;
                uniform vec2 uReshape;

                vec2 warpView(vec2 v) {
                    vec2 w = v;
                    if (uFace.w > 0.5 && (uReshape.x > 0.003 || uReshape.y > 0.003)) {
                        float fw = max(uFace.z, 0.05);
                        float dx = w.x - uFace.x;
                        float dy = (w.y - uFace.y) / 1.4;
                        float m = exp(-(dx * dx + dy * dy) / (fw * fw * 0.35)) * uReshape.x;
                        w.x = mix(w.x, uFace.x, clamp(m * 0.35, 0.0, 0.5));
                        float er = fw * 0.18;
                        if (uEyeL.z > 0.5) {
                            float kl = 1.0 - smoothstep(0.0, er, distance(w, uEyeL.xy));
                            w = mix(w, uEyeL.xy + (w - uEyeL.xy) * 0.80, kl * uReshape.y);
                        }
                        if (uEyeR.z > 0.5) {
                            float kr = 1.0 - smoothstep(0.0, er, distance(w, uEyeR.xy));
                            w = mix(w, uEyeR.xy + (w - uEyeR.xy) * 0.80, kr * uReshape.y);
                        }
                    }
                    return w;
                }

                vec3 tapW(vec3 acc, float wsum, vec3 c0, vec2 tuv, vec2 off, float sigma, inout float ws) {
                    vec3 n = texture2D(uTex, tuv + off).rgb;
                    vec3 d = n - c0;
                    float w = exp(-dot(d, d) / (sigma * sigma));
                    ws = wsum + w;
                    return acc + n * w;
                }

                void main() {
                    vec2 wuv = warpView(vView);
                    vec2 tuv = (uTexMat * vec4(wuv, 0.0, 1.0)).xy;
                    vec4 c = texture2D(uTex, tuv);
                    vec3 rgb = c.rgb;
                    if (uSmooth > 0.003) {
                        float sigma = mix(0.02, 0.22, uSmooth);
                        vec3 acc = c.rgb;
                        float ws = 1.0;
                        vec2 px = uTexel;
                        acc = tapW(acc, ws, c.rgb, tuv, vec2(px.x, 0.0), sigma, ws);
                        acc = tapW(acc, ws, c.rgb, tuv, vec2(-px.x, 0.0), sigma, ws);
                        acc = tapW(acc, ws, c.rgb, tuv, vec2(0.0, px.y), sigma, ws);
                        acc = tapW(acc, ws, c.rgb, tuv, vec2(0.0, -px.y), sigma, ws);
                        acc = tapW(acc, ws, c.rgb, tuv, vec2(px.x, px.y), sigma, ws);
                        acc = tapW(acc, ws, c.rgb, tuv, vec2(-px.x, px.y), sigma, ws);
                        acc = tapW(acc, ws, c.rgb, tuv, vec2(px.x, -px.y), sigma, ws);
                        acc = tapW(acc, ws, c.rgb, tuv, vec2(-px.x, -px.y), sigma, ws);
                        rgb = mix(c.rgb, acc / ws, uSmooth);
                    }
                    vec4 g = vec4(
                        dot(vec4(rgb, c.a), uR) + uBias.x,
                        dot(vec4(rgb, c.a), uG) + uBias.y,
                        dot(vec4(rgb, c.a), uB) + uBias.z,
                        dot(c, uA) + uBias.w);
                    g.rgb += uWhiten * vec3(0.07, 0.06, 0.05);
                    gl_FragColor = g;
                }
            """

        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }
    }
}
