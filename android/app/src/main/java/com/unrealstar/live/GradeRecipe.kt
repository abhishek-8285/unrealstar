package com.unrealstar.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Instagram-style data-driven color grade.
 *
 * REA provenance (rea-out/com.instagram.android/FILTER_FINDINGS.md,
 * rea-out/com.snapchat.android/FILTER_FINDINGS.md): both competitors implement
 * filters as *data*: Instagram materializes IGLU manifests whose passes sample
 * a per-channel curve strip (`assets/filters/<brand>/map.png`, 256x3/256x1 RGB)
 * and grayscale/RGB masks (overlay/soft-light/vignette) with named blend modes
 * and opacity uniforms; Snapchat ships the same idea as `.lns` shader-scene
 * packages (`lookup.glsl` passes + LUT textures). Neither app's textures are
 * used here — CamStar generates its own curve/mask PNGs procedurally
 * (scripts/build_grade_assets.py) and keeps this Kotlin layer as the pure
 * recipe + math model.
 *
 * One recipe = optional channel curve + 0..3 screen-space overlay masks, each
 * with [GradeBlend] and opacity. When a recipe carries a curve it *replaces*
 * the 4x5 color matrix on the GPU and CPU-bake paths (the matrix remains the
 * lite-path approximation); matrix-only recipes keep the matrix — cross-channel
 * looks (true B&W, sepia) cannot be expressed by a per-channel curve.
 */
enum class GradeBlend(val mode: Float) {
    MULTIPLY(0f),
    SCREEN(1f),
    OVERLAY(2f),
    SOFT_LIGHT(3f);

    companion object {
        fun from(s: String): GradeBlend? =
            when (s.lowercase()) {
                "multiply" -> MULTIPLY
                "screen" -> SCREEN
                "overlay" -> OVERLAY
                "soft_light", "softlight" -> SOFT_LIGHT
                else -> null
            }
    }
}

/** One blended overlay pass: a screen-space mask texture + blend + opacity. */
data class GradeOverlay(val texture: String, val blend: GradeBlend, val opacity: Float)

/**
 * A whole filter recipe. Asset paths are relative to `assets/grade/`.
 * `strength` mirrors Instagram's named customization params
 * (default/min/max uniform triple); the UI slider writes per-filter values
 * through [com.unrealstar.live.FilterEngine.setGradeStrength].
 */
data class GradeRecipe(
    val id: String,
    val curve: String?,
    val overlays: List<GradeOverlay>,
    val strengthDefault: Float,
    val strengthMin: Float,
    val strengthMax: Float,
    /**
     * When the recipe comes from a runtime-installed pack
     * ([GradePackLoader]), the absolute dir its asset paths resolve against;
     * null = the shipped `assets/grade/` tree.
     */
    val packDir: String? = null
)

object GradeCatalog {

    private const val TAG = "GradeCatalog"
    const val DIR = "grade"
    const val CATALOG = "$DIR/catalog.json"

    /**
     * Parses the catalog JSON. Pure JSON logic (org.json is also on the JVM
     * test classpath) so tests can exercise it without Android. Unknown ids
     * and malformed entries are skipped — a catalog never breaks the camera.
     */
    fun parse(text: String): Map<String, GradeRecipe> {
        val out = LinkedHashMap<String, GradeRecipe>()
        val root = JSONObject(text)
        val filters = root.optJSONArray("filters") ?: JSONArray()
        for (i in 0 until filters.length()) {
            try {
                val f = filters.getJSONObject(i)
                val id = f.optString("id")
                if (id.isEmpty()) continue
                val obj = kotlin.runCatching { f.getJSONObject("strength") }.getOrNull()
                val overlays =
                    f.optJSONArray("overlays")?.let { arr ->
                        buildList {
                            for (j in 0 until arr.length()) {
                                val o = arr.getJSONObject(j)
                                val tex = o.optString("texture")
                                val blend = GradeBlend.from(o.optString("blend")) ?: continue
                                val opacity = o.optDouble("opacity", 0.0)
                                if (tex.isEmpty() || opacity <= 0.0) continue
                                add(GradeOverlay(tex, blend, opacity.toFloat()))
                            }
                        }
                    } ?: emptyList()
                val curve = f.optString("curve").takeIf { it.isNotEmpty() }
                out[id] = GradeRecipe(
                    id = id,
                    curve = curve,
                    overlays = overlays,
                    strengthDefault = (obj?.optDouble("default", 1.0) ?: 1.0).toFloat(),
                    strengthMin = (obj?.optDouble("min", 0.0) ?: 0.0).toFloat(),
                    strengthMax = (obj?.optDouble("max", 1.0) ?: 1.0).toFloat()
                )
            } catch (t: Throwable) {
                Log.w(TAG, "grade catalog entry skipped", t)
            }
        }
        return out
    }

    /** Loads `assets/grade/catalog.json`. Missing/broken catalog = no recipes. */
    fun load(context: Context): Map<String, GradeRecipe> =
        try {
            context.assets.open(CATALOG).bufferedReader().use { parse(it.readText()) }
        } catch (t: Throwable) {
            Log.w(TAG, "grade catalog unavailable (recipe-less filters)", t)
            emptyMap()
        }
}

/**
 * Exact per-pixel grade math shared by the CPU bake; each formula is the twin
 * of the GLSL in [GradeShader]. Inputs/outputs are 8-bit ARGB ints — camera
 * frames are 8-bit, so indexing a 256-entry LUT with `floor(c * 255)` in the
 * shader is exactly [lut][input] here.
 */
