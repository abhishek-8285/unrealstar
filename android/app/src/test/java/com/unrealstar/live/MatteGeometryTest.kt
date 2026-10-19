package com.unrealstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the makeup anchoring math ([MatteGeometry]). No Android
 * APIs are touched, so these run on the local JVM without Robolectric.
 *
 * The numbers pin the pack's canonical geometry (750x1334 canvas, eyes at
 * y=531.7, mouth at y=772.8 — see scripts/build_beauty_assets.py) against the
 * transform chain used by all three render paths.
 */
class MatteGeometryTest {

    private fun assertNear(expected: Float, actual: Float, tol: Float = 1e-3f) {
        assertEquals(expected.toDouble(), actual.toDouble(), tol.toDouble())
    }

    private fun face(
        eyeL: Pair<Float, Float>? = null,
        eyeR: Pair<Float, Float>? = null,
        mouthL: Pair<Float, Float>? = null,
        mouthR: Pair<Float, Float>? = null,
        cx: Float = 0.5f,
        cy: Float = 0.5f,
        widthFrac: Float = 0.3f,
        heightFrac: Float = 0.5f,
        frameW: Int = 1080,
        frameH: Int = 1920
    ) = FaceGeometry(cx, cy, widthFrac, heightFrac, eyeL, eyeR, mouthL, mouthR, frameW, frameH)

    // ---- solve: weighted similarity fit ----

    @Test
    fun solve_recoversScaleAndTranslation() {
        // view = 2 * canvas + (10, 20)
        val sim = MatteGeometry.solve(
            listOf(
                floatArrayOf(0f, 0f, 10f, 20f, 1f),
                floatArrayOf(10f, 0f, 30f, 20f, 1f),
                floatArrayOf(0f, 10f, 10f, 40f, 1f)
            )
        )
        assertNotNull(sim)
        assertNear(2f, sim!!.qx)
        assertNear(0f, sim.qy)
        assertNear(10f, sim.tx)
        assertNear(20f, sim.ty)
        val (x, y) = sim.map(5f, 5f)
        assertNear(20f, x)
        assertNear(30f, y)
    }

    @Test
    fun solve_recoversRotation() {
        // Complex q = i: map (x, y) -> (-y, x).
        val sim = MatteGeometry.solve(
            listOf(
                floatArrayOf(10f, 0f, 0f, 10f, 1f),
                floatArrayOf(0f, 10f, -10f, 0f, 1f)
            )
        )
        assertNotNull(sim)
        assertNear(0f, sim!!.qx)
        assertNear(1f, sim.qy)
        val (x, y) = sim.map(10f, 0f)
        assertNear(0f, x)
        assertNear(10f, y)
    }

    @Test
    fun solve_degenerateInputs_returnNull() {
        assertNull(MatteGeometry.solve(emptyList()))
        // All canvas points identical -> denominator collapses.
        assertNull(
            MatteGeometry.solve(
                listOf(
                    floatArrayOf(5f, 5f, 1f, 1f, 1f),
                    floatArrayOf(5f, 5f, 2f, 2f, 1f)
                )
            )
        )
        // Zero total weight.
        assertNull(MatteGeometry.solve(listOf(floatArrayOf(1f, 1f, 2f, 2f, 0f))))
    }

    // ---- cropToView: frame fractions -> center-cropped view fractions ----

    @Test
    fun cropToView_sameAspect_isIdentity() {
        val (x, y) = MatteGeometry.cropToView(0.25f, 0.75f, 1080f, 1920f, 1080f, 1920f)
        assertNear(0.25f, x)
        assertNear(0.75f, y)
    }

    @Test
    fun cropToView_tallerView_cropsWidth() {
        // 9:16 frame on a 9:20 view: visible width fraction = 0.8, 10% cut each side.
        val (x1, y1) = MatteGeometry.cropToView(0.1f, 0.5f, 1080f, 1920f, 1080f, 2400f)
        assertNear(0f, x1)
        assertNear(0.5f, y1)
        val (x2, y2) = MatteGeometry.cropToView(0.9f, 0.2f, 1080f, 1920f, 1080f, 2400f)
        assertNear(1f, x2)
        assertNear(0.2f, y2)
        val (xc, yc) = MatteGeometry.cropToView(0.5f, 0.5f, 1080f, 1920f, 1080f, 2400f)
        assertNear(0.5f, xc)
        assertNear(0.5f, yc)
    }

