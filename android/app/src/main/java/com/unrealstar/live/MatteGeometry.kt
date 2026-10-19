package com.unrealstar.live

import kotlin.math.hypot

/**
 * Face-to-mask transform math for the makeup engine.
 *
 * The shipped masks live on a 750x1334 canvas with fixed landmark geometry
 * ([CanonicalFace]). At runtime we fit a similarity (uniform scale + rotation
 * + translation, no shear) from canvas pixels to view pixels using the
 * detected eyes and mouth center, then map view coordinates into mask
 * texture UVs.
 *
 * Coordinate spaces (all y-down image space, matching the camera pipeline):
 *  - canvas px: the 750x1334 makeup reference frame
 *  - view px: pixels of the displayed preview (or of the still frame)
 *  - view fractions: 0..1 of that view
 *  - mask UV: 0..1 of the cropped mask texture
 *
 * The fit is a weighted least-squares Procrustes solve: two points determine
 * the transform exactly; the third (mouth) stabilizes it against landmark
 * jitter on faces whose proportions differ from the canonical model.
 */
object MatteGeometry {

    /** q = s * R(theta) as a complex number; (cx, cy) -> q*c + t maps canvas px to view px. */
    class Similarity(
        val qx: Float,
        val qy: Float,
        val tx: Float,
        val ty: Float
    ) {
        fun map(cx: Float, cy: Float): Pair<Float, Float> =
            Pair(qx * cx - qy * cy + tx, qy * cx + qx * cy + ty)
    }

    /**
     * Fits canvas -> view similarity from weighted point pairs, each
     * (canvasX, canvasY, viewX, viewY, weight). Returns null when degenerate —
     * callers must then render no makeup rather than a guessed placement.
     */
    fun solve(pairs: List<FloatArray>): Similarity? {
        if (pairs.isEmpty()) return null
        var sw = 0f
        var ccx = 0f; var ccy = 0f
        var cvx = 0f; var cvy = 0f
        for (p in pairs) {
            sw += p[4]
            ccx += p[4] * p[0]; ccy += p[4] * p[1]
            cvx += p[4] * p[2]; cvy += p[4] * p[3]
        }
        if (sw <= 0f) return null
        ccx /= sw; ccy /= sw; cvx /= sw; cvy /= sw

        var numX = 0f; var numY = 0f; var den = 0f
        for (p in pairs) {
            val cx = p[0] - ccx; val cy = p[1] - ccy
            val vx = p[2] - cvx; val vy = p[3] - cvy
            // num += w * v * conj(c)
            numX += p[4] * (vx * cx + vy * cy)
            numY += p[4] * (vy * cx - vx * cy)
            den += p[4] * (cx * cx + cy * cy)
        }
        if (den < 1e-6f) return null
        val qx = numX / den
        val qy = numY / den
        if (hypot(qx, qy) < 1e-6f) return null

        val tx = cvx - (qx * ccx - qy * ccy)
        val ty = cvy - (qy * ccx + qx * ccy)
        return Similarity(qx, qy, tx, ty)
    }

    /**
     * Center-crop mapping: raw upright-frame fractions -> view fractions.
     * Mirrors PreviewView FILL_CENTER and GpuPreviewView.computeTexMat, so
     * face landmarks land exactly where the preview shows them on any screen
     * aspect (a 9:16 frame on a 20:9 screen is cropped, not stretched).
     */
    fun cropToView(
        fx: Float, fy: Float,
        frameW: Float, frameH: Float,
        viewW: Float, viewH: Float
    ): Pair<Float, Float> {
        if (frameW <= 0f || frameH <= 0f || viewW <= 0f || viewH <= 0f) return Pair(fx, fy)
        val frameAspect = frameW / frameH
        val viewAspect = viewW / viewH
        return if (viewAspect >= frameAspect) {
            // View is relatively wider: full frame width shows, top/bottom crop.
            val visibleH = frameAspect / viewAspect
            Pair(fx, (fy - (1f - visibleH) / 2f) / visibleH)
        } else {
            val visibleW = viewAspect / frameAspect
            Pair((fx - (1f - visibleW) / 2f) / visibleW, fy)
        }
    }

    /**
     * Maps [face]'s upright-frame fractions into view fractions of a
     * center-cropped view (the live preview): center-crop + size scaling.
     * Used by the sticker overlay and the lite makeup overlay (top-down View
     * space) and — after a y-flip at the GL boundary — by the GL shader.
     *
     * Do not feed the result back into [similarityFor] with crop = true; the
     * fractions are already in view space.
     */
    fun faceInView(face: FaceGeometry, viewW: Float, viewH: Float): FaceGeometry {
        val fw = face.frameW.toFloat()
        val fh = face.frameH.toFloat()
        if (fw <= 0f || fh <= 0f || viewW <= 0f || viewH <= 0f) return face
        val frameAspect = fw / fh
        val viewAspect = viewW / viewH
        val wScale = if (viewAspect < frameAspect) frameAspect / viewAspect else 1f
        val hScale = if (viewAspect > frameAspect) viewAspect / frameAspect else 1f
        fun p(pt: Pair<Float, Float>?): Pair<Float, Float>? =
            pt?.let { cropToView(it.first, it.second, fw, fh, viewW, viewH) }
        val c = cropToView(face.centerX, face.centerY, fw, fh, viewW, viewH)
        return face.copy(
            centerX = c.first,
            centerY = c.second,
            widthFrac = (face.widthFrac * wScale).coerceIn(0.02f, 1.5f),
            heightFrac = (face.heightFrac * hScale).coerceIn(0.02f, 2f),
            eyeL = p(face.eyeL),
            eyeR = p(face.eyeR),
            mouthL = p(face.mouthL),
            mouthR = p(face.mouthR)
        )
    }

