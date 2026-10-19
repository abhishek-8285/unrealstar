package com.unrealstar.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure JVM coverage of the runtime filter-pack contract ([GradePackLoader]):
 * verification caps/entry rules, catalog schema parsing and the builtin-list
 * merge semantics. Android-only layers (disk swap, OkHttp fetch, decode) are
 * exercised on device — see rea-out/grade-verify/.
 */
class GradePackTest {

    // ------------------------------------------------------------------
    // Helpers: build a pack ZIP in memory (java.util.zip => JVM-pure).
    // ------------------------------------------------------------------

    private val PNG = validPngHeader()

    private fun validPngHeader(width: Int = 256, height: Int = 3): ByteArray {
        val b = ByteArray(33)
        b[0] = 0x89.toByte(); b[1] = 0x50; b[2] = 0x4E; b[3] = 0x47
        b[4] = 0x0D; b[5] = 0x0A; b[6] = 0x1A; b[7] = 0x0A
        b[12] = 0x49; b[13] = 0x48; b[14] = 0x44; b[15] = 0x52 // "IHDR"
        fun putU32(i: Int, v: Int) {
            b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte()
            b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
        }
        putU32(8, 13) // IHDR length
        putU32(16, width)
        putU32(20, height)
        b[24] = 8 // bit depth
        b[25] = 2 // truecolor
        b[28] = 0 // no interlace
        return b
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun packJson(vararg filters: String, version: Int = 1) = """
        {"version": $version, "filters": [${filters.joinToString()}]}
    """.trimIndent()

    private fun recipeFilterJson(
        id: String,
        curve: String? = "$id/curve.png",
        extra: String = ""
    ): String {
        val curvePart = curve?.let { "\"curve\": \"$it\", " } ?: ""
        return """{"id": "$id", ${curvePart}
            "overlays": [{"texture": "$id/vignette.png", "blend": "multiply", "opacity": 0.4}],
            "strength": {"default": 0.9, "min": 0.0, "max": 1.0}$extra}"""
    }

    private fun packZip(vararg filters: String, version: Int = 1) =
        zip(
            "catalog.json" to packJson(*filters, version = version).toByteArray(),
            "a/curve.png" to PNG,
            "a/vignette.png" to PNG,
            "b/curve.png" to PNG,
            "b/vignette.png" to PNG,
            "c/vignette.png" to PNG
        )

    private fun expectPackError(body: () -> Unit, messagePart: String? = null) {
        try {
            body()
            fail("expected PackException")
        } catch (e: GradePackLoader.PackException) {
            messagePart?.let { assertTrue("message '$e' lacks '$it'", e.message!!.contains(it)) }
        }
    }

    private fun builtin(id: String) = CameraFilter(
        id = id,
        name = id,
        colorMatrix = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f
        ),
        tintColor = 0x112233, // matches Color.parseColor("#112233") — pure int
        tintAlpha = 0.1f
    )

    // ------------------------------------------------------------------
    // verify(): happy path + structural defenses.
    // ------------------------------------------------------------------

    @Test
    fun verify_acceptsValidPackAndExposesCatalogAndFiles() {
        val verified = GradePackLoader.verify(
            packZip(recipeFilterJson("a"), recipeFilterJson("b"))
        )
        assertEquals(1, verified.version)
        assertEquals(6, verified.files.size) // catalog + 5 asset entries zipped by the helper
        assertTrue(verified.files.containsKey("catalog.json"))
        assertTrue(verified.files.containsKey("a/curve.png"))
    }

    @Test
    fun verify_rejectsMissingCatalog() {
        expectPackError({ GradePackLoader.verify(zip("a/curve.png" to PNG)) }, "missing")
    }

    @Test
    fun verify_rejectsMissingReferencedAsset() {
        // filter c references c/vignette.png AND c/curve.png (missing from zip)
        expectPackError({ GradePackLoader.verify(packZip(recipeFilterJson("c"))) }, "missing curve asset")
    }

