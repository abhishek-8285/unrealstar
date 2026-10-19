package com.unrealstar.live

import android.content.Context

/**
 * Beauty state: catalog + the user's active makeup selection + the
 * Instagram-style beauty params.
 *
 * Rendering lives elsewhere: the GPU live path renders layers inside
 * GpuPreviewView's shader, the lite live path draws a face-anchored overlay
 * via [MatteCompositor], and stills bake through [FilterEngine.processBitmap].
 */
class BeautyFilterManager {

    var catalog: MatteCatalog? = null

    /** Single active item today; [layers] already returns a list so stacking
     *  multiple categories later is a UI change, not an engine change. */
    var selection: MatteSelection? = null
        private set

    // ---- Instagram-style beauty params (0.0 to 1.0) ----
    // GPU path: real in-shader smoothing/whiten/reshape. Lite path: smooth
    // and whiten run as a soft-glow approximation; the reshape sliders are
    // hidden there (the row is gated on GPU mode) so stills never bake a
    // reshape the live preview never showed.
    var smooth: Float = 0f
    var whiten: Float = 0f
    var slimFace: Float = 0f
    var bigEyes: Float = 0f

    // Retouch + look knobs (Banuba-informed REA port; see [BeautyShader]).
    // GPU live + still bakes only — the lite live preview does not simulate
    // these, so the slider rows are gated on GPU mode like the reshape pair.
    var sharpen: Float = 0f
    var contrast: Float = 0f
    var saturation: Float = 0f
    var ruddy: Float = 0f
    var eyeTeeth: Float = 0f

    val categories: List<MatteCategory>
        get() = catalog?.categories ?: emptyList()

    fun select(category: MatteCategory, item: MatteItem) {
        selection = MatteSelection(category, item, item.strength.coerceIn(0f, 1f))
    }

    fun deselect() {
        selection = null
    }

    /** Applies to the active selection; no-op when nothing is selected. */
    fun setIntensity(value: Float) {
        selection = selection?.copy(intensity = value.coerceIn(0f, 1f))
    }

    val intensity: Float
        get() = selection?.intensity ?: 0f

    /** Resolved layers for the render paths; empty when nothing is resolvable. */
    fun layers(context: Context): List<MatteLayer> =
        listOfNotNull(MatteAssets.resolve(context, selection))
}
