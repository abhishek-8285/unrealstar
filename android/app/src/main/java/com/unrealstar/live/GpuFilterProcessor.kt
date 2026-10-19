package com.unrealstar.live

import android.graphics.Bitmap
import android.graphics.Paint
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Offscreen (Pbuffer) OpenGL ES 2.0 still baker.
 *
 * The same math as the live shader runs here in one pass: reshape warp, the
 * grade color matrix, beauty smoothing/whiten, makeup masks (LUT recolor or
 * multiply, anchored by the tracked face), and — on lite devices — the
 * Instagram tint overlay, so posters match what the user saw.
 *
 * Any GL failure falls back to the Canvas/CPU path ([cpuBake]), which
 * implements every step including a mesh warp and exact per-pixel mattes —
 * captures never fail because the GPU did, and never silently drop a look.
 *
 * The EGL display, context and one pbuffer surface are retained across
 * calls (the surface is re-created only when the bake size changes) and all
 * bakes are serialized on an internal lock. Call off the main thread.
 */
object GpuFilterProcessor {

    private const val TAG = "GpuFilter"
    private const val MAX_MATTES = 5
    private const val MATTE_UNIT_BASE = 1
    private const val LUT_UNIT = 6

    private const val VERT = """
        attribute vec4 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() { gl_Position = aPos; vUV = aUV; }
    """

    /** Shared with the live shader's helpers (warp + LUT); see GpuPreviewView. */
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

