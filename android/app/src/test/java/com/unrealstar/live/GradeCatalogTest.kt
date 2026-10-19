package com.unrealstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract test for the committed grade assets: catalog.json parses, every
 * referenced curve/overlay texture exists on disk, ranges are sane, and the
 * cross-channel-only filters (moon B&W, sepia, inkwell) are matrix-only.
 * Guards scripts/build_grade_assets.py against shipping a broken bundle.
 */
class GradeCatalogTest {

    private fun assetsGradeDir(): File {
        val a = File("src/main/assets/grade")
        if (a.exists()) return a
        return File("app/src/main/assets/grade").also { assertTrue(it.exists()) }
    }

    @Test
    fun `catalog parses with every committed filter recipe`() {
        val file = File(assetsGradeDir(), "catalog.json")
        assertTrue("catalog.json missing", file.exists())
        val recipes = GradeCatalog.parse(file.readText())

        // The CameraFilter ids that carry recipes (moon keeps its vignette;
        // sepia and inkwell are pure matrices and never appear in the catalog).
        val expected = setOf(
            "clarendon", "juno", "gingham", "lark", "moon",
            "aden", "valencia", "xpro", "lofi", "nashville"
        )
        assertEquals(expected, recipes.keys)

        val dir = assetsGradeDir()
        for (r in recipes.values) {
            // Range sanity: strength default/min/max (Instagram's triple).
            assertTrue("strength default out of range: ${r.id}", r.strengthDefault in r.strengthMin..r.strengthMax)
            assertTrue("max strength < min: ${r.id}", r.strengthMax >= r.strengthMin)
            // Overlays parse with valid blends + acitve opacities and files on disk.
            for (ov in r.overlays) {
                assertTrue("opacity out of range: ${r.id}/${ov.texture}", ov.opacity in 0f..1f)
                assertTrue("file missing: ${ov.texture}", File(dir, ov.texture).exists())
            }
            // A curve, when present, ships as an asset.
            if (r.curve != null) {
                assertTrue("curve missing: ${r.curve}", File(dir, r.curve!!).exists())
            }
        }

        // Cross-channel looks must stay matrix-only: moon may keep a vignette
        // overlay but never a curve; sepia/inkwell are pure matrices and are
        // absent from the catalog entirely.
        assertTrue(recipes["moon"]!!.curve == null)
        assertTrue("sepia" !in recipes)
        assertTrue("inkwell" !in recipes)
        // Recipe-carrying filters have a curve or overlays (a recipe that is
        // entirely empty would be a pointless catalog entry).
        for (r in recipes.values) {
            assertTrue("empty recipe: ${r.id}", r.curve != null || r.overlays.isNotEmpty())
        }
        // Spot values from the generator's curated table.
        assertEquals(GradeBlend.SCREEN, recipes["juno"]!!.overlays.first().blend)
        assertEquals(GradeBlend.MULTIPLY, recipes["xpro"]!!.overlays.first().blend)
        assertEquals(GradeBlend.SOFT_LIGHT, recipes["valencia"]!!.overlays.first().blend)
        assertTrue(recipes["lofi"]!!.overlays.size >= 2)
        assertNotNull(recipes["nashville"]!!.curve)
    }
}
