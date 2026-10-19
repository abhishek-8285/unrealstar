package com.unrealstar.live

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chooses which path renders the live looks (see [LIVE_GPU_FILTERING];
 * lite is the product default) and applies Instagram-style filters.
 *
 * - LIGHTWEIGHT: fullscreen tint overlay View over PreviewView for the live
 *   color look, and a face-anchored makeup overlay ([MatteCompositor]) above
 *   it. No per-frame shader, safe on low-RAM / old-GPU phones.
 * - GPU: live GLES preview ([GpuPreviewView]) with per-pixel shaders for the
 *   color grade, beauty, reshape warp and makeup masks; stills bake through
 *   the same math offscreen ([GpuFilterProcessor]).
 *
 * PreviewView is a SurfaceView — a ColorMatrix cannot be applied to it
 * directly, hence the overlay approach for live preview on lite devices.
 * Captured stills always go through the full bake (grade + beauty + reshape +
 * makeup) so posters match the committed look.
 */
data class BeautyParams(
    val smooth: Float = 0f,
    val whiten: Float = 0f,
    val slimFace: Float = 0f,
    val bigEyes: Float = 0f,
    // Retouch + look knobs (REA port of Banuba's smooth/retouch stages; stage
    // math in [BeautyShader]). All 0..1; GPU live + still bakes only — the
    // lite live path intentionally does not simulate these.
    val sharpen: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val ruddy: Float = 0f,
    val eyeTeeth: Float = 0f
)

class FilterEngine(private val context: Context) {

    private val TAG = "FilterEngine"

    enum class Mode { LIGHTWEIGHT, GPU }

    val mode: Mode by lazy { decideMode() }

    /**
     * The camera filter list with REA-grade recipes from
     * assets/grade/catalog.json attached by id, plus any runtime-installed
     * pack overrides/extensions ([GradePackLoader], Snap's `file_url`
     * model). Rebuilt by [refreshGrades]; ids/order stay stable otherwise.
     */
    var filters: List<CameraFilter> = buildFilters()
        private set

    private fun buildFilters(): List<CameraFilter> {
        val assetRecipes = GradeCatalog.load(context)
        var list = INSTAGRAM_FILTERS.map { f -> f.copy(recipe = assetRecipes[f.id]) }
        GradePackLoader.loadActivePack(context)?.let { pack ->
            list = GradePackLoader.applyPack(list, pack)
        }
        return list
    }

    var activeFilter: CameraFilter = INSTAGRAM_FILTERS[0]
        private set

    private val gradeCache = HashMap<String, Pair<GradeRecipe, GradeImages>>()

    /**
     * Per-filter grade strength — Instagram's customization-param semantics:
     * every effect keeps its own intensity inside [GradeRecipe.strengthMin]
     * ..[strengthMax], defaulting to [GradeRecipe.strengthDefault]. Scales
     * the curve mix AND the overlay opacities together (one "effect amount").
     */
    private val gradeStrengths = HashMap<String, Float>()

    fun gradeStrength(filter: CameraFilter): Float {
        val recipe = filter.recipe ?: return 1f
        return (gradeStrengths[filter.id] ?: recipe.strengthDefault)
            .coerceIn(recipe.strengthMin, recipe.strengthMax)
    }

    fun setGradeStrength(filter: CameraFilter, value: Float) {
        val recipe = filter.recipe ?: return
        gradeStrengths[filter.id] = value.coerceIn(recipe.strengthMin, recipe.strengthMax)
    }

    /** Decode-once per filter: curve strip + overlay masks for the CPU bake. */
    fun gradeImages(filter: CameraFilter): GradeImages? {
        val recipe = filter.recipe ?: return null
        gradeCache[filter.id]?.let { cached -> return cached.second }
        val curve = recipe.curve?.let { GradeAssets.decode(context, recipe, it) }
        val overlays = recipe.overlays.mapNotNull { ov ->
            GradeAssets.decode(context, recipe, ov.texture)?.let { bmp -> bmp to ov }
        }
        val imgs = GradeImages(curve, overlays)
        gradeCache[filter.id] = recipe to imgs
        return imgs
    }

    /**
     * Fetches the runtime filter pack (blank `GRADE_PACK_URL` = feature off).
     * Installs a changed pack, rebuilds [filters] and clears the grade
     * bitmap/strength caches (pack overrides replace recipes by id).
     * Returns the new filter count, 0 when the server answered
     * not-modified, -1 when the URL is unset or the fetch failed.
     */
    suspend fun refreshGrades(): Int {
        val url = BuildConfig.GRADE_PACK_URL
        if (url.isBlank()) return -1
        val newVersion = GradePackLoader.fetch(
            context, url, GradePackLoader.installedVersion(context)
        )
        if (newVersion <= 0) return newVersion
        return withContext(Dispatchers.IO) {
            filters = buildFilters()
            gradeCache.clear()
            gradeStrengths.clear()
            // Keep the user's selection by id; reset only if it vanished.
            activeFilter = filters.firstOrNull { it.id == activeFilter.id } ?: filters.first()
            filters.size
        }
    }