        vec3 tapW(vec3 acc, float wsum, vec3 c0, sampler2D tex, vec2 tuv, vec2 off, float sigma, inout float ws) {
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
    private fun fragSource(): String = buildString {
        append(
            // highp keeps screen-UV math exact; mediump quantizes UVs to a
            // step of about one source texel at typical crop/upscale factors
            // (reads as an overall softness in the preview/bake).
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                "precision highp float;\n" +
                "#else\n" +
                "precision mediump float;\n" +
                "#endif\n" +
                "varying vec2 vUV;\n" +
                "uniform sampler2D uTex;\n" +
                "uniform vec4 uR;\nuniform vec4 uG;\nuniform vec4 uB;\nuniform vec4 uA;\nuniform vec4 uBias;\n" +
                "uniform vec2 uTexel;\n" +
                "uniform float uSmooth;\nuniform float uWhiten;\n" +
                "uniform vec4 uFace;\nuniform vec4 uEyeL;\nuniform vec4 uEyeR;\nuniform vec2 uReshape;\n" +
                "uniform sampler2D uLutTex;\n" +
                "uniform vec4 uTintBake;\n"
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
                "  vec2 wuv = warpView(vUV, uFace, uEyeL, uEyeR, uReshape);\n" +
                "  vec4 c = texture2D(uTex, wuv);\n" +
                "  vec3 rgb = c.rgb;\n" +
                "  if (uSmooth > 0.003) {\n" +
                "    float sigma = mix(0.02, 0.22, uSmooth);\n" +
                "    vec3 acc = c.rgb;\n" +
                "    float ws = 1.0;\n" +
                "    vec2 px = uTexel;\n" +
                "    acc = tapW(acc, ws, c.rgb, uTex, wuv, vec2(px.x, 0.0), sigma, ws);\n" +
                "    acc = tapW(acc, ws, c.rgb, uTex, wuv, vec2(-px.x, 0.0), sigma, ws);\n" +
                "    acc = tapW(acc, ws, c.rgb, uTex, wuv, vec2(0.0, px.y), sigma, ws);\n" +
                "    acc = tapW(acc, ws, c.rgb, uTex, wuv, vec2(0.0, -px.y), sigma, ws);\n" +
                "    acc = tapW(acc, ws, c.rgb, uTex, wuv, vec2(px.x, px.y), sigma, ws);\n" +
                "    acc = tapW(acc, ws, c.rgb, uTex, wuv, vec2(-px.x, px.y), sigma, ws);\n" +
                "    acc = tapW(acc, ws, c.rgb, uTex, wuv, vec2(px.x, -px.y), sigma, ws);\n" +
                "    acc = tapW(acc, ws, c.rgb, uTex, wuv, vec2(-px.x, -px.y), sigma, ws);\n" +
                "    vec3 blurred = acc / ws;\n" +
                "    rgb = beautyComposite(rgb, blurred, uSmooth);\n" +
                "  }\n" +
                BeautyShader.retouchCall("wuv", "rgb") +
                BeautyShader.sharpenCall("wuv", "rgb")
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
                GradeShader.overlayCalls("vUV", "g.rgb") +
                "  g.rgb = mix(g.rgb, uTintBake.rgb, uTintBake.a);\n" +
                "  gl_FragColor = g;\n" +
                "}\n"
        )
    }

    fun apply(
        src: Bitmap,
        filter: CameraFilter,
        beauty: BeautyParams = BeautyParams(),
        face: FaceGeometry? = null,
        layers: List<MatteLayer> = emptyList(),
        canonical: CanonicalFace = CanonicalFace.EMPTY,
        tintColor: Int = 0,
        tintAlpha: Float = 0f,
        grade: GradeImages? = null,
        gradeStrength: Float = 1f
    ): Bitmap {
        val s = beauty.smooth.coerceIn(0f, 1f)
        val w = beauty.whiten.coerceIn(0f, 1f)
        val shp = beauty.sharpen.coerceIn(0f, 1f)
        val con = beauty.contrast.coerceIn(0f, 1f)
        val sat = beauty.saturation.coerceIn(0f, 1f)
        val rud = beauty.ruddy.coerceIn(0f, 1f)
        val eye = beauty.eyeTeeth.coerceIn(0f, 1f)
        val slim = beauty.slimFace.coerceIn(0f, 1f)
        val eyes = beauty.bigEyes.coerceIn(0f, 1f)
        val reshape = face != null && (slim > 0.003f || eyes > 0.003f)
        val makeup = face != null && layers.isNotEmpty()
        val tint = tintAlpha.coerceIn(0f, 1f)
        if (filter.id == "normal" && s <= 0.003f && w <= 0.003f &&
            shp <= 0.003f && con <= 0.003f && sat <= 0.003f && rud <= 0.003f &&
            eye <= 0.003f && !reshape && !makeup && tint <= 0.003f && filter.recipe == null
        ) {
            return src
        }
        return try {
            renderOffscreen(
                src, filter, s, w, shp, con, sat, rud, eye,
                slim, eyes, face, layers, canonical, tintColor, tint, grade, gradeStrength
            )
        } catch (t: Throwable) {
            Log.w(TAG, "GPU bake failed, CPU fallback", t)
            cpuBake(src, filter, beauty, face, layers, canonical, tintColor, tint, grade, gradeStrength)
        }
    }

    /**
     * Lite / fallback bake. Order matches the GPU shader: reshape warp →
     * adaptive smoothing composite → eyes/teeth retouch → sharpen → makeup →
     * (curve | matrix) grade → whiten → look knobs → grade overlays →
     * (lite) tint overlay. The smoothing blur itself stays the cheap box
     * approximation; the adaptive composite on top is the exact twin.
     */
    fun cpuBake(
        src: Bitmap,
        filter: CameraFilter,
        beauty: BeautyParams,
        face: FaceGeometry?,
        layers: List<MatteLayer>,
        canonical: CanonicalFace,
        tintColor: Int,
        tintAlpha: Float,
        grade: GradeImages? = null,
        gradeStrength: Float = 1f
    ): Bitmap {
        val s = beauty.smooth.coerceIn(0f, 1f)
        val w = beauty.whiten.coerceIn(0f, 1f)
        val shp = beauty.sharpen.coerceIn(0f, 1f)
        val con = beauty.contrast.coerceIn(0f, 1f)
        val sat = beauty.saturation.coerceIn(0f, 1f)
        val rud = beauty.ruddy.coerceIn(0f, 1f)
        val eye = beauty.eyeTeeth.coerceIn(0f, 1f)
        val slim = beauty.slimFace.coerceIn(0f, 1f)
        val eyes = beauty.bigEyes.coerceIn(0f, 1f)

        var work = src
        var owned = false
        if (face != null && (slim > 0.003f || eyes > 0.003f)) {
            work = meshWarp(src, face, slim, eyes)
            owned = true
        }
        if (s > 0.003f) {
            // Adaptive composite (shader parity): blur without mixing, then
            // per-pixel curve/val2/unsharp/skin math via [BeautyMath].
            val blurred = blurCopy(work)
            val composited = compositeCpu(work, blurred, s)
            runCatching { blurred.recycle() }
            if (owned) runCatching { work.recycle() }
            work = composited
            owned = true
        }
        if (face != null && eye > 0.003f) {
            if (!owned) {
                work = work.copy(Bitmap.Config.ARGB_8888, true)
                owned = true
            }
            retouchCpu(work, face, eye)
        }
        if (shp > 0.003f) {
            if (!owned) {
                work = work.copy(Bitmap.Config.ARGB_8888, true)
                owned = true
            }
            sharpenCpu(work, shp * BeautyShader.SHARPEN_SCALE)
        }
        if (face != null && layers.isNotEmpty()) {
            if (!owned) {
                work = work.copy(Bitmap.Config.ARGB_8888, true)
                owned = true
            }
            // Stills show the full frame: raw fractions, no center-crop map.
            val sim = MatteGeometry.similarityFor(
                face, canonical, work.width.toFloat(), work.height.toFloat(), crop = false
            )
            if (sim != null) {
                for (layer in layers) {
                    try {
                        MatteCompositor.applyLayerCpu(work, layer, sim)
                    } catch (t: Throwable) {
                        Log.w(TAG, "matte bake skipped: ${layer.item.id}", t)
                    }
                }
            }
        }
        // Grade: the recipe's curve strip replaces the 4x5 matrix (GPU parity).
        val graded = if (filter.recipe?.curve != null && grade?.curve != null) {
            applyCurveCpu(work, grade.curve, gradeStrength)
        } else {
            canvasGrade(work, filter) // always a fresh bitmap
        }
        if (owned) runCatching { work.recycle() }
        if (w > 0.003f) {
            val canvas = android.graphics.Canvas(graded)
            canvas.drawColor(
                android.graphics.Color.argb(
                    (w * 20).toInt().coerceIn(0, 40), 255, 244, 235
                )
            )
        }
        if (sat > 0.003f || con > 0.003f || rud > 0.003f) {
            val pw = graded.width
            val ph = graded.height
            val kpx = IntArray(pw * ph)
            graded.getPixels(kpx, 0, pw, 0, 0, pw, ph)
            for (i in kpx.indices) kpx[i] = BeautyMath.knobs(kpx[i], sat, con, rud)
            graded.setPixels(kpx, 0, pw, 0, 0, pw, ph)
        }
        // Recipe overlay masks (multiply/screen/overlay/soft-light), exact.
        if (filter.recipe != null && grade != null && grade.overlays.isNotEmpty()) {
            applyGradeOverlaysCpu(graded, grade, gradeStrength)
        }
        if (tintAlpha > 0.003f) {
            val canvas = android.graphics.Canvas(graded)
            canvas.drawColor(
                android.graphics.Color.argb(
                    (tintAlpha * 255).toInt().coerceIn(0, 255),
                    android.graphics.Color.red(tintColor),
                    android.graphics.Color.green(tintColor),
                    android.graphics.Color.blue(tintColor)
                )
            )
        }
        return graded
    }

    /** Exact CPU twin of the shader's gradeCurve stage on a full bitmap. */
    private fun applyCurveCpu(src: Bitmap, curve: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val sw = curve.width
        val sh = curve.height
        val strip = IntArray(sw * sh)
        curve.getPixels(strip, 0, sw, 0, 0, sw, sh)
        val luts = GradeMath.lutsFromStrip(strip, sw, sh)
        if (luts.all { it != null }) {
            for (i in px.indices) {
                px[i] = GradeMath.applyCurve(luts, px[i], strength)
            }
        }
        val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Exact CPU twin of the shader's gradeSlot passes (scaled masks). */
    private fun applyGradeOverlaysCpu(target: Bitmap, grade: GradeImages, strength: Float) {
        val w = target.width
        val h = target.height
        val px = IntArray(w * h)
        target.getPixels(px, 0, w, 0, 0, w, h)
        for ((mask, ov) in grade.overlays) {
            val mw = mask.width
            val mh = mask.height
            val mp = IntArray(mw * mh)
            mask.getPixels(mp, 0, mw, 0, 0, mw, mh)
            val opacity = (ov.opacity * strength).coerceIn(0f, 1f)
            if (opacity <= 0.003f) continue
            for (y in 0 until h) {
                val my = ((y.toFloat() / h * mh).toInt()).coerceIn(0, mh - 1)
                for (x in 0 until w) {
                    val mx = ((x.toFloat() / w * mw).toInt()).coerceIn(0, mw - 1)
                    val srcPx = mp[my * mw + mx]
                    val si = y * w + x
                    px[si] = GradeMath.applyOverlay(
                        px[si], ov.blend,
                        (srcPx shr 16) and 0xFF,
                        (srcPx shr 8) and 0xFF,
                        srcPx and 0xFF,
                        opacity
                    )
                }
            }
        }
        target.setPixels(px, 0, w, 0, 0, w, h)
    }

    /**
     * CPU mesh warp for stills, matching the shader's warpView: a slim-face
     * horizontal pull toward the face center plus eye-radius expansion.
     * Vertices are destination positions for a uniform source grid.
     */
    private fun meshWarp(src: Bitmap, face: FaceGeometry, slim: Float, bigEyes: Float): Bitmap {
        val w = src.width
        val h = src.height
        val n = 24
        val verts = FloatArray((n + 1) * (n + 1) * 2)
        val fw = face.widthFrac.coerceAtLeast(0.05f)
        val eyeL = face.eyeL
        val eyeR = face.eyeR
        val eyesOn = eyeL != null && eyeR != null
        for (gy in 0..n) {
            for (gx in 0..n) {
                var vx = gx.toFloat() / n
                var vy = gy.toFloat() / n
                val dx = vx - face.centerX
                val dy = (vy - face.centerY) / 1.4f
                val m = exp(-(dx * dx + dy * dy) / (fw * fw * 0.35f)) * slim
                vx += (face.centerX - vx) * (m * 0.35f).coerceIn(0f, 0.5f)
                if (eyesOn) {
                    val er = fw * 0.18f
                    for (e in listOf(eyeL!!, eyeR!!)) {
                        val d = hypot(vx - e.first, vy - e.second)
                        val t = ((d - 0f) / er).coerceIn(0f, 1f)
                        val k = (1f - t * t * (3f - 2f * t)) * bigEyes
                        vx += ((e.first + (vx - e.first) * 0.80f) - vx) * k
                        vy += ((e.second + (vy - e.second) * 0.80f) - vy) * k
                    }
                }
                val idx = (gy * (n + 1) + gx) * 2
                verts[idx] = vx * w
                verts[idx + 1] = vy * h
            }
        }
        val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmapMesh(src, n, n, verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Downscale → two-pass box blur → upscale. The composite's `blurred`
     * source, without the old strength-mix (the adaptive composite does the
     * mixing per pixel now).
     */
    private fun blurCopy(src: Bitmap): Bitmap {
        val sw = (src.width / 4).coerceAtLeast(1)
        val sh = (src.height / 4).coerceAtLeast(1)
        val small = src.scale(sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(2) { boxBlurPass(px, sw, sh, 2) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        val out = createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(
            small, null, android.graphics.Rect(0, 0, out.width, out.height),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        small.recycle()
        return out
    }

    /** Exact CPU twin of the shader's adaptive-smoothing composite. */
    private fun compositeCpu(src: Bitmap, blurred: Bitmap, s: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        val bp = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        blurred.getPixels(bp, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            px[i] = BeautyMath.composite(px[i], bp[i], s)
        }
        val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Eyes/teeth whitening on the still: soft eye + mouth ellipses in frame
     * fractions, same coverage math as the shader's `retouchCall` (mouth
     * corners → centre + radius, same as the live uniform).
     */
    private fun retouchCpu(target: Bitmap, face: FaceGeometry, amount: Float) {
        val w = target.width
        val h = target.height
        val er = face.widthFrac.coerceAtLeast(0.05f) * 0.18f
        val regions = ArrayList<FloatArray>(3) // cx, cy, radius (frame fractions)
        face.eyeL?.let { regions += floatArrayOf(it.first, it.second, er) }
        face.eyeR?.let { regions += floatArrayOf(it.first, it.second, er) }
        val mc = face.mouthCenter
        val ml = face.mouthL
        val mr = face.mouthR
        if (mc != null && ml != null && mr != null) {
            val r = (hypot(ml.first - mr.first, ml.second - mr.second) * 0.4f)
                .coerceAtLeast(0.02f)
            regions += floatArrayOf(mc.first, mc.second, r)
        }
        if (regions.isEmpty()) return
        val px = IntArray(w * h)
        target.getPixels(px, 0, w, 0, 0, w, h)
        for (rg in regions) {
            val cx = rg[0]
            val cy = rg[1]
            val rad = rg[2]
            val x0 = ((cx - rad) * w).toInt().coerceIn(0, w - 1)
            val x1 = ((cx + rad) * w).toInt().coerceIn(0, w - 1)
            val y0 = ((cy - rad) * h).toInt().coerceIn(0, h - 1)
            val y1 = ((cy + rad) * h).toInt().coerceIn(0, h - 1)
            for (y in y0..y1) {
                val fy = y.toFloat() / h
                for (x in x0..x1) {
                    val d = hypot(x.toFloat() / w - cx, fy - cy) / rad
                    val t = d.coerceIn(0f, 1f)
                    val cov = 1f - t * t * (3f - 2f * t)
                    if (cov > 0.003f) {
                        val i = y * w + x
                        px[i] = BeautyMath.retouch(px[i], amount, cov)
                    }
                }
            }
        }
        target.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Full-frame sharpen: Banuba 5c − 4 diagonal kernel via [BeautyMath]. */
    private fun sharpenCpu(target: Bitmap, amount: Float) {
        val w = target.width
        val h = target.height
        val px = IntArray(w * h)
        target.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val nb = IntArray(4)
        for (y in 0 until h) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y1 = (y + 1).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x1 = (x + 1).coerceAtMost(w - 1)
                nb[0] = px[y0 * w + x0]
                nb[1] = px[y0 * w + x1]
                nb[2] = px[y1 * w + x0]
                nb[3] = px[y1 * w + x1]
                out[y * w + x] = BeautyMath.sharpen(px[y * w + x], nb, amount)
            }
        }
        target.setPixels(out, 0, w, 0, 0, w, h)
    }

    /** Separable box blur in place, radius r. */
    private fun boxBlurPass(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        // Horizontal.
        for (y in 0 until h) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (x in -r..r) {
                val c = px[y * w + x.coerceIn(0, w - 1)]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (x in 0 until w) {
                val n = 2 * r + 1
                tmp[y * w + x] = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
                val out = px[y * w + (x - r).coerceIn(0, w - 1)]
                val inn = px[y * w + (x + r + 1).coerceIn(0, w - 1)]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
        // Vertical.
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (y in -r..r) {
                val c = tmp[y.coerceIn(0, h - 1) * w + x]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (y in 0 until h) {
                val n = 2 * r + 1
                px[y * w + x] = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
                val out = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun canvasGrade(src: Bitmap, filter: CameraFilter): Bitmap {
        val out = createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(filter.colorMatrix)
        )
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun renderOffscreen(
        src: Bitmap,
        filter: CameraFilter,
        smooth: Float,
        whiten: Float,
        sharpen: Float,
        contrast: Float,
        saturation: Float,
        ruddy: Float,
        eyeTeeth: Float,
        slim: Float,
        bigEyes: Float,
        face: FaceGeometry?,
        layers: List<MatteLayer>,
        canonical: CanonicalFace,
        tintColor: Int,
        tintAlpha: Float,
        grade: GradeImages?,
        gradeStrength: Float
    ): Bitmap {
        val w = src.width
        val h = src.height
        require(w > 0 && h > 0)

        // Retained display+context (never eglTerminate'd): eglTerminate on
        // the shared default display invalidates every context on it on
        // several drivers — including the live preview's. The pbuffer is
        // retained too and only re-created when the bake size changes, so a
        // batch of same-size bakes touches EGL exactly once. Serialized: EGL
        // contexts are single-threaded.
        synchronized(eglLock) {
            check(ensureEglLocked()) { "egl init" }
            val display = eglDisplay
            check(ensurePbufferLocked(w, h)) { "pbuffer" }
            val surface = eglSurface
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                // The retained pbuffer can survive a driver reset in a bad
                // state; drop it so the next bake rebuilds clean.
                destroyPbufferLocked()
                error("makeCurrent")
            }
            var ok = false
            try {
                val result = draw(
                    src, filter, w, h, smooth, whiten, sharpen, contrast, saturation, ruddy, eyeTeeth,
                    slim, bigEyes, face, layers, canonical, tintColor, tintAlpha, grade, gradeStrength
                )
                check(EGL14.eglSwapBuffers(display, surface))
                ok = true
                return result
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                // Failed draw/swap: the surface may be bad, rebuild next time.
                if (!ok) destroyPbufferLocked()
            }
        }
    }

    private val eglLock = Any()
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    /** Retained per-bake pbuffer surface + the size it was created for. */
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglSurfaceW = 0
    private var eglSurfaceH = 0

    /** Neutral 1x1 white: a valid 2D binding for unused grade samplers. */
    private val WHITE_1X1: Bitmap by lazy {
        createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(-1) }
    }

    /** Row-major 4x5 identity used when a curve strip replaces the matrix. */
    private val IDENTITY_4X5 = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /** Max fragment sampler units (must be current-context); draw-time guard. */
    private val maxTexUnits: Int
        get() {
            val v = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, v, 0)
            return v[0]
        }

    /** Caller must hold [eglLock]. Rebuilds from scratch on any failure. */
    private fun ensureEglLocked(): Boolean {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglContext != EGL14.EGL_NO_CONTEXT && eglConfig != null
        ) {
            return true
        }
        teardownEglLocked()
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) || n[0] <= 0) {
                EGL14.eglTerminate(display)
                return false
            }
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                EGL14.eglTerminate(display)
                return false
            }
            eglDisplay = display
            eglContext = context
            eglConfig = configs[0]
            true
        } catch (_: Throwable) {
            teardownEglLocked()
            false
        }
    }

    /**
     * Caller must hold [eglLock]. Reuses the retained pbuffer when it already
     * matches the bake size; re-creates it otherwise. The surface can only be
     * made current after this returns true.
     */
    private fun ensurePbufferLocked(w: Int, h: Int): Boolean {
        if (eglSurface != EGL14.EGL_NO_SURFACE && eglSurfaceW == w && eglSurfaceH == h) {
            return true
        }
        destroyPbufferLocked()
        return try {
            val attribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, attribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                false
            } else {
                eglSurface = surface
                eglSurfaceW = w
                eglSurfaceH = h
                true
            }
        } catch (_: Throwable) {
            destroyPbufferLocked()
            false
        }
    }

    /** Caller must hold [eglLock]. Drops the retained pbuffer (if any). */
    private fun destroyPbufferLocked() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            } catch (_: Throwable) {
            }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglSurfaceW = 0
        eglSurfaceH = 0
    }