    @Test
    fun verify_rejectsOversizeZip() {
        val big = ByteArray(GradePackLoader.MAX_ZIP_BYTES.toInt() + 1)
        expectPackError({ GradePackLoader.verify(big) }, "too large")
    }

    @Test
    fun verify_rejectsOversizeEntry() {
        val blob = ByteArray(GradePackLoader.MAX_ENTRY_BYTES.toInt() + 1024) // deflate-friendly zeros
        expectPackError({
            GradePackLoader.verify(
                zip(
                    "catalog.json" to packJson(recipeFilterJson("a", curve = null)).toByteArray(),
                    "a/junk.png" to blob
                )
            )
        }, "too large")
    }

    @Test
    fun verify_rejectsZipSlipAndBadPaths() {
        val cat = packJson(recipeFilterJson("a", curve = null)).toByteArray()
        expectPackError({
            GradePackLoader.verify(zip("../evil.png" to PNG, "catalog.json" to cat))
        }, "bad entry name")
        expectPackError({
            GradePackLoader.verify(zip("/abs.png" to PNG, "catalog.json" to cat))
        }, "bad entry name")
        expectPackError({
            GradePackLoader.verify(zip("dir\\x.png" to PNG, "catalog.json" to cat))
        }, "bad entry name")
    }

    @Test
    fun verify_rejectsUnexpectedEntryTypes() {
        val cat = packJson(recipeFilterJson("a", curve = null)).toByteArray()
        expectPackError({
            GradePackLoader.verify(zip("readme.txt" to byteArrayOf(), "catalog.json" to cat))
        }, "unexpected entry type")
    }

    @Test
    fun verify_rejectsCorruptPng() {
        val cat = packJson(recipeFilterJson("a", curve = null)).toByteArray()
        expectPackError({
            GradePackLoader.verify(zip("a/decoy.png" to "JPG?nope".toByteArray(), "catalog.json" to cat))
        }, "not a png")
    }

    @Test
    fun verify_rejectsHugePngDims() {
        val cat = packJson(recipeFilterJson("a", curve = null)).toByteArray()
        expectPackError({
            GradePackLoader.verify(
                zip("a/big.png" to validPngHeader(width = 4096, height = 2), "catalog.json" to cat)
            )
        }, "dims out of range")
    }

    @Test
    fun verify_rejectsVersionZero() {
        val cat = packJson(recipeFilterJson("a", curve = null), version = 0).toByteArray()
        expectPackError({ GradePackLoader.verify(zip("catalog.json" to cat)) }, "version")
    }

    @Test
    fun verify_rejectsMalformedZip() {
        expectPackError({ GradePackLoader.verify(ByteArray(64) { it.toByte() }) })
    }

    // ------------------------------------------------------------------
    // parseCatalog(): schema surface.
    // ------------------------------------------------------------------

    @Test
    fun parseCatalog_mapsGradeFieldsAndStrength() {
        val pack = GradePackLoader.parseCatalog(
            packJson(recipeFilterJson("a")), null
        )
        assertEquals(1, pack.version)
        val e = requireNotNull(pack.byId["a"])
        assertEquals("a/curve.png", e.recipe.curve)
        assertEquals(GradeBlend.MULTIPLY, e.recipe.overlays[0].blend)
        assertEquals(0.4f, e.recipe.overlays[0].opacity, 1e-6f)
        assertEquals(0.9f, e.recipe.strengthDefault, 1e-6f)
        assertEquals(null, e.recipe.packDir) // verify-time parse: no pack root yet
    }

