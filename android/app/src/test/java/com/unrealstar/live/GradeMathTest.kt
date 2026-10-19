package com.unrealstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-math tests for the REA-informed grade stage (see
 * docs/FILTER_PIPELINE_SPECIFICATION.md). Every expectation here is computed
 * by hand from the documented per-channel formulas — the same formulas the
 * GLSL in GradeShader encodes — so a regression in either breaks these.
 */
class GradeMathTest {

    private fun strip(): IntArray {
        // A synthetic 256x3 strip: R-in -> R-out ramps 0..255 inverted,
        // G v-shaped, B step gamma-ish; each stored in its own channel row.
        val px = IntArray(256 * 3) { 0 }
        for (i in 0 until 256) {
            val r = 255 - i
            val g = if (i < 128) (i * 2) else 254 - (i - 128) * 2
            val b = (i * 0.8f).toInt()
            px[0 * 256 + i] = (0xFF shl 24) or (r shl 16)
            px[1 * 256 + i] = (0xFF shl 24) or (g shl 8)
            px[2 * 256 + i] = (0xFF shl 24) or b
        }
        return px
    }

    @Test
    fun `strip decodes three independent channel LUTs`() {
        val luts = GradeMath.lutsFromStrip(strip(), 256, 3)
        assertEquals(255, luts[0]!![0])
        assertEquals(0, luts[0]!![255])
        assertEquals(0, luts[1]!![0])
        assertEquals(254, luts[1]!![128])
        assertEquals(0, luts[2]!![0])
        assertEquals(204, luts[2]!![255])
    }

    @Test
    fun `curve at strength one fully replaces channel values`() {
        val luts = GradeMath.lutsFromStrip(strip(), 256, 3)
        // argb in: r=10,g=20,b=30
        val argb = (0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30
        val out = GradeMath.applyCurve(luts, argb, 1f)
        assertEquals(255 - 10, (out shr 16) and 0xFF)
        assertEquals(40, (out shr 8) and 0xFF)      // g: i=20 <128 -> 2*20
        assertEquals(24, out and 0xFF)              // b: (30*0.8).toInt()
        assertEquals(0xFF, (out ushr 24) and 0xFF)  // alpha untouched
    }

    @Test
    fun `curve at zero strength is a no-op`() {
        val luts = GradeMath.lutsFromStrip(strip(), 256, 3)
        val argb = (0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30
        assertEquals(argb, GradeMath.applyCurve(luts, argb, 0f))
    }

    @Test
    fun `curve half strength blends toward LUT`() {
        val luts = GradeMath.lutsFromStrip(strip(), 256, 3)
        val argb = (0xFF shl 24) or (100 shl 16) or (0 shl 8) or (0 shl 8)
        val out = GradeMath.applyCurve(luts, argb, 0.5f)
        // r: mix(100, 155, .5) = 127.5; the +0.5 rounding convention puts
        // ties at the upper bound -> 128.
        assertEquals(128, (out shr 16) and 0xFF)
    }

    @Test
    fun `multiply blends like base times src`() {
        // base=200, src=128 -> 200*128/255 = 100.39 -> 100
        assertEquals(100, GradeMath.blendChannel(GradeBlend.MULTIPLY, 200, 128))
        // Black source annihilates every base.
        assertEquals(0, GradeMath.blendChannel(GradeBlend.MULTIPLY, 200, 0))
        // White source is neutral over multiply.
        assertEquals(200, GradeMath.blendChannel(GradeBlend.MULTIPLY, 200, 255))
    }

    @Test
    fun `screen is 1-(1-b)(1-s)`() {
        // base=200, src=128 -> 255 - (55*127)/255 = 227.61 -> 228
        assertEquals(228, GradeMath.blendChannel(GradeBlend.SCREEN, 200, 128))
        // Screen is neutral to white, full-black is lifted to the source.
        assertEquals(255, GradeMath.blendChannel(GradeBlend.SCREEN, 200, 255))
        assertEquals(128, GradeMath.blendChannel(GradeBlend.SCREEN, 0, 128))
    }

    @Test
    fun `overlay switches on base luminance`() {
        // Dark base path (b<0.5): 2*b*s. base=60, src=128 -> 2*(60/255)*(0.50196)*255
        val dark = (2.0 * (60.0 / 255.0) * (128.0 / 255.0) * 255.0 + 0.5).toInt()
        assertEquals(dark, GradeMath.blendChannel(GradeBlend.OVERLAY, 60, 128))
        // Light base path (b>=0.5): 1-2(1-b)(1-s). base=200 -> 200 (computed).
        assertEquals(200, GradeMath.blendChannel(GradeBlend.OVERLAY, 200, 128))
    }

    @Test
    fun `soft_light formula matches the GLSL twin`() {
        // src gray mid-high (fs >= 0.5) branch: b + (2s-1)(sqrt(b)-b).
        val fb = 0.78431; val fs = 0.50196
        val expected = (fb + (2 * fs - 1) * (kotlin.math.sqrt(fb) - fb)).let {
            (it * 255.0 + 0.5).toInt()
        }
        assertEquals(expected, GradeMath.blendChannel(GradeBlend.SOFT_LIGHT, 200, 128))
        // Neutral src (0.5 exact) leaves base unchanged: 2s-1 = 0.
        assertEquals(200, GradeMath.blendChannel(GradeBlend.SOFT_LIGHT, 200, 128))
        // src == 0.5 exactly: fs = 127.5/255 rounds to 128 — assert via exact
        // formula instead: with src=0 -> dark branch: 2*b*0 + b^2*(1-0) = b^2.
        val b0 = (0.78431 * 0.78431 * 255.0 + 0.5).toInt()
        assertEquals(b0, GradeMath.blendChannel(GradeBlend.SOFT_LIGHT, 200, 0))
    }

    @Test
    fun `overlay pass mixes by opacity and keeps alpha`() {
        val argb = (0x40 shl 24) or (200 shl 16) or (200 shl 8) or 200
        val out = GradeMath.applyOverlay(argb, GradeBlend.MULTIPLY, 0, 0, 0, 0.5f)
        // base 200, multiply(200,0)=0 -> mix(200, 0, .5) = 100.
        assertEquals(100, (out shr 16) and 0xFF)
        assertEquals(100, (out shr 8) and 0xFF)
        assertEquals(100, out and 0xFF)
        assertEquals(0x40, (out ushr 24) and 0xFF)
    }

    @Test
    fun `identity curve is a transform no-op at full strength`() {
        val identityPx = IntArray(256 * 3) { x -> 0 }
        for (i in 0 until 256) {
            identityPx[i] = (0xFF shl 24) or (i shl 16)
            identityPx[256 + i] = (0xFF shl 24) or (i shl 8)
            identityPx[2 * 256 + i] = (0xFF shl 24) or i
        }
        val luts = GradeMath.lutsFromStrip(identityPx, 256, 3)
        val argb = (0xFF shl 24) or (17 shl 16) or (99 shl 8) or 231
        assertEquals(argb, GradeMath.applyCurve(luts, argb, 1f))
    }

    @Test
    fun `malformed strips do not crash`() {
        val tiny = GradeMath.lutsFromStrip(IntArray(10), 10, 1)
        assertTrue(tiny.any { it == null })
        val argb = (0xFF shl 24) or (1 shl 16) or (2 shl 8) or 3
        assertEquals(argb, GradeMath.applyCurve(tiny, argb, 1f))
    }
}