    private fun decideMode(): Mode {
        // Live GPU filtering is off by default: the PreviewView route is the
        // most robust across devices, and the live screen still gets the
        // tint-look approximation, the beauty soft-glow and the tracked
        // makeup overlay — accepted stills/posters always bake the full look
        // on the CPU. The GLES pipeline stays in the codebase (maintenance-
        // only) behind this switch.
        val gpuCapable = run {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memMb = am?.memoryClass ?: 128
            val glEs = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0x20000
            val cores = Runtime.getRuntime().availableProcessors()
            // GPU needs GLES3 + decent RAM + multi-core; otherwise lite.
            val ok = glEs >= 0x30000 && memMb >= 192 && cores >= 6 && !(am != null && am.isLowRamDevice)
            Log.i(
                TAG, "engine mode=" + (if (LIVE_GPU_FILTERING && ok) "GPU" else "LIGHTWEIGHT") +
                    " (switch=$LIVE_GPU_FILTERING capable=$ok glEs=$glEs mem=${memMb}MB cores=$cores)"
            )
            ok
        }
        return if (LIVE_GPU_FILTERING && gpuCapable) Mode.GPU else Mode.LIGHTWEIGHT
    }

    companion object {
        /** Product switch: enable the GLES live-filter pipeline. */
        const val LIVE_GPU_FILTERING: Boolean = false
    }

    /**
     * Live path: tint overlay over the camera preview (the lite approximation
     * of the grade; GPU path does the real thing in-shader). [strength] is the
     * grade strength — it scales the approximation exactly like the bake does.
     */
    fun applyLive(filter: CameraFilter, tintView: View, strength: Float = gradeStrength(filter)) {
        activeFilter = filter
        if (filter.tintAlpha <= 0f) {
            tintView.visibility = View.GONE
            tintView.background = null
            return
        }
        tintView.visibility = View.VISIBLE
        tintView.setBackgroundColor(filter.tintColor)
        tintView.alpha = (filter.tintAlpha * strength).coerceIn(0f, 1f)
    }

    /**
     * Still-capture path: full bake for posters.
     *
     * [face] and [layers] are the last tracked face and the active makeup;
     * the bake skips either when absent (never a guessed placement). [bakeTint]
     * folds the lite-path Instagram tint overlay into the still so the poster
     * matches what the user saw; the GPU path already shows the grade in the
     * live shader and leaves the tint out of stills for the same reason.
     */
    fun processBitmap(
        src: Bitmap,
        filter: CameraFilter = activeFilter,
        beauty: BeautyParams = BeautyParams(),
        face: FaceGeometry? = null,
        layers: List<MatteLayer> = emptyList(),
        canonical: CanonicalFace = CanonicalFace.EMPTY,
        bakeTint: Boolean = false
    ): Bitmap {
        val s = beauty.smooth.coerceIn(0f, 1f)
        val w = beauty.whiten.coerceIn(0f, 1f)
        val slim = beauty.slimFace.coerceIn(0f, 1f)
        val eyes = beauty.bigEyes.coerceIn(0f, 1f)
        val knobsOn = beauty.sharpen.coerceIn(0f, 1f) > 0.003f ||
            beauty.contrast.coerceIn(0f, 1f) > 0.003f ||
            beauty.saturation.coerceIn(0f, 1f) > 0.003f ||
            beauty.ruddy.coerceIn(0f, 1f) > 0.003f ||
            beauty.eyeTeeth.coerceIn(0f, 1f) > 0.003f
        val reshape = face != null && (slim > 0.003f || eyes > 0.003f)
        val makeup = face != null && layers.isNotEmpty()
        val strength = gradeStrength(filter)
        // Lite captures bake the tint look; scale it by the grade strength so
        // the poster matches the (strength-scaled) lite preview.
        val tintAlpha = if (bakeTint) filter.tintAlpha * strength else 0f
        val tintColor = if (bakeTint) filter.tintColor else 0

        if (filter.id == "normal" && s <= 0.003f && w <= 0.003f && !knobsOn &&
            !reshape && !makeup && tintAlpha <= 0.003f && filter.recipe == null
        ) {
            return src
        }
        // Off main thread by contract (EGL blocks ~30-60ms at 1080p).
        return GpuFilterProcessor.apply(
            src, filter,
            beauty,
            face, layers, canonical,
            tintColor, tintAlpha,
            gradeImages(filter),
            strength
        )
    }

    /**
     * Beauty soft-focus for the lite live path: a low-alpha warm overlay over
     * the preview simulates whiten + smoothing glow. (GPU path uses real
     * in-shader bilateral smoothing instead; see GpuPreviewView.)
     */
    fun applyBeautyLive(smooth: Float, whiten: Float, softView: View) {
        val alpha = (smooth * 0.10f + whiten * 0.16f).coerceIn(0f, 0.26f)
        if (alpha <= 0.005f) {
            softView.visibility = View.GONE
            return
        }
        softView.visibility = View.VISIBLE
        softView.setBackgroundColor(0xFFFFF4EC.toInt())
        softView.alpha = alpha
    }

    /** Sticker overlay: emoji/text sticker pinned above preview, no tracking yet. */
    fun applySticker(sticker: FaceSticker?, stickerView: TextView) {
        if (sticker == null) {
            stickerView.visibility = View.GONE
            return
        }
        stickerView.visibility = View.VISIBLE
        stickerView.text = sticker.emoji
        stickerView.textSize = 64f
    }
}