    @Test
    fun parseCatalog_parsesDisplayNameTintAndMatrix() {
        val text = packJson(
            """{"id": "sakura", "name": "Sakura", "tint": {"color": "#F472B6", "alpha": 0.09},
                "curve": "a/curve.png", "overlays": []}"""
        )
        val e = requireNotNull(GradePackLoader.parseCatalog(text, null).byId["sakura"])
        assertEquals("Sakura", e.name)
        assertEquals(0xFFF472B6.toInt(), e.tintColor)
        assertEquals(0.09f, e.tintAlpha!!, 1e-6f)
        assertEquals(null, e.matrix)
    }

    @Test
    fun parseCatalog_parsesOverrideMatrix() {
        val text = packJson(
            """{"id": "x", "matrix": [2.0,0,0,0,10, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,1,0],
                "curve": "a/curve.png", "overlays": []}"""
        )
        val e = requireNotNull(GradePackLoader.parseCatalog(text, null).byId["x"])
        assertEquals(2f, e.matrix!![0], 1e-6f)
        assertEquals(10f, e.matrix!![4], 1e-6f)
    }

    @Test
    fun parseCatalog_rootPackDirFlowsIntoRecipes() {
        val pack = GradePackLoader.parseCatalog(
            packJson(recipeFilterJson("a")), "/data/user/0/app/files/grade_pack/v1"
        )
        assertEquals("/data/user/0/app/files/grade_pack/v1", pack.byId["a"]!!.recipe.packDir)
    }

    @Test
    fun parseCatalog_duplicateIdKeepsFirst() {
        val pack = GradePackLoader.parseCatalog(
            packJson(recipeFilterJson("a"), recipeFilterJson("a")), null
        )
        assertEquals(1, pack.entries.size)
    }

    @Test
    fun parseCatalog_rejectsBadHexTint() {
        val text = packJson(
            """{"id": "a", "tint": {"color": "zzz"}, "curve": "a/curve.png", "overlays": []}"""
        )
        val e = requireNotNull(GradePackLoader.parseCatalog(text, null).byId["a"])
        assertEquals(null, e.tintColor)
    }

    // ------------------------------------------------------------------
    // applyPack(): override + append semantics.
    // ------------------------------------------------------------------

    @Test
    fun applyPack_overridesBuiltinByIdAndKeepsOrder() {
        val builtin = listOf(builtin("normal"), builtin("clarendon"))
        val pack = GradePackLoader.parseCatalog(
            packJson(
                """{"id": "clarendon", "name": "Clare+", "curve": "a/curve.png", "overlays": []}"""
            ), null
        )
        val merged = GradePackLoader.applyPack(builtin, pack)
        assertEquals(2, merged.size)
        assertEquals("normal", merged[0].id)
        val clarendon = merged[1]
        assertEquals("Clare+", clarendon.name)
        assertEquals("a/curve.png", clarendon.recipe!!.curve)
    }

    @Test
    fun applyPack_appendsPackOnlyFiltersWithDefaults() {
        val builtin = listOf(builtin("normal"))
        val pack = GradePackLoader.parseCatalog(
            packJson(
                """{"id": "cyber", "curve": "b/curve.png", "overlays": []}"""
            ), null
        )
        val merged = GradePackLoader.applyPack(builtin, pack)
        assertEquals(2, merged.size)
        val cyber = merged[1]
        assertEquals("cyber", cyber.id)
        assertEquals("Cyber", cyber.name) // prettyId fallback
        assertEquals(0, cyber.tintColor) // no tint shipped => lite shows no look
        assertEquals(0f, cyber.tintAlpha, 1e-6f)
        assertEquals(1f, cyber.colorMatrix[0], 1e-6f) // identity matrix
    }

    @Test
    fun applyPack_missingRecipeEntriesStayBuiltin() {
        val builtin = listOf(builtin("normal"), builtin("sepia"))
        val pack = GradePackLoader.parseCatalog(packJson(recipeFilterJson("a")), null)
        val merged = GradePackLoader.applyPack(builtin, pack)
        assertEquals(null, merged[0].recipe)
        assertEquals(null, merged[1].recipe)
        assertEquals("sepia", merged[1].id)
    }
}