object GradeMath {

    /**
     * Builds 3 LUT tables from a 256x3 (or wider) curve strip's pixel row:
     * row 0 = R mapping, row 1 = G, row 2 = B (each stored in its own channel).
     * Returns {rLut, gLut, bLut} as 256-entry arrays, or nulls when the strip
     * is unusable.
     */
    fun lutsFromStrip(pixels: IntArray, width: Int, height: Int): Array<IntArray?> {
        if (width < 256 || height < 3) return arrayOfNulls(3)
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        for (i in 0 until 256) {
            r[i] = (pixels[i] shr 16) and 0xFF
            g[i] = (pixels[width + i] shr 8) and 0xFF
            b[i] = pixels[2 * width + i] and 0xFF
        }
        return arrayOf(r, g, b)
    }

    /** Per-pixel curve application (curve stage: `mix(rgb, lut, strength)`). */
    fun applyCurve(
        luts: Array<IntArray?>,
        argb: Int,
        strength: Float
    ): Int {
        val a = argb and 0xFF000000.toInt()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val lr = luts[0]
        val lg = luts[1]
        val lb = luts[2]
        if (lr == null || lg == null || lb == null) return argb
        val nr = mixc(r, lr[r], strength)
        val ng = mixc(g, lg[g], strength)
        val nb = mixc(b, lb[b], strength)
        return a or (nr shl 16) or (ng shl 8) or nb
    }

    /** Channel blend — the exact CPU twin of GradeShader.gradeBlend. */
    fun blendChannel(mode: GradeBlend, base: Int, src: Int): Int {
        val fb = base / 255f
        val fs = src / 255f
        val v = when (mode) {
            GradeBlend.MULTIPLY -> fb * fs
            GradeBlend.SCREEN -> 1f - (1f - fb) * (1f - fs)
            GradeBlend.OVERLAY ->
                if (fb < 0.5f) 2f * fb * fs
                else 1f - 2f * (1f - fb) * (1f - fs)
            GradeBlend.SOFT_LIGHT ->
                if (fs < 0.5f)
                    2f * fb * fs + fb * fb * (1f - 2f * fs)
                else
                    fb + (2f * fs - 1f) * (kotlin.math.sqrt(kotlin.math.abs(fb)) - fb)
        }
        return (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Full overlay pass: mix(base, blend(src over base), opacity), ARGB in. */
    fun applyOverlay(argb: Int, mode: GradeBlend, sr: Int, sg: Int, sb: Int, opacity: Float): Int {
        val a = argb and 0xFF000000.toInt()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val br = mixc(r, blendChannel(mode, r, sr), opacity)
        val bg = mixc(g, blendChannel(mode, g, sg), opacity)
        val bb = mixc(b, blendChannel(mode, b, sb), opacity)
        return a or (br shl 16) or (bg shl 8) or bb
    }

    private fun mixc(c0: Int, c1: Int, t: Float): Int {
        if (t <= 0f) return c0
        if (t >= 1f) return c1
        return (c0 + (c1 - c0) * t + 0.5f).toInt().coerceIn(0, 255)
    }
}

/** Decodes grade assets relative to [GradeCatalog.DIR]. Android-only layer. */
object GradeAssets {

    private const val TAG = "GradeAssets"

    /**
     * Decode-once cache keyed by asset path. The live GL thread hits this
     * every frame (bindGradeOverlays/curve bind) — decoding a PNG per frame
     * would stall the preview; the matte path has the same decode-upstream
     * shape ([MatteLayer.mask]). Decoded bitmaps are immutable and read-only
     * (GL upload + CPU getPixels), so sharing them across threads is safe.
     * Total footprint is bounded by the catalog (≈10 filters × ≤4 small
     * textures, well under 1 MB).
     */
    private val cache = HashMap<String, Bitmap?>()

    /**
     * Resolves a recipe-relative asset path to a bitmap. Shipped recipes
     * (no [GradeRecipe.packDir]) read from `assets/grade/`; pack recipes read
     * from their installed pack dir on disk. The cache key includes the
     * source root so a pack overriding a builtin filter's `curve.png` never
     * collisions-caches the shipped bitmap under one key. Both roots produce
     * immutable read-only bitmaps, shared safely across GL + bake threads.
     */
    fun decode(context: Context, recipe: GradeRecipe?, relPath: String): Bitmap? {
        val packDir = recipe?.packDir
        return if (packDir == null) decodeFromAssets(context, relPath)
        else decodeFromFile("$packDir/$relPath")
    }

    private fun decodeFromAssets(context: Context, path: String): Bitmap? = synchronized(cache) {
        if (cache.containsKey(path)) return cache[path]
        val bmp = try {
            context.assets.open("${GradeCatalog.DIR}/$path").use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "grade asset decode failed: $path", t)
            null
        }
        cache[path] = bmp
        bmp
    }

    private fun decodeFromFile(absPath: String): Bitmap? = synchronized(cache) {
        val key = "pack|$absPath"
        if (cache.containsKey(key)) return cache[key]
        val bmp = try {
            BitmapFactory.decodeFile(absPath)
        } catch (t: Throwable) {
            Log.w(TAG, "grade asset decode failed: $absPath", t)
            null
        }
        cache[key] = bmp
        bmp
    }
}

/** Curve + overlay bitmaps resolved once per filter for the CPU bake. */
class GradeImages(
    val curve: Bitmap?,
    val overlays: List<Pair<Bitmap, GradeOverlay>>
)
