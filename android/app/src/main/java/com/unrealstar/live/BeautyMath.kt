package com.unrealstar.live

/**
 * Exact CPU twins of [BeautyShader]'s formulas, used by the CPU bake path
 * (`GpuFilterProcessor.cpuBake`) and unit-tested by hand-computed
 * expectations. Inputs/outputs are 8-bit ARGB ints (camera frames are 8-bit,
 * so shader `floor(c*255)`-style indexing is exact integer math here).
 *
 * Same parity caveat as [GradeMath]: the shader runs at mediump, so boundary
 * cases of `step()` may differ by one 8-bit unit; everything else matches.
 */
object BeautyMath {

    /** Twin of `beautySkin`: soft YCbCr skin-tone window, 0..1. */
    fun skinWeight(r: Float, g: Float, b: Float): Float {
        val cb = -0.169f * r - 0.331f * g + 0.5f * b + 0.5f
        val cr = 0.5f * r - 0.419f * g - 0.081f * b + 0.5f
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        var w = smoothstep(0.30f, 0.36f, cb) * (1f - smoothstep(0.47f, 0.53f, cb))
        w *= smoothstep(0.50f, 0.55f, cr) * (1f - smoothstep(0.68f, 0.73f, cr))
        w *= smoothstep(0.10f, 0.22f, l)
        return w
    }

    /** Twin of the shader curve: Banuba's skin-tone beautify polynomial. */
    fun curveChannel(x: Float): Float =
        (-0.391731f * x * x + 1.4554f * x - 0.0637f).coerceIn(0f, 1f)

    /**
     * Twin of `beautyComposite` — adaptive smoothing of one pixel against its
     * blurred twin at slider [s]. The un-clamped `case1` powers mirror the
     * shader exactly (bounded 0..1 by construction for either `step` branch).
     */
    fun composite(orig: Int, blurred: Int, s: Float): Int {
        val or = ((orig shr 16) and 0xFF) / 255f
        val og = ((orig shr 8) and 0xFF) / 255f
        val ob = (orig and 0xFF) / 255f
        val br = ((blurred shr 16) and 0xFF) / 255f
        val bg = ((blurred shr 8) and 0xFF) / 255f
        val bb = (blurred and 0xFF) / 255f

        val skin = skinWeight(or, og, ob)

        var val2 = (sqrtF(og * og + ob * ob) - sqrtF(bg * bg + bb * bb) + 0.5f).coerceIn(0f, 1f)
        val caseX = pow8(val2) * 128f
        val caseY = pow8(1f - val2) * 128f
        val2 = if (0.5f >= val2) caseX else 1f - caseY

        val diffR = br - or
        val diffG = bg - og
        val diffB = bb - ob
        val thresh = 0.01f * s * s
        val mixAmount = if (diffR * diffR + diffG * diffG + diffB * diffB >= thresh) 1f else 0f

        fun channel(oc: Float, bc: Float, diff: Float): Int {
            val origCurve = mixf(curveChannel(oc), oc, val2)
            var comp = origCurve + mixAmount * 0.05f * s * diff
            comp = mixf(comp, bc, skin * 0.7f * s)
            return to8(mixf(oc, comp, skin * 0.4f * s))
        }

        val out = (orig and 0xFF000000.toInt()) or
            (channel(or, br, diffR) shl 16) or
            (channel(og, bg, diffG) shl 8) or
            channel(ob, bb, diffB)
        return out
    }

    /** Twin of `beautyWhiten` (eyes/teeth): desaturate 60% + lift 28%. */
    fun whiten(argb: Int, amount: Float): Int {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        val fr = mixf(r, l, 0.6f); val fg = mixf(g, l, 0.6f); val fb = mixf(b, l, 0.6f)
        val lr = fr + (1f - fr) * 0.28f
        val lg = fg + (1f - fg) * 0.28f
        val lb = fb + (1f - fb) * 0.28f
        return (argb and 0xFF000000.toInt()) or
            (to8(mixf(r, lr, amount)) shl 16) or
            (to8(mixf(g, lg, amount)) shl 8) or
            to8(mixf(b, lb, amount))
    }

    /**
     * Twin of the `retouchCall` blend: whiten blended by [coverage] scaled by
     * the low-saturation damping (near-white sclera/teeth respond, saturated
     * lips/iris are protected).
     */
    fun retouch(argb: Int, amount: Float, coverage: Float): Int {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val sat = maxOf(r, g, b) - minOf(r, g, b)
        val damp = 1f - (sat * 3f).coerceIn(0f, 1f)
        val c = (coverage * damp).coerceIn(0f, 1f)
        val w = whiten(argb, amount)
        return (argb and 0xFF000000.toInt()) or
            (mixc((argb shr 16) and 0xFF, (w shr 16) and 0xFF, c) shl 16) or
            (mixc((argb shr 8) and 0xFF, (w shr 8) and 0xFF, c) shl 8) or
            mixc(argb and 0xFF, w and 0xFF, c)
    }

    /** Twin of `beautySharpen` — caller passes the 4 diagonal neighbours. */
    fun sharpen(argb: Int, neighbors: IntArray, amount: Float): Int {
        val a = argb and 0xFF000000.toInt()
        var sr = 0; var sg = 0; var sb = 0
        for (n in neighbors) {
            sr += (n shr 16) and 0xFF; sg += (n shr 8) and 0xFF; sb += n and 0xFF
        }
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val shr = (5 * r - sr).coerceIn(0, 255)
        val shg = (5 * g - sg).coerceIn(0, 255)
        val shb = (5 * b - sb).coerceIn(0, 255)
        return a or (mixc(r, shr, amount) shl 16) or (mixc(g, shg, amount) shl 8) or mixc(b, shb, amount)
    }

    /** Twin of `beautyKnobs`: saturation/contrast boosts + skin-gated ruddy. */
    fun knobs(argb: Int, saturation: Float, contrast: Float, ruddy: Float): Int {
        var r = ((argb shr 16) and 0xFF) / 255f
        var g = ((argb shr 8) and 0xFF) / 255f
        var b = (argb and 0xFF) / 255f
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        // GLSL mix() extrapolates for t > 1 (saturation boost); match that.
        val satK = 1f + 0.6f * saturation
        r = l + (r - l) * satK
        g = l + (g - l) * satK
        b = l + (b - l) * satK
        val conK = 1f + 0.5f * contrast
        r = (r - 0.5f) * conK + 0.5f; g = (g - 0.5f) * conK + 0.5f; b = (b - 0.5f) * conK + 0.5f
        val skin = ruddy * skinWeight(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        r += skin * 0.09f; g += skin * 0.015f; b += skin * -0.045f
        return (argb and 0xFF000000.toInt()) or
            (to8(r.coerceIn(0f, 1f)) shl 16) or (to8(g.coerceIn(0f, 1f)) shl 8) or to8(b.coerceIn(0f, 1f))
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pow8(x: Float): Float {
        var v = x * x
        v *= v
        return v * v
    }

    private fun sqrtF(x: Float): Float = kotlin.math.sqrt(x)

    private fun mixf(c0: Float, c1: Float, t: Float): Float =
        if (t <= 0f) c0 else if (t >= 1f) c1 else c0 + (c1 - c0) * t

    /** Same rounding convention as [GradeMath]: +0.5 then truncate. */
    private fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun mixc(c0: Int, c1: Int, t: Float): Int =
        if (t <= 0f) c0 else if (t >= 1f) c1 else (c0 + (c1 - c0) * t + 0.5f).toInt().coerceIn(0, 255)
}
