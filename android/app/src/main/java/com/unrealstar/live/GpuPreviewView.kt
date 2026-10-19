package com.unrealstar.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live GPU camera preview: CameraX pumps frames into our SurfaceTexture and
 * each frame is drawn through the active [CameraFilter] fragment shader,
 * plus:
 *  - in-shader beauty: bilateral-ish smoothing, whiten, and the tracked
 *    slim-face / big-eyes reshape warp (FaceTracker landmarks);
 *  - makeup masks from the shipped packs ([MatteCatalog]): the mask texture
 *    is transformed from the 750x1334 canvas onto the tracked face via a
 *    weighted similarity fit ([MatteGeometry]), then composited as LUT
 *    recolor (lipstick) or multiply blend (blush/brows/shadow/lash).
 *
 * No face, no makeup — never a guessed placement.
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

    /**
     * Grade strength (0..1), Instagram's per-effect customization param.
     * Scales the curve mix and overlay opacities; UI writes it alongside
     * [filter] on filter select / seek changes.
     */
    @Volatile var gradeStrength: Float = 1f
    @Volatile var mirrorFront: Boolean = true

    // ---- Live beauty (driven by the Beauty sliders on the GPU path) ----
    @Volatile var smooth: Float = 0f
    @Volatile var whiten: Float = 0f
    @Volatile var slim: Float = 0f
    @Volatile var bigEyes: Float = 0f

    // Retouch + look knobs (0..1 sliders; stage math in [BeautyShader]).
    @Volatile var sharpen: Float = 0f
    @Volatile var contrast: Float = 0f
    @Volatile var saturation: Float = 0f
    @Volatile var ruddy: Float = 0f
    @Volatile var eyeTeeth: Float = 0f

    /** Latest tracked face (upright frame fractions); null hides makeup + warp. */
    @Volatile var face: FaceGeometry? = null

    /** Canonical makeup-canvas geometry (from [MatteCatalog]); never null. */
    @Volatile var canonical: CanonicalFace = CanonicalFace.EMPTY

    /** Active makeup layers; rendered in order, capped at [MAX_MATTES]. */
    @Volatile var mattes: List<MatteLayer> = emptyList()

    /**
     * Beauty sliders -> shader uniforms: smooth/whiten plus the retouch/look
     * knobs. All 0..1 and clamped; the knob parameters default to off so
     * callers driving only smooth/whiten keep working unchanged.
     */
    fun setBeauty(
        s: Float,
        w: Float,
        sharpenAmount: Float = 0f,
        contrastAmount: Float = 0f,
        saturationAmount: Float = 0f,
        ruddyAmount: Float = 0f,
        eyeTeethAmount: Float = 0f
    ) {
        smooth = s.coerceIn(0f, 1f)
        whiten = w.coerceIn(0f, 1f)
        sharpen = sharpenAmount.coerceIn(0f, 1f)
        contrast = contrastAmount.coerceIn(0f, 1f)
        saturation = saturationAmount.coerceIn(0f, 1f)
        ruddy = ruddyAmount.coerceIn(0f, 1f)
        eyeTeeth = eyeTeethAmount.coerceIn(0f, 1f)
    }

    fun setReshape(slimFace: Float, eyes: Float) {
        slim = slimFace.coerceIn(0f, 1f)
        bigEyes = eyes.coerceIn(0f, 1f)
    }

    /**
     * Uploaded 2D texture: GL id + pixel size (the size drives the UV math).
     * A class declaration is not allowed inside the inner [FilterRenderer],
     * hence this outer placement.
     */
    private class GlTex(val id: Int, val w: Int, val h: Int)

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

    /**
     * Invoked on the main thread when a recreated GL context invalidated the
     * surface pair that had been handed to a camera session. The owner must
     * re-run its camera bind so frames flow into the new pair; otherwise the
     * camera keeps streaming into the dead queue and the preview renders
     * black (classic case: screen off/on, or the entrance-transition surface
     * recreation racing the first bind).
     */
    var onSurfaceReset: (() -> Unit)? = null

    fun setStreamSize(w: Int, h: Int) {
        renderer.streamW = w
        renderer.streamH = h
        // Sharpness debugging: this is the resolution the camera actually
        // streams; the GL view upscales from here to the screen size.
        android.util.Log.i("GpuPreview", "stream size ${w}x$h")
    }

    /** Display rotation from CameraX TransformationInfo; aspect math only. */
    fun setDisplayRotation(deg: Int) {
        renderer.displayRotation = deg
    }

    fun release() {
        try { renderer.releaseSurfaces() } catch (_: Throwable) {}
    }

    /**
     * The camera activity attaches an OnTouchListener (tap to focus and
     * dismiss panels); accessibility services expect click handling to land
     * in performClick, so the listener forwards its ACTION_UP here.
     */
    override fun performClick(): Boolean = super.performClick()

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
        /** Generation of the surface pair last handed to the activity. */
        private var deliveredGen: Int = 0
        private var viewW: Int = 0
        private var viewH: Int = 0
        private val stMatrix = FloatArray(16)

        private val quadPos = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val quadUV = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        // ---- GL texture cache (mask/LUT bitmaps), GL thread only ----
        private val texCache = HashMap<String, GlTex>()

        /** 1x1 white 2D texture: a valid binding for unused grade samplers. */
        private var nullTexId: Int = 0

        // ---- Cached uniform locations, filled once per program link ----
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
        private var lSharpen = -1
        private var lContrast = -1
        private var lSaturation = -1
        private var lRuddy = -1
        private var lEyeTeeth = -1
        private var lMouth = -1
        private var lFace = -1
        private var lEyeL = -1
        private var lEyeR = -1
        private var lReshape = -1
        private var lLutTex = -1
        private var lCurveTex = -1
        private var lCurveOn = -1
        private var lCurveStrength = -1
        private val lOvlTex = IntArray(GradeShader.MAX_OVERLAYS)
        private val lOvlA = IntArray(GradeShader.MAX_OVERLAYS)
        private val lXfA = IntArray(MAX_MATTES)
        private val lXfB = IntArray(MAX_MATTES)
        private val lTint = IntArray(MAX_MATTES)
        private val lMode = IntArray(MAX_MATTES)
        private val lMatteTex = IntArray(MAX_MATTES)

        fun requestSurface(cb: (Surface) -> Unit) {
            // Everything on the GL thread: one fresh surface pair per session.
            // A pair whose BufferQueue died (screen off, GL context loss)
            // cannot be revived, and after a context recreation its texture id
            // is foreign — reusing it made the camera stream into a dead queue
            // while onDrawFrame early-returned on updateTexImage (black
            // preview after screen off/on).
            queueEvent {
                releaseSurfacePairLocked()
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

        /** Releases the GL-thread-owned consumer pair; call on the GL thread. */
        private fun releaseSurfacePairLocked() {
            try { surface?.release() } catch (_: Throwable) {}
            surface = null
            try { surfaceTexture?.release() } catch (_: Throwable) {}
            surfaceTexture = null
            if (oesTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0)
                oesTexId = 0
            }
        }

        private fun deliverIfCurrent(gen: Int, s: Surface) {
            val cb = synchronized(this) {
                if (gen != surfaceGen) null
                else {
                    deliveredGen = gen
                    val c = pendingCb
                    pendingCb = null
                    c
                }
            }
            if (cb != null) runCatching { cb(s) }
        }

        fun releaseSurfaces() {
            queueEvent {
                releaseSurfacePairLocked()
                for (t in texCache.values) {
                    GLES20.glDeleteTextures(1, intArrayOf(t.id), 0)
                }
                texCache.clear()
                if (nullTexId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(nullTexId), 0)
                    nullTexId = 0
                }
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // The context was recreated (screen off/on, surface teardown):
            // every GL object from the previous context is gone, and any
            // surface pair — already handed to the camera (deliveredGen
            // match) or still in flight on the main thread (pendingCb
            // != null) — has a dead queue. Drop it here without GL deletes
            // (those ids died with the old context), reject any delivery
            // already posted to main, and if a session was wiring itself to
            // that pair, ask the owner to rebind: otherwise the camera
            // streams into the dead queue and the session never produces a
            // single frame (cold-start race with the entrance transition).
            val staleSession = synchronized(this) {
                val stale = pendingCb != null ||
                    (deliveredGen != 0 && deliveredGen == surfaceGen)
                pendingCb = null
                deliveredGen = 0
                surfaceGen++ // invalidate deliveries already posted to main
                stale
            }
            try { surface?.release() } catch (_: Throwable) {}
            try { surfaceTexture?.release() } catch (_: Throwable) {}
            surface = null
            surfaceTexture = null
            oesTexId = 0
            // The full program (grade + beauty + mattes) normally links. If a
            // driver ever refuses it, fall back to a minimal passthrough so
            // the camera stays usable instead of crashing the GL thread —
            // captures still bake the exact look through GpuFilterProcessor,
            // whose failures degrade to the CPU bake.
            program = try {
                linkProgram(VERT, buildFragSource())
            } catch (t: Throwable) {
                Log.e(TAG, "full preview program failed; passthrough fallback", t)
                linkProgram(VERT, PASSTHROUGH_FRAG)
            }
            cacheLocations()
            // A recreated GL context invalidates every previously uploaded texture.
            texCache.clear()
            nullTexId = createWhiteTex()
            if (staleSession) {
                mainHandler.post { onSurfaceReset?.invoke() }
            }
        }

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
            lSharpen = GLES20.glGetUniformLocation(program, "uSharpen")
            lContrast = GLES20.glGetUniformLocation(program, "uContrast")
            lSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
            lRuddy = GLES20.glGetUniformLocation(program, "uRuddy")
            lEyeTeeth = GLES20.glGetUniformLocation(program, "uEyeTeeth")
            lMouth = GLES20.glGetUniformLocation(program, "uMouth")
            lFace = GLES20.glGetUniformLocation(program, "uFace")
            lEyeL = GLES20.glGetUniformLocation(program, "uEyeL")
            lEyeR = GLES20.glGetUniformLocation(program, "uEyeR")
            lReshape = GLES20.glGetUniformLocation(program, "uReshape")
            lLutTex = GLES20.glGetUniformLocation(program, "uLutTex")
            lCurveTex = GLES20.glGetUniformLocation(program, "uCurveTex")
            lCurveOn = GLES20.glGetUniformLocation(program, "uCurveOn")
            lCurveStrength = GLES20.glGetUniformLocation(program, "uCurveStrength")
            for (i in 0 until GradeShader.MAX_OVERLAYS) {
                lOvlTex[i] = GLES20.glGetUniformLocation(program, "uOvlTex$i")
                lOvlA[i] = GLES20.glGetUniformLocation(program, "uOvlA$i")
            }
            for (i in 0 until MAX_MATTES) {
                lXfA[i] = GLES20.glGetUniformLocation(program, "uXfA$i")
                lXfB[i] = GLES20.glGetUniformLocation(program, "uXfB$i")
                lTint[i] = GLES20.glGetUniformLocation(program, "uTint$i")
                lMode[i] = GLES20.glGetUniformLocation(program, "uMode$i")
                lMatteTex[i] = GLES20.glGetUniformLocation(program, "uMatte$i")
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width
            viewH = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture
            if (st == null) {
                // No live consumer pair (released on context loss): paint
                // black instead of leaving the fresh buffer undefined.
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                return
            }
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
            val recipe = f.recipe
            // The REA-derived grade replaces the 4x5 matrix when its curve
            // strip is present; the matrix keeps carrying lite-path looks and
            // matrix-only recipes (cross-channel B&W / sepia).
            var curveBound = false
            if (recipe?.curve != null) {
                // Pack-aware decode: shipped recipes read assets/, installed
                // packs read their dir; the texture key carries the source
                // root so an overriding pack never reuses a cached texture.
                val root = recipe.packDir ?: "assets"
                val curveBmp = GradeAssets.decode(context, recipe, recipe.curve!!)
                val curveTex = curveBmp?.let { textureFor("grade:$root:${recipe.curve}", it) }
                if (curveTex != null) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GradeShader.CURVE_UNIT)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex.id)
                    GLES20.glUniform1i(lCurveTex, GradeShader.CURVE_UNIT)
                    GLES20.glUniform1f(lCurveStrength, gradeStrength.coerceIn(0f, 1f))
                    curveBound = true
                }
            }
            GLES20.glUniform1f(lCurveOn, if (curveBound) 1f else 0f)
            if (curveBound) {
                GLES20.glUniform4f(lR, 1f, 0f, 0f, 0f)
                GLES20.glUniform4f(lG, 0f, 1f, 0f, 0f)
                GLES20.glUniform4f(lB, 0f, 0f, 1f, 0f)
                GLES20.glUniform4f(lA, 0f, 0f, 0f, 1f)
                GLES20.glUniform4f(lBias, 0f, 0f, 0f, 0f)
            } else {
                // The uCurveTex sampler is still program-active: keep the
                // neutral 1x1 white bound so every draw has valid textures.
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GradeShader.CURVE_UNIT)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nullTexId)
                GLES20.glUniform1i(lCurveTex, GradeShader.CURVE_UNIT)
                val m = f.colorMatrix
                GLES20.glUniform4f(lR, m[0], m[1], m[2], m[3])
                GLES20.glUniform4f(lG, m[5], m[6], m[7], m[8])
                GLES20.glUniform4f(lB, m[10], m[11], m[12], m[13])
                GLES20.glUniform4f(lA, m[15], m[16], m[17], m[18])
                GLES20.glUniform4f(
                    lBias,
                    m[4] / 255f, m[9] / 255f, m[14] / 255f, m[19]
                )
            }
            bindGradeOverlays(recipe)

            GLES20.glUniformMatrix4fv(lTexMat, 1, false, computeTexMat(), 0)
            GLES20.glUniform2f(
                lTexel,
                1f / streamW.coerceAtLeast(1), 1f / streamH.coerceAtLeast(1)
            )
            GLES20.glUniform1f(lSmooth, smooth)
            GLES20.glUniform1f(lWhiten, whiten)
            GLES20.glUniform1f(lSharpen, sharpen)
            GLES20.glUniform1f(lContrast, contrast)
            GLES20.glUniform1f(lSaturation, saturation)
            GLES20.glUniform1f(lRuddy, ruddy)
            GLES20.glUniform1f(lEyeTeeth, eyeTeeth)

            // Warp/matte geometry: tracker fractions are upright-frame,
            // top-down. The preview center-crops the frame, and GL view space
            // is bottom-up (clip y = -1 is the view bottom), so the mapping
            // is: center-crop, then flip y once here at the GL boundary.
            val geo = face
            val geoView = if (geo != null && viewW > 0 && viewH > 0) {
                val g = MatteGeometry.faceInView(geo, viewW.toFloat(), viewH.toFloat())
                g.copy(
                    centerY = 1f - g.centerY,
                    eyeL = g.eyeL?.let { Pair(it.first, 1f - it.second) },
                    eyeR = g.eyeR?.let { Pair(it.first, 1f - it.second) },
                    mouthL = g.mouthL?.let { Pair(it.first, 1f - it.second) },
                    mouthR = g.mouthR?.let { Pair(it.first, 1f - it.second) }
                )
            } else {
                null
            }
            GLES20.glUniform4f(
                lFace,
                geoView?.centerX ?: 0.5f, geoView?.centerY ?: 0.5f,
                geoView?.widthFrac ?: 0.3f, if (geoView != null) 1f else 0f
            )
            val eyesOk = geoView?.eyeL != null && geoView?.eyeR != null
            GLES20.glUniform4f(
                lEyeL,
                geoView?.eyeL?.first ?: 0.4f, geoView?.eyeL?.second ?: 0.4f,
                if (eyesOk) 1f else 0f, 0f
            )
            GLES20.glUniform4f(
                lEyeR,
                geoView?.eyeR?.first ?: 0.6f, geoView?.eyeR?.second ?: 0.4f,
                if (eyesOk) 1f else 0f, 0f
            )
            // Mouth anchor for the eyes/teeth retouch: the mouth-corner centre
            // with a radius from the corner distance (same fraction space the
            // eye uniforms use; warped-frame space in the shader).
            val mouthC = geoView?.mouthCenter
            val mdx = (geoView?.mouthL?.first ?: 0f) - (geoView?.mouthR?.first ?: 0f)
            val mdy = (geoView?.mouthL?.second ?: 0f) - (geoView?.mouthR?.second ?: 0f)
            val mouthR =
                if (mouthC != null) (sqrt(mdx * mdx + mdy * mdy) * 0.4f).coerceAtLeast(0.02f) else 0.2f
            GLES20.glUniform4f(
                lMouth,
                mouthC?.first ?: 0.5f, mouthC?.second ?: 0.6f,
                mouthR, if (mouthC != null) 1f else 0f
            )
            GLES20.glUniform2f(lReshape, slim, bigEyes)

            uploadMattes(geoView)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUV)
        }

        /**
         * Grade overlay masks: up to [GradeShader.MAX_OVERLAYS] screen-space
         * passes (blend-mode x + opacity y). Unused slots bind the 1x1 white
         * texture with opacity 0 — every sampler always points at a valid 2D
         * texture, mirroring the matte-LUT convention.
         */
        private fun bindGradeOverlays(recipe: GradeRecipe?) {
            for (i in 0 until GradeShader.MAX_OVERLAYS) {
                val ov = recipe?.overlays?.getOrNull(i)
                var confX = 0f
                var confY = 0f
                val tex = ov?.let {
                    val bmp = GradeAssets.decode(context, recipe, it.texture)
                    bmp?.let { b ->
                        textureFor("grade:${recipe.packDir ?: "assets"}:${it.texture}", b)
                    }
                }
                if (ov != null && tex != null) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GradeShader.OVL_UNIT_BASE + i)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id)
                    GLES20.glUniform1i(lOvlTex[i], GradeShader.OVL_UNIT_BASE + i)
                    confX = ov.blend.mode
                    confY = (ov.opacity * gradeStrength).coerceIn(0f, 1f)
                } else {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GradeShader.OVL_UNIT_BASE + i)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nullTexId)
                    GLES20.glUniform1i(lOvlTex[i], GradeShader.OVL_UNIT_BASE + i)
                }
                GLES20.glUniform4f(lOvlA[i], confX, confY, 0f, 0f)
            }
        }

        /**
         * Per-frame makeup uniforms: fit canvas->GL-view once, then transform
         * each layer's mask into view UVs. Every slot is uploaded every frame
         * — a slot that stops being used must not keep last frame's uniforms.
         * [geo] is already crop-mapped + y-flipped at the call site.
         */
        private fun uploadMattes(geo: FaceGeometry?) {
            val layers = mattes
            val sim = if (geo != null && layers.isNotEmpty() && viewW > 0 && viewH > 0) {
                MatteGeometry.similarityFor(
                    geo, canonical, viewW.toFloat(), viewH.toFloat(), crop = false
                )
            } else {
                null
            }
            for (i in 0 until MAX_MATTES) {
                val layer = if (sim != null) layers.getOrNull(i) else null
                var tintR = 0f; var tintG = 0f; var tintB = 0f; var strength = 0f
                if (layer != null) {
                    val maskTex = textureFor(layer.category.maskAsset, layer.mask)
                    if (maskTex != null) {
                        val cat = layer.category
                        val xf = MatteGeometry.viewFractionToMaskUv(
                            sim!!, cat.anchorX, cat.anchorY, cat.imageScale,
                            maskTex.w, maskTex.h, viewW.toFloat(), viewH.toFloat()
                        )
                        GLES20.glUniform4f(lXfA[i], xf[0], xf[1], xf[2], xf[3])
                        GLES20.glUniform2f(lXfB[i], xf[4], xf[5])
                        GLES20.glUniform1f(lMode[i], if (cat.lutMode) 1f else 0f)
                        val t = layer.item.tint
                        tintR = t[0]; tintG = t[1]; tintB = t[2]
                        strength = layer.intensity.coerceIn(0f, 1f)

                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + MATTE_UNIT_BASE + i)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex.id)
                        GLES20.glUniform1i(lMatteTex[i], MATTE_UNIT_BASE + i)

                        // Shared LUT unit; bind the mask when unused so the
                        // sampler always points at a valid 2D texture.
                        val lutTex = if (cat.lutMode) {
                            val lutAsset = cat.lutAsset
                            val lutBmp = layer.lut
                            if (lutAsset != null && lutBmp != null) textureFor(lutAsset, lutBmp) else null
                        } else {
                            null
                        }
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + LUT_UNIT)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, (lutTex ?: maskTex).id)
                        GLES20.glUniform1i(lLutTex, LUT_UNIT)
                    }
                }
                GLES20.glUniform4f(lTint[i], tintR, tintG, tintB, strength)
                if (strength <= 0f) GLES20.glUniform1f(lMode[i], 0f)
            }
        }

        /** Uploads [bmp] as a cached 2D texture for [key]; GL thread only. */
        private fun textureFor(key: String, bmp: Bitmap): GlTex? {
            texCache[key]?.let { return it }
            return try {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                GlTex(ids[0], bmp.width, bmp.height).also { texCache[key] = it }
            } catch (t: Throwable) {
                Log.w(TAG, "mask upload failed: $key", t)
                null
            }
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
        private const val MAX_MATTES = 5
        private const val MATTE_UNIT_BASE = 1
        private const val LUT_UNIT = 6

        private const val VERT = """
            attribute vec4 aPos;
            attribute vec2 aUV;
            varying vec2 vView;
            void main() { gl_Position = aPos; vView = aUV; }
        """

        private const val SHADER_HELPERS = """
            vec2 warpView(vec2 v, vec4 face, vec4 eyeL, vec4 eyeR, vec2 reshape) {
                vec2 w = v;
                if (face.w > 0.5 && (reshape.x > 0.003 || reshape.y > 0.003)) {
                    float fw = max(face.z, 0.05);
                    float dx = w.x - face.x;
                    float dy = (w.y - face.y) / 1.4;
                    float m = exp(-(dx * dx + dy * dy) / (fw * fw * 0.35)) * reshape.x;
                    w.x = mix(w.x, face.x, clamp(m * 0.35, 0.0, 0.5));
                    float er = fw * 0.18;
                    if (eyeL.z > 0.5) {
                        float kl = 1.0 - smoothstep(0.0, er, distance(w, eyeL.xy));
                        w = mix(w, eyeL.xy + (w - eyeL.xy) * 0.80, kl * reshape.y);
                    }
                    if (eyeR.z > 0.5) {
                        float kr = 1.0 - smoothstep(0.0, er, distance(w, eyeR.xy));
                        w = mix(w, eyeR.xy + (w - eyeR.xy) * 0.80, kr * reshape.y);
                    }
                }
                return w;
            }

            vec3 tapW(vec3 acc, float wsum, vec3 c0, samplerExternalOES tex, vec2 tuv, vec2 off, float sigma, inout float ws) {
                vec3 n = texture2D(tex, tuv + off).rgb;
                vec3 d = n - c0;
                float w = exp(-dot(d, d) / (sigma * sigma));
                ws = wsum + w;
                return acc + n * w;
            }

            vec3 lookupLut(sampler2D lut, vec3 color) {
                float blue = clamp(color.b, 0.0, 1.0) * 63.0;
                vec2 q1;
                q1.y = floor(floor(blue) / 8.0);
                q1.x = floor(blue) - q1.y * 8.0;
                vec2 q2;
                q2.y = floor(ceil(blue) / 8.0);
                q2.x = ceil(blue) - q2.y * 8.0;
                vec2 rg = clamp(color.rg, 0.0, 1.0);
                vec2 t1 = (q1 * 8.0 + 0.5 + rg * 7.0) / 64.0;
                vec2 t2 = (q2 * 8.0 + 0.5 + rg * 7.0) / 64.0;
                vec3 c1 = texture2D(lut, t1).rgb;
                vec3 c2 = texture2D(lut, t2).rgb;
                return mix(c1, c2, fract(blue));
            }
        """

        /** Unrolled matte slots keep GLSL ES 1.00 sampler indexing happy. */
        private fun buildFragSource(): String = buildString {
            append(
                "#extension GL_OES_EGL_image_external : require\n" +
                    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                "precision highp float;\n" +
                "#else\n" +
                "precision mediump float;\n" +
                "#endif\n" +
                    "varying vec2 vView;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform mat4 uTexMat;\n" +
                    "uniform vec4 uR;\nuniform vec4 uG;\nuniform vec4 uB;\nuniform vec4 uA;\nuniform vec4 uBias;\n" +
                    "uniform vec2 uTexel;\n" +
                    "uniform float uSmooth;\nuniform float uWhiten;\n" +
                    "uniform vec4 uFace;\nuniform vec4 uEyeL;\nuniform vec4 uEyeR;\nuniform vec2 uReshape;\n" +
                    "uniform sampler2D uLutTex;\n"
            )
            append(GradeShader.uniformDecls())
            append(BeautyShader.uniformDecls())
            for (i in 0 until MAX_MATTES) {
                append(
                    "uniform sampler2D uMatte$i;\n" +
                        "uniform vec4 uXfA$i;\nuniform vec2 uXfB$i;\n" +
                        "uniform vec4 uTint$i;\nuniform float uMode$i;\n"
                )
            }
            append(SHADER_HELPERS)
            append(GradeShader.HELPERS)
            append(BeautyShader.HELPERS)
            append(
                "void main() {\n" +
                    "  vec2 wuv = warpView(vView, uFace, uEyeL, uEyeR, uReshape);\n" +
                    "  vec2 tuv = (uTexMat * vec4(wuv, 0.0, 1.0)).xy;\n" +
                    "  vec4 c = texture2D(uTex, tuv);\n" +
                    "  vec3 rgb = c.rgb;\n" +
                    "  if (uSmooth > 0.003) {\n" +
                    "    float sigma = mix(0.02, 0.22, uSmooth);\n" +
                    "    vec3 acc = c.rgb;\n" +
                    "    float ws = 1.0;\n" +
                    "    vec2 px = uTexel;\n" +
                    "    acc = tapW(acc, ws, c.rgb, uTex, tuv, vec2(px.x, 0.0), sigma, ws);\n" +
                    "    acc = tapW(acc, ws, c.rgb, uTex, tuv, vec2(-px.x, 0.0), sigma, ws);\n" +
                    "    acc = tapW(acc, ws, c.rgb, uTex, tuv, vec2(0.0, px.y), sigma, ws);\n" +
                    "    acc = tapW(acc, ws, c.rgb, uTex, tuv, vec2(0.0, -px.y), sigma, ws);\n" +
                    "    acc = tapW(acc, ws, c.rgb, uTex, tuv, vec2(px.x, px.y), sigma, ws);\n" +
                    "    acc = tapW(acc, ws, c.rgb, uTex, tuv, vec2(-px.x, px.y), sigma, ws);\n" +
                    "    acc = tapW(acc, ws, c.rgb, uTex, tuv, vec2(px.x, -px.y), sigma, ws);\n" +
                    "    acc = tapW(acc, ws, c.rgb, uTex, tuv, vec2(-px.x, -px.y), sigma, ws);\n" +
                    "    vec3 blurred = acc / ws;\n" +
                    "    rgb = beautyComposite(rgb, blurred, uSmooth);\n" +
                    "  }\n" +
                    BeautyShader.retouchCall("wuv", "rgb") +
                    BeautyShader.sharpenCall("tuv", "rgb")
            )
            for (i in 0 until MAX_MATTES) {
                append(
                    "  if (uTint$i.a > 0.003) {\n" +
                        "    vec2 muv = vec2(uXfA$i.x * wuv.x + uXfA$i.y * wuv.y + uXfB$i.x,\n" +
                        "                    uXfA$i.z * wuv.x + uXfA$i.w * wuv.y + uXfB$i.y);\n" +
                        "    if (muv.x >= 0.0 && muv.x <= 1.0 && muv.y >= 0.0 && muv.y <= 1.0) {\n" +
                        "      vec4 mk = texture2D(uMatte$i, muv);\n" +
                        "      float cov = mk.a * uTint$i.a;\n" +
                        "      if (cov > 0.003) {\n" +
                        "        vec3 mcol;\n" +
                        "        if (uMode$i > 0.5) { mcol = lookupLut(uLutTex, rgb); }\n" +
                        "        else { mcol = rgb * mk.rgb; }\n" +
                        "        rgb = mix(rgb, mcol * uTint$i.rgb, cov);\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n"
                )
            }
            append(
                GradeShader.curveCall("rgb") +
                    "  vec4 g = vec4(\n" +
                    "    dot(vec4(rgb, c.a), uR) + uBias.x,\n" +
                    "    dot(vec4(rgb, c.a), uG) + uBias.y,\n" +
                    "    dot(vec4(rgb, c.a), uB) + uBias.z,\n" +
                    "    dot(c, uA) + uBias.w);\n" +
                    "  g.rgb += uWhiten * vec3(0.07, 0.06, 0.05);\n" +
                    BeautyShader.knobsCall("g.rgb") +
                    GradeShader.overlayCalls("vView", "g.rgb") +
                    "  gl_FragColor = g;\n" +
                    "}\n"
            )
        }

        /**
         * Minimal OES passthrough: only used when the full program fails to
         * link on an exotic driver — live preview degrades to unfiltered
         * camera; the grade/beauty/matte looks still reach captures via the
         * offscreen baker (which has its own CPU fallback).
         */
        private val PASSTHROUGH_FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
                "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                "precision highp float;\n" +
                "#else\n" +
                "precision mediump float;\n" +
                "#endif\n" +
                "varying vec2 vView;\n" +
                "uniform samplerExternalOES uTex;\n" +
                "uniform mat4 uTexMat;\n" +
                "void main() {\n" +
                "  vec2 tuv = (uTexMat * vec4(vView, 0.0, 1.0)).xy;\n" +
                "  gl_FragColor = texture2D(uTex, tuv);\n" +
                "}\n"

        /** Neutral 1x1 white 2D texture for unused grade samplers. */
        private fun createWhiteTex(): Int =
            createBitmap(1, 1, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(0xFFFFFFFF.toInt())
            }.let { bmp ->
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
                )
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                bmp.recycle()
                ids[0]
            }

        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }
    }
}

/** GL texture-cache entry (mask/LUT bitmaps). Top level: never inside the renderer. */
private class GlTex(val id: Int, val w: Int, val h: Int)