    @Test
    fun cropToView_widerView_cropsHeight() {
        // 9:16 frame on a 16:9 view: visible height fraction = 0.5625 / 1.7778.
        val visibleH = (1080f / 1920f) / (1920f / 1080f)
        val top = (1f - visibleH) / 2f
        val (x1, y1) = MatteGeometry.cropToView(0.3f, top, 1080f, 1920f, 1920f, 1080f)
        assertNear(0.3f, x1)
        assertNear(0f, y1)
        val (xc, yc) = MatteGeometry.cropToView(0.5f, 0.5f, 1080f, 1920f, 1920f, 1080f)
        assertNear(0.5f, xc)
        assertNear(0.5f, yc)
    }

    // ---- faceInView: crop + size scaling of the whole geometry ----

    @Test
    fun faceInView_mapsLandmarksAndScalesSizes() {
        val geo = face(
            eyeL = Pair(0.3f, 0.4f),
            eyeR = Pair(0.7f, 0.4f),
            mouthL = Pair(0.45f, 0.55f),
            mouthR = Pair(0.55f, 0.55f),
            cx = 0.5f, cy = 0.5f,
            widthFrac = 0.4f, heightFrac = 0.5f
        )
        val out = MatteGeometry.faceInView(geo, 1080f, 2400f)
        // Width crops by 0.8 ((x - 0.1) / 0.8); height untouched.
        assertNear(0.25f, out.eyeL!!.first)
        assertNear(0.75f, out.eyeR!!.first)
        assertNear(0.4f, out.eyeL!!.second)
        assertNear(0.5f, out.centerX)
        assertNear(0.5f, out.centerY)
        // Width fraction grows by frameAspect/viewAspect = 1.25.
        assertNear(0.5f, out.widthFrac)
        assertNear(0.5f, out.heightFrac)
        assertNear(0.4375f, out.mouthL!!.first) // (0.45 - 0.1) / 0.8
    }

    // ---- similarityFor: full canvas -> view fit ----

    /** Fractions that place the canonical landmarks exactly on a same-size view. */
    private fun canonicalSizedFace(): FaceGeometry = face(
        eyeL = Pair(263.2f / 750f, 531.7f / 1334f),
        eyeR = Pair(504.8f / 750f, 531.7f / 1334f),
        mouthL = Pair(386.1f / 750f, 772.8f / 1334f),
        mouthR = Pair(386.1f / 750f, 772.8f / 1334f),
        frameW = 750, frameH = 1334
    )

    @Test
    fun similarityFor_samePixelView_isIdentity() {
        val sim = MatteGeometry.similarityFor(
            canonicalSizedFace(), CanonicalFace.EMPTY, 750f, 1334f, crop = false
        )
        assertNotNull(sim)
        val (x, y) = sim!!.map(263.2f, 531.7f)
        assertNear(263.2f, x, 0.02f)
        assertNear(531.7f, y, 0.02f)
        val (mx, my) = sim.map(386.1f, 772.8f)
        assertNear(386.1f, mx, 0.02f)
        assertNear(772.8f, my, 0.02f)
    }

    @Test
    fun similarityFor_doubleSizeView_isDoubleScale() {
        // View = exactly 2x the canvas in both axes (crop is identity).
        val sim = MatteGeometry.similarityFor(
            canonicalSizedFace(), CanonicalFace.EMPTY, 1500f, 2668f, crop = true
        )
        assertNotNull(sim)
        assertNear(2f, sim!!.qx, 1e-3f)
        assertNear(0f, sim.qy, 1e-3f)
        assertNear(0f, sim.tx, 0.05f)
        assertNear(0f, sim.ty, 0.05f)
        val (x, y) = sim.map(263.2f, 531.7f)
        assertNear(526.4f, x, 0.1f)
        assertNear(1063.4f, y, 0.1f)
    }