    /**
     * Fits canvas -> view px for a detected face.
     *
     * [crop] is true for the live preview (which center-crops the frame into
     * the view) and false for stills (which show the full frame). Without both
     * eyes the solve degrades to a landmark-free box estimate (no roll) —
     * approximate, but the makeup still tracks position and size instead of
     * disappearing on a dropped landmark frame.
     */
    fun similarityFor(
        face: FaceGeometry,
        canonical: CanonicalFace,
        viewW: Float,
        viewH: Float,
        crop: Boolean
    ): Similarity? {
        if (viewW <= 0f || viewH <= 0f) return null

        fun viewPx(p: Pair<Float, Float>): Pair<Float, Float> {
            val (fx, fy) = if (crop) {
                cropToView(p.first, p.second, face.frameW.toFloat(), face.frameH.toFloat(), viewW, viewH)
            } else {
                p
            }
            return Pair(fx * viewW, fy * viewH)
        }

        val eyeL = face.eyeL
        val eyeR = face.eyeR
        if (eyeL != null && eyeR != null) {
            // Canvas eyeL is the screen-left blob; pair it with the screen-left eye.
            val left = if (eyeL.first <= eyeR.first) eyeL else eyeR
            val right = if (eyeL.first <= eyeR.first) eyeR else eyeL
            val vl = viewPx(left)
            val vr = viewPx(right)
            val pairs = ArrayList<FloatArray>(3)
            pairs += floatArrayOf(canonical.eyeLx, canonical.eyeLy, vl.first, vl.second, 1f)
            pairs += floatArrayOf(canonical.eyeRx, canonical.eyeRy, vr.first, vr.second, 1f)
            face.mouthCenter?.let { m ->
                val vm = viewPx(m)
                pairs += floatArrayOf(canonical.mouthX, canonical.mouthY, vm.first, vm.second, 0.5f)
            }
            return solve(pairs)
        }

        // Box-only fallback: face box proportions -> synthetic landmarks.
        val cx = face.centerX
        val cy = face.centerY
        val w = face.widthFrac.coerceIn(0.05f, 1f)
        val h = face.heightFrac.coerceIn(w, 1.6f)
        val eyeY = cy - 0.04f * h
        val halfIod = 0.20f * w
        val vEyeL = viewPx(Pair(cx - halfIod, eyeY))
        val vEyeR = viewPx(Pair(cx + halfIod, eyeY))
        val vMouth = viewPx(Pair(cx, eyeY + 0.40f * w))
        return solve(
            listOf(
                floatArrayOf(canonical.eyeLx, canonical.eyeLy, vEyeL.first, vEyeL.second, 1f),
                floatArrayOf(canonical.eyeRx, canonical.eyeRy, vEyeR.first, vEyeR.second, 1f),
                floatArrayOf(canonical.mouthX, canonical.mouthY, vMouth.first, vMouth.second, 0.25f)
            )
        )
    }

    /**
     * view fractions -> mask UV for one layer, as a 2x2 + translation:
     * maskU = a00*x + a01*y + bx; maskV = a10*x + a11*y + by.
     * Returns [a00, a01, a10, a11, bx, by] for shader uniforms.
     */
    fun viewFractionToMaskUv(
        sim: Similarity,
        anchorX: Float,
        anchorY: Float,
        imageScale: Float,
        maskW: Int,
        maskH: Int,
        viewW: Float,
        viewH: Float
    ): FloatArray {
        val mag2 = sim.qx * sim.qx + sim.qy * sim.qy
        // 1/q = conj(q) / |q|^2
        val wx = sim.qx / mag2
        val wy = -sim.qy / mag2
        val sx = maskW * imageScale
        val sy = maskH * imageScale
        // canvas = 1/q * (view - t); maskU = (canvas.x - anchor) / (maskSize*scale)
        val a00 = wx * viewW / sx
        val a01 = -wy * viewH / sx
        val bx = (-wx * sim.tx + wy * sim.ty - anchorX) / sx
        val a10 = wy * viewW / sy
        val a11 = wx * viewH / sy
        val by = (-wy * sim.tx - wx * sim.ty - anchorY) / sy
        return floatArrayOf(a00, a01, a10, a11, bx, by)
    }

    /**
     * Mask texture px -> view px as an Android matrix layout
     * [a, b, c, d, e, f, 0, 0, 1] (view = M * tex). One texture px spans
     * [imageScale] canvas px, so the linear part is q scaled by [imageScale]
     * (not by the mask size — the Canvas path draws the mask in px space).
     * Used by the Canvas compositing path (lite live overlay): concat, then
     * draw the mask bitmap at 0,0.
     */
    fun maskToViewMatrix(
        sim: Similarity,
        anchorX: Float,
        anchorY: Float,
        imageScale: Float
    ): FloatArray {
        return floatArrayOf(
            sim.qx * imageScale, -sim.qy * imageScale, sim.tx + sim.qx * anchorX - sim.qy * anchorY,
            sim.qy * imageScale, sim.qx * imageScale, sim.ty + sim.qy * anchorX + sim.qx * anchorY,
            0f, 0f, 1f
        )
    }
}
