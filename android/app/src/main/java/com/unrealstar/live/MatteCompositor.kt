package com.unrealstar.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.graphics.withMatrix
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Canvas-side makeup compositing.
 *
 * Two consumers:
 *  - [renderOverlayInto] drives the lite live path: a transparent fullscreen
 *    bitmap composited over PreviewView each face update. It can only draw
 *    source-over (an overlay View cannot multiply against the camera), so it
 *    approximates the GPU look — documented, not silent.
 *  - [applyLayerCpu] bakes the exact shader math per pixel for captured
 *    stills, where a one-shot pass over the mask bounding box is cheap.
 *
 * The 64x64 LUT is the pack's 8x8 grid of 8x8 texels; lookup mirrors the
 * GPUImage-style shader in GpuPreviewView/GpuFilterProcessor.
 */
object MatteCompositor {

    private const val ALPHA_EPS = 2 // 0..255 mask alpha below this is skipped

    /**
     * Redraws [out] as the overlay for the active [layers] at the fitted
     * [sim]. Reuses the caller's bitmap (erase + draw) so the 400 ms face
     * cadence does not churn fullscreen allocations.
     */
    fun renderOverlayInto(
        out: Bitmap,
        layers: List<MatteLayer>,
        sim: MatteGeometry.Similarity
    ) {
        out.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(out)
        for (layer in layers) {
            val cat = layer.category
            val m = Matrix()
            m.setValues(
                MatteGeometry.maskToViewMatrix(sim, cat.anchorX, cat.anchorY, cat.imageScale)
            )
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (layer.intensity.coerceIn(0f, 1f) * 255).toInt()
                if (cat.lutMode) {
                    // Approximate the LUT recolor with the item's swatch color,
                    // keeping the mask alpha: SRC_ATOP replaces RGB only.
                    colorFilter = PorterDuffColorFilter(layer.item.color, PorterDuff.Mode.SRC_ATOP)
                } else {
                    // Nudge the shipped texture toward the item's budget of
                    // the palette color; MULTIPLY keeps its shading.
                    colorFilter = PorterDuffColorFilter(layer.item.color, PorterDuff.Mode.MULTIPLY)
                }
            }
            canvas.withMatrix(m) {
                drawBitmap(layer.mask, 0f, 0f, paint)
            }
        }
    }

    /**
     * Exact per-pixel composite of one layer onto [out], matching the GPU
     * shader:  LUT   -> mix(rgb, lut(rgb) * tint, alpha * strength)
     *          MULT  -> mix(rgb, rgb * maskRgb * tint, alpha * strength)
     * [sim] maps the still's px space (full frame, no crop).
     */
    fun applyLayerCpu(out: Bitmap, layer: MatteLayer, sim: MatteGeometry.Similarity) {
        val cat = layer.category
        val mask = layer.mask
        val mw = mask.width
        val mh = mask.height
        if (mw <= 0 || mh <= 0) return
        val sx = mw * cat.imageScale
        val sy = mh * cat.imageScale

        // Bounding box of the transformed mask rect, clamped to the frame.
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (cornerX in intArrayOf(0, mw)) {
            for (cornerY in intArrayOf(0, mh)) {
                val (vx, vy) = sim.map(cat.anchorX + cornerX * cat.imageScale, cat.anchorY + cornerY * cat.imageScale)
                if (vx < minX) minX = vx
                if (vx > maxX) maxX = vx
                if (vy < minY) minY = vy
                if (vy > maxY) maxY = vy
            }
        }
        val x0 = floor(minX).toInt().coerceAtLeast(0)
        val y0 = floor(minY).toInt().coerceAtLeast(0)
        val x1 = ceil(maxX).toInt().coerceAtMost(out.width)
        val y1 = ceil(maxY).toInt().coerceAtMost(out.height)
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw <= 0 || bh <= 0) return

        val maskPx = IntArray(mw * mh)
        mask.getPixels(maskPx, 0, mw, 0, 0, mw, mh)
        val lut = layer.lut
        val lutPx = if (cat.lutMode && lut != null) IntArray(lut.width * lut.height).also {
            lut.getPixels(it, 0, lut.width, 0, 0, lut.width, lut.height)
        } else null

        val px = IntArray(bw * bh)
        out.getPixels(px, 0, bw, x0, y0, bw, bh)

        val strength = layer.intensity.coerceIn(0f, 1f)
        val tintR = layer.item.tint[0]
        val tintG = layer.item.tint[1]
        val tintB = layer.item.tint[2]

        for (yy in 0 until bh) {
            val vy = (y0 + yy).toFloat()
            for (xx in 0 until bw) {
                val (cvx, cvy) = sim.map((x0 + xx).toFloat(), vy)
                val u = (cvx - cat.anchorX) / sx
                val v = (cvy - cat.anchorY) / sy
                if (u < 0f || u > 1f || v < 0f || v > 1f) continue
                val mi = (v * mh).toInt().coerceIn(0, mh - 1) * mw +
                    (u * mw).toInt().coerceIn(0, mw - 1)
                val mr = (maskPx[mi] ushr 16) and 0xFF
                val mg = (maskPx[mi] ushr 8) and 0xFF
                val mb = maskPx[mi] and 0xFF
                val ma = (maskPx[mi] ushr 24) and 0xFF
                if (ma <= ALPHA_EPS) continue
                val cov = ma / 255f * strength

                val idx = yy * bw + xx
                val p = px[idx]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF

                val nr: Float; val ng: Float; val nb: Float
                if (lutPx != null) {
                    val (lr, lg, lb) = lutLookup(lutPx, lut!!.width, lut.height, r, g, b)
                    nr = lr * tintR; ng = lg * tintG; nb = lb * tintB
                } else {
                    nr = r * (mr / 255f * tintR)
                    ng = g * (mg / 255f * tintG)
                    nb = b * (mb / 255f * tintB)
                }
                val or = (r + (nr - r) * cov).toInt().coerceIn(0, 255)
                val og = (g + (ng - g) * cov).toInt().coerceIn(0, 255)
                val ob = (b + (nb - b) * cov).toInt().coerceIn(0, 255)
                px[idx] = (0xFF shl 24) or (or shl 16) or (og shl 8) or ob
            }
        }
        out.setPixels(px, 0, bw, x0, y0, bw, bh)
    }

    /**
     * 64x64 LUT (8x8 grid of 8x8 tiles, red/green in-tile, blue selects the
     * tile pair) sampled like the reference GPU shader.
     */
    fun lutLookup(pixels: IntArray, w: Int, h: Int, r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val dim = listOf(w, h).min()
        val tiles = 8
        val tile = dim / tiles
        if (tile <= 0) return Triple(r, g, b)

        val blue = (b / 255f) * (tile * tiles - 1) // 0..63
        val q1y = floor(blue / tile)
        val q1x = blue - q1y * tile
        val q2y = floor(ceil(blue) / tile)
        val q2x = ceil(blue) - q2y * tile
        val mix = blue - floor(blue)

        fun sample(qx: Float, qy: Float): Triple<Int, Int, Int> {
            val tx = ((qx * tile + 0.5f + (r / 255f) * (tile - 1))).toInt()
                .coerceIn(0, w - 1)
            val ty = ((qy * tile + 0.5f + (g / 255f) * (tile - 1))).toInt()
                .coerceIn(0, h - 1)
            val c = pixels[ty * w + tx]
            return Triple((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF)
        }

        val (r1, g1, b1) = sample(q1x, q1y)
        val (r2, g2, b2) = sample(q2x, q2y)
        fun lerp(a: Int, b2v: Int) = (a + (b2v - a) * mix).toInt().coerceIn(0, 255)
        return Triple(lerp(r1, r2), lerp(g1, g2), lerp(b1, b2))
    }
}