    private fun teardownEglLocked() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                destroyPbufferLocked()
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
            }
        } catch (_: Throwable) {
        } finally {
            // Never eglTerminate the shared display here: see above.
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglConfig = null
            eglSurface = EGL14.EGL_NO_SURFACE
            eglSurfaceW = 0
            eglSurfaceH = 0
        }
    }

    private fun draw(
        src: Bitmap,
        filter: CameraFilter,
        w: Int,
        h: Int,
        smooth: Float,
        whiten: Float,
        sharpen: Float,
        contrast: Float,
        saturation: Float,
        ruddy: Float,
        eyeTeeth: Float,
        slim: Float,
        bigEyes: Float,
        face: FaceGeometry?,
        layers: List<MatteLayer>,
        canonical: CanonicalFace,
        tintColor: Int,
        tintAlpha: Float,
        grade: GradeImages?,
        gradeStrength: Float
    ): Bitmap {
        val prog = linkProgram(VERT, fragSource())
        // The grade stage raises the sampler budget to 11 (camera + 5 matte +
        // LUT + curve + 3 overlays). Hardware is ES3-class on this path
        // (>= 16 guaranteed); anything fewer throws into the CPU fallback so
        // captures keep the exact look rather than a degraded one.
        if (grade != null) {
            check(maxTexUnits >= 11) { "texture units too few: $maxTexUnits" }
        }
        GLES20.glUseProgram(prog)

        // Fullscreen quad (triangle strip).
        val pos: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        // Flip V: GL texture origin is bottom-left, Bitmap top-left. vUV ends
        // up top-down relative to the output bitmap — the same orientation as
        // FaceTracker fractions.
        val uv: FloatBuffer = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUV = GLES20.glGetAttribLocation(prog, "aUV")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, uv)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
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
        // The recipe's curve strip replaces the matrix when decodable — the
        // lite matrix stays as the no-curve fallback (GPU parity with live).
        val recipe = filter.recipe
        val curveTexId = grade?.curve?.let { uploadTexture(it, GradeShader.CURVE_UNIT) }
        val curved = curveTexId != null && recipe?.curve != null
        if (curved) {
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(prog, "uCurveStrength"),
                gradeStrength.coerceIn(0f, 1f)
            )
            GLES20.glUniform1i(
                GLES20.glGetUniformLocation(prog, "uCurveTex"), GradeShader.CURVE_UNIT
            )
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uCurveOn"), 1f)
        } else {
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uCurveOn"), 0f)
        }
        val m = if (curved) IDENTITY_4X5 else filter.colorMatrix
        fun row(r: Int) = floatArrayOf(m[r * 5], m[r * 5 + 1], m[r * 5 + 2], m[r * 5 + 3])
        val bias = floatArrayOf(m[4] / 255f, m[9] / 255f, m[14] / 255f, m[19])
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uR"), 1, row(0), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uG"), 1, row(1), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uB"), 1, row(2), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uA"), 1, row(3), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uBias"), 1, bias, 0)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(prog, "uTexel"),
            1f / w.coerceAtLeast(1), 1f / h.coerceAtLeast(1)
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSmooth"), smooth)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWhiten"), whiten)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSharpen"), sharpen)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uContrast"), contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSaturation"), saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRuddy"), ruddy)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uEyeTeeth"), eyeTeeth)
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(prog, "uFace"),
            face?.centerX ?: 0.5f, face?.centerY ?: 0.5f,
            face?.widthFrac ?: 0.3f, if (face != null) 1f else 0f
        )
        val eyesOk = face?.eyeL != null && face?.eyeR != null
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(prog, "uEyeL"),
            face?.eyeL?.first ?: 0.4f, face?.eyeL?.second ?: 0.4f,
            if (eyesOk) 1f else 0f, 0f
        )
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(prog, "uEyeR"),
            face?.eyeR?.first ?: 0.6f, face?.eyeR?.second ?: 0.4f,
            if (eyesOk) 1f else 0f, 0f
        )
        // Mouth anchor for the eyes/teeth retouch (fraction space like the
        // eye uniforms; the bake UV is unwarped-full-frame like the tracker).
        val mouthC = face?.mouthCenter
        val mdx = (face?.mouthL?.first ?: 0f) - (face?.mouthR?.first ?: 0f)
        val mdy = (face?.mouthL?.second ?: 0f) - (face?.mouthR?.second ?: 0f)
        val mouthR =
            if (mouthC != null) (hypot(mdx, mdy) * 0.4f).coerceAtLeast(0.02f) else 0.2f
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(prog, "uMouth"),
            mouthC?.first ?: 0.5f, mouthC?.second ?: 0.6f,
            mouthR, if (mouthC != null) 1f else 0f
        )
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uReshape"), slim, bigEyes)
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(prog, "uTintBake"),
            android.graphics.Color.red(tintColor) / 255f,
            android.graphics.Color.green(tintColor) / 255f,
            android.graphics.Color.blue(tintColor) / 255f,
            tintAlpha.coerceIn(0f, 1f)
        )

        // Makeup layers: fit once, upload one mask texture per layer, hand
        // the transform to the shader; every slot is written every call.
        val usedTextures = ArrayList<Int>(MAX_MATTES)
        // The curve texture uploaded above must be released with the rest.
        if (curveTexId != null) usedTextures += curveTexId else {
            // No curve this call: the uCurveTex sampler is still program-active,
            // so bind the neutral 1x1 white and keep it for the draw check.
            val whiteCurveId = uploadTexture(WHITE_1X1, GradeShader.CURVE_UNIT)
            usedTextures += whiteCurveId
            GLES20.glUniform1i(
                GLES20.glGetUniformLocation(prog, "uCurveTex"), GradeShader.CURVE_UNIT
            )
        }
        // Grade overlay slots: bind a real mask or the 1x1 white so every
        // sampler is valid for the draw (recipe-free slots get opacity 0).
        // Slot opacity scales with the grade strength (one "effect amount").
        WHITE_1X1.let { white ->
            for (i in 0 until GradeShader.MAX_OVERLAYS) {
                val entry = grade?.overlays?.getOrNull(i)
                val bmp = entry?.first ?: white
                GLES20.glUniform4f(
                    GLES20.glGetUniformLocation(prog, "uOvlA$i"),
                    entry?.second?.blend?.mode ?: 0f,
                    ((entry?.second?.opacity ?: 0f) * gradeStrength).coerceIn(0f, 1f),
                    0f, 0f
                )
                val ovlId = uploadTexture(bmp, GradeShader.OVL_UNIT_BASE + i)
                usedTextures += ovlId
                GLES20.glUniform1i(
                    GLES20.glGetUniformLocation(prog, "uOvlTex$i"),
                    GradeShader.OVL_UNIT_BASE + i
                )
            }
        }
        val sim = if (face != null && layers.isNotEmpty()) {
            MatteGeometry.similarityFor(face, canonical, w.toFloat(), h.toFloat(), crop = false)
        } else {
            null
        }
        var lutTexId = tex[0] // unused LUT unit points at the camera texture
        for (i in 0 until MAX_MATTES) {
            val layer = if (sim != null) layers.getOrNull(i) else null
            var tintR = 0f; var tintG = 0f; var tintB = 0f; var strength = 0f
            if (layer != null) {
                val cat = layer.category
                val maskId = uploadTexture(layer.mask, MATTE_UNIT_BASE + i)
                usedTextures += maskId
                val xf = MatteGeometry.viewFractionToMaskUv(
                    sim!!, cat.anchorX, cat.anchorY, cat.imageScale,
                    layer.mask.width, layer.mask.height, w.toFloat(), h.toFloat()
                )
                GLES20.glUniform4f(
                    GLES20.glGetUniformLocation(prog, "uXfA$i"),
                    xf[0], xf[1], xf[2], xf[3]
                )
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uXfB$i"), xf[4], xf[5])
                GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(prog, "uMode$i"),
                    if (cat.lutMode) 1f else 0f
                )
                GLES20.glUniform1i(
                    GLES20.glGetUniformLocation(prog, "uMatte$i"), MATTE_UNIT_BASE + i
                )
                val t = layer.item.tint
                tintR = t[0]; tintG = t[1]; tintB = t[2]
                strength = layer.intensity.coerceIn(0f, 1f)
                if (cat.lutMode) {
                    val lutBmp = layer.lut
                    if (lutBmp != null) {
                        lutTexId = uploadTexture(lutBmp, LUT_UNIT)
                        usedTextures += lutTexId
                    }
                }
            }
            GLES20.glUniform4f(
                GLES20.glGetUniformLocation(prog, "uTint$i"),
                tintR, tintG, tintB, strength
            )
            if (strength <= 0f) {
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uMode$i"), 0f)
            }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + LUT_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLutTex"), LUT_UNIT)

        GLES20.glViewport(0, 0, w, h)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw")

        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        checkGl("readPixels")

        // GL returns bottom-up RGBA; flip rows into ARGB_8888.
        val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
        if (usedTextures.isNotEmpty()) {
            val arrT = usedTextures.toIntArray()
            GLES20.glDeleteTextures(arrT.size, arrT, 0)
        }
        GLES20.glDeleteProgram(prog)
        return out
    }

    /** One-shot texture upload bound to [unit] (mask/LUT are per-call here). */
    private fun uploadTexture(bmp: Bitmap, unit: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return id[0]
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
