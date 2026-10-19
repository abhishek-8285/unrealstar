package com.unrealstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-computed expectations for the CPU twins of the REA-derived beauty
 * stages (Banuba `smooth.frag` / `retouch.frag` port — see [BeautyShader]).
 *
 * Parity caveat (same as [GradeMath]): the shader runs at mediump, so edge
 * cases of `step()`/`smoothstep()` may differ by one 8-bit unit.
 */
class BeautyMathTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun rOf(c: Int) = (c shr 16) and 0xFF
    private fun gOf(c: Int) = (c shr 8) and 0xFF
    private fun bOf(c: Int) = c and 0xFF

    private fun assertNear(expected: Int, actual: Int, tol: Int = 1, msg: String = "") {
        assertTrue(
            "$msg expected $expected±$tol, got $actual",
            kotlin.math.abs(expected - actual) <= tol
        )
    }

    // ---- curve ----

    @Test
    fun curveEndpointsClamp() {
        // f(0) = -0.0637 -> clamped to 0; f(1) = 0.999969.
        assertEquals(0f, BeautyMath.curveChannel(0f), 1e-6f)
        assertEquals(1f, BeautyMath.curveChannel(1f), 1e-4f)
        // Early stop: the polynomial dips below 0 at the dark end.
        assertEquals(0f, BeautyMath.curveChannel(0.04f), 1e-6f)
    }

    @Test
    fun curveLiftsMidtones() {
        // f(0.5) = -0.0979 + 0.7277 - 0.0637 = 0.5661.
        assertEquals(0.5661f, BeautyMath.curveChannel(0.5f), 1e-3f)
        assertTrue(BeautyMath.curveChannel(0.75f) > 0.75f)
    }

    // ---- skin window (stand-in for Banuba's NN mask) ----

    @Test
    fun skinWeightPassesWarmSkin() {
        // Classic skin sample RGB(224,172,137): all three windows saturate.
        assertEquals(1f, BeautyMath.skinWeight(224f / 255f, 172f / 255f, 137f / 255f), 1e-3f)
    }

    @Test
    fun skinWeightRejectsGrayGreenBlue() {
        // Pure gray: Cr = 0.5, below the skin window floor.
        assertEquals(0f, BeautyMath.skinWeight(0.5f, 0.5f, 0.5f), 1e-3f)
        // Saturated green background.
        assertEquals(0f, BeautyMath.skinWeight(100f / 255f, 200f / 255f, 60f / 255f), 1e-3f)
        // Saturated blue.
        assertEquals(0f, BeautyMath.skinWeight(0.2f, 0.3f, 0.9f), 1e-3f)
        // Shadow skin: luma floor kills it.
        assertEquals(0f, BeautyMath.skinWeight(0.08f, 0.05f, 0.04f), 1e-3f)
    }

    // ---- adaptive composite (smooth.frag) ----

    @Test
    fun compositeAtZeroStrengthIsIdentity() {
        val skin = argb(204, 158, 128)
        assertEquals(skin, BeautyMath.composite(skin, argb(255, 200, 150), 0f))
    }

    @Test
    fun compositeLeavesNonSkinUntouched() {
        // Saturated green: skin weight 0 -> no curve, no fade, exact identity.
        val green = argb(100, 200, 60)
        assertEquals(green, BeautyMath.composite(green, argb(120, 220, 80), 1f))
    }

    @Test
    fun compositeCurveLiftsFlatSkin() {
        // blurred == original -> val2 = 0.5 exactly, no unsharp term; the
        // result is 40% of the curve/soft-skin mix at s=1.
        // Hand computed: (204,158,128) -> (205,159,129).
        val out = BeautyMath.composite(argb(204, 158, 128), argb(204, 158, 128), 1f)
        assertNear(205, rOf(out), 1, "r")
        assertNear(159, gOf(out), 1, "g")
        assertNear(129, bOf(out), 1, "b")
    }

    @Test
    fun compositePullsStronglyBlurredSkinTowardBlur() {
        // Bright blur: all three channels must move up toward the blur.
        val orig = argb(204, 158, 128)
        val out = BeautyMath.composite(orig, argb(255, 200, 150), 1f)
        assertTrue("r up", rOf(out) > rOf(orig))
        assertTrue("g up", gOf(out) > gOf(orig))
        assertTrue("b up", bOf(out) > bOf(orig))
        assertTrue("r below blur", rOf(out) < 255)
        assertTrue("b below blur", bOf(out) < 150)
    }

    // ---- eyes/teeth whiten + retouch damping ----

    @Test
    fun whitenZeroIsIdentity() {
        assertEquals(argb(204, 158, 128), BeautyMath.whiten(argb(204, 158, 128), 0f))
    }

    @Test
    fun whitenDesaturatesAndLifts() {
        // Yellowish tooth (229,217,179) at full amount: blue rises (desat),
        // red rises a little (lift), channel spread shrinks.
        val src = argb(229, 217, 179)
        val out = BeautyMath.whiten(src, 1f)
        assertTrue("b up", bOf(out) > bOf(src))
        assertTrue("r lifted", rOf(out) > rOf(src))
        assertTrue(
            "spread shrinks",
            (rOf(out) - bOf(out)) < (rOf(src) - bOf(src))
        )
    }

    @Test
    fun whitenPureWhiteAndBlackExact() {
        assertEquals(argb(255, 255, 255), BeautyMath.whiten(argb(255, 255, 255), 1f))
        // l = 0 -> flat = 0 -> lifted = 0.28 -> 71.4 -> 71.
        assertNear(71, rOf(BeautyMath.whiten(0xFF000000.toInt(), 1f)), 1, "r")
    }

    @Test
    fun retouchProtectsSaturatedLips() {
        // Pure red: saturation damping = 0 -> exact identity even at coverage 1.
        assertEquals(argb(255, 0, 0), BeautyMath.retouch(argb(255, 0, 0), 1f, 1f))
    }

    @Test
    fun retouchFullCoverageOnLowSatEqualsWhiten() {
        // Neutral gray: saturation 0 -> damping 1 -> full coverage == whiten.
        val tooth = argb(235, 235, 235)
        assertEquals(
            BeautyMath.whiten(tooth, 0.8f),
            BeautyMath.retouch(tooth, 0.8f, 1f)
        )
    }

    @Test
    fun retouchPartialCoverageHalvesEffect() {
        val tooth = argb(235, 235, 235)
        val full = BeautyMath.whiten(tooth, 1f)
        val half = BeautyMath.retouch(tooth, 1f, 0.5f)
        // Midpoint between original and whitened.
        assertNear(
            (bOf(tooth) + bOf(full)) / 2, bOf(half), 1, "b"
        )
    }

    // ---- sharpen (5c - 4 diagonals) ----

    @Test
    fun sharpenFlatNeighborhoodIsExactIdentity() {
        val flat = argb(137, 137, 137)
        val nb = IntArray(4) { flat }
        assertEquals(flat, BeautyMath.sharpen(flat, nb, 1f))
    }

    @Test
    fun sharpenBoostsBrightIsolatedCenter() {
        // 5*200 - 4*100 = 600 -> clamped 255; mix(200, 255, 0.5) = 228.
        val nb = IntArray(4) { argb(100, 100, 100) }
        val out = BeautyMath.sharpen(argb(200, 200, 200), nb, 0.5f)
        assertEquals(228, rOf(out))
    }

    // ---- look knobs ----

    @Test
    fun knobsNeutralIsIdentity() {
        for (c in listOf(argb(0, 0, 0), argb(128, 128, 128), argb(200, 50, 50), argb(255, 255, 255))) {
            assertEquals(c, BeautyMath.knobs(c, 0f, 0f, 0f))
        }
    }

    @Test
    fun knobsSaturationExpandsDeviationFromLuma() {
        val src = argb(200, 50, 50)
        val out = BeautyMath.knobs(src, 1f, 0f, 0f)
        assertTrue("r up", rOf(out) > rOf(src))
        assertTrue("g down", gOf(out) < gOf(src))
    }

    @Test
    fun knobsContrastPivotsAtHalf() {
        // 0.75 with contrast 1: 0.5 + 0.25*1.5 = 0.875 -> 223.
        assertNear(223, rOf(BeautyMath.knobs(argb(191, 191, 191), 0f, 1f, 0f)), 1)
        // Below the pivot moves down.
        val dark = BeautyMath.knobs(argb(64, 64, 64), 0f, 1f, 0f)
        assertTrue(rOf(dark) < 64)
    }

    @Test
    fun knobsRuddyWarmsSkinOnly() {
        // Skin: red up, blue down (warmth).
        val skin = BeautyMath.knobs(argb(224, 172, 137), 0f, 0f, 1f)
        assertTrue("r up", rOf(skin) > 224)
        assertTrue("b down", bOf(skin) < 137)
        // Gray: the skin window rejects it -> identity.
        assertEquals(argb(128, 128, 128), BeautyMath.knobs(argb(128, 128, 128), 0f, 0f, 1f))
    }
}