    @Test
    fun similarityFor_withoutLandmarks_usesBoxFallback() {
        val geo = face(
            cx = 0.5f, cy = 0.5f, widthFrac = 0.4f, heightFrac = 0.5f,
            frameW = 1080, frameH = 1920
        )
        val sim = MatteGeometry.similarityFor(
            geo, CanonicalFace.EMPTY, 1080f, 1920f, crop = false
        )
        assertNotNull(sim)
        // Orientation sanity: the fitted mouth must sit below the fitted eyes.
        val (_, eyeY) = sim!!.map(263.2f, 531.7f)
        val (_, mouthY) = sim.map(386.1f, 772.8f)
        assertTrue(mouthY > eyeY)
        // The box fallback implies a plausible scale (face ~0.4 of 1080px wide).
        val scale = kotlin.math.hypot(sim.qx, sim.qy)
        assertTrue(scale > 0.3f && scale < 2.0f)
    }

    @Test
    fun similarityFor_zeroView_returnsNull() {
        assertNull(MatteGeometry.similarityFor(canonicalSizedFace(), CanonicalFace.EMPTY, 0f, 0f, false))
    }

    // ---- viewFractionToMaskUv: shader-side mapping ----

    @Test
    fun maskUv_anchorMapsToOrigin_andEdgesToUnit() {
        // q = 2, t = (10, 20); anchor (100, 200); imageScale 1; mask 100x50.
        val sim = MatteGeometry.Similarity(2f, 0f, 10f, 20f)
        val xf = MatteGeometry.viewFractionToMaskUv(sim, 100f, 200f, 1f, 100, 50, 1000f, 1000f)
        fun mu(x: Float, y: Float) = xf[0] * x + xf[1] * y + xf[4]
        fun mv(x: Float, y: Float) = xf[2] * x + xf[3] * y + xf[5]
        // Anchor maps to mask UV (0, 0): view px (210, 420) -> fractions (0.21, 0.42).
        assertNear(0f, mu(0.21f, 0.42f))
        assertNear(0f, mv(0.21f, 0.42f))
        // Right mask edge: canvas (200, 200) -> view px (410, 420).
        assertNear(1f, mu(0.41f, 0.42f))
        // Bottom mask edge: canvas (100, 250) -> view px (210, 520).
        assertNear(1f, mv(0.21f, 0.52f))
    }

    // ---- maskToViewMatrix: Canvas-side mapping (px -> px) ----

    @Test
    fun maskMatrix_mapsTextureCornersToStringCanvas() {
        // q = i (90 deg), imageScale 2, anchor (0, 0), t = 0:
        // view = (-2*texY, 2*texX).
        val m = MatteGeometry.maskToViewMatrix(
            MatteGeometry.Similarity(0f, 1f, 0f, 0f), 0f, 0f, 2f
        )
        fun vx(tx: Float, ty: Float) = m[0] * tx + m[1] * ty + m[2]
        fun vy(tx: Float, ty: Float) = m[3] * tx + m[4] * ty + m[5]
        assertNear(-6f, vx(5f, 3f))
        assertNear(10f, vy(5f, 3f))
    }

    @Test
    fun maskMatrix_appliesAnchorAndScale() {
        // q = 2, t = (10, 20); anchor (100, 200); imageScale 1; mask 100x50.
        val m = MatteGeometry.maskToViewMatrix(
            MatteGeometry.Similarity(2f, 0f, 10f, 20f), 100f, 200f, 1f
        )
        fun vx(tx: Float, ty: Float) = m[0] * tx + m[1] * ty + m[2]
        fun vy(tx: Float, ty: Float) = m[3] * tx + m[4] * ty + m[5]
        // Texture origin -> anchor's view position.
        assertNear(210f, vx(0f, 0f))
        assertNear(420f, vy(0f, 0f))
        // Right edge (100 tex px = 100 canvas px at scale 1).
        assertNear(410f, vx(100f, 0f))
        // Bottom edge (50 tex px).
        assertNear(520f, vy(0f, 50f))
    }
}
