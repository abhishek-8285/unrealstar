package com.unrealstar.live

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Runtime-fetchable filter pack (Snap's `LensResource.file_url` model, REA
 * evidence: rea-out/com.snapchat.android/FILTER_FINDINGS.md — its catalog is
 * fetched at runtime and materialized client-side; only 4 filters ship in the
 * APK). CamStar has no baked-in SDK equivalent, so the contract here is:
 *
 * - One ZIP at `BuildConfig.GRADE_PACK_URL` (blank = feature off — release
 *   builds ship blank until the backend endpoint exists; debug builds inject
 *   a loopback URL with `-PgradePackUrl=...`, served through `adb reverse`
 *   and whitelisted for cleartext by `network_security_config.xml`).
 * - The request carries `X-Grade-Pack-Version: <installed>`; a 304 keeps the
 *   installed pack (server answers with the pack version in that header).
 * - ZIP shape: top-level `catalog.json` + the PNG paths it references, laid
 *   out like the shipped `assets/grade/` tree (`<id>/curve.png` etc.).
 * - Catalog schema = the shipped one (`version` at the root; `filters[]` with
 *   `curves`/`overlays`/`strength`) extended with optional display metadata:
 *   `name`, `tint {color,alpha}` and `matrix` (20-float 4x5, lite-path look)
 *   — needed because pack-only filter ids have no [INSTAGRAM_FILTERS] twin.
 * - Validate-then-verify-then-swap: bytes are fully validated in memory
 *   (caps, entry-name sanity, catalog parse, every referenced asset present,
 *   PNG headers sane) BEFORE anything lands on disk; the swap itself is a
 *   staged directory + atomic pointer write, so a broken download can never
 *   degrade the camera.
 */
object GradePackLoader {

    private const val TAG = "GradePack"

    /** Defensive caps: a malicious/huge pack must be rejected, not OOM'd. */
    const val MAX_ZIP_BYTES: Long = 12L * 1024 * 1024
    const val MAX_ENTRY_BYTES: Long = 6L * 1024 * 1024
    const val MAX_ENTRIES = 96

    private const val CATALOG_ENTRY = "catalog.json"

    // ------------------------------------------------------------------
    // Pure model (JVM-testable: no Android in this block).
    // ------------------------------------------------------------------

    /** Validated in-memory pack: parsed catalog + raw asset bytes by name. */
    class VerifiedPack(
        val version: Int,
        val catalogText: String,
        val files: Map<String, ByteArray>
    )

    /**
     * One catalog entry: the grade recipe (curve/overlays/strength) plus the
     * optional display metadata overriding a builtin filter or defining a
     * pack-only one. `packDir` is resolved by [loadActivePack].
     */
    class GradePackEntry(
        val id: String,
        val name: String?,
        val recipe: GradeRecipe,
        val tintColor: Int?,
        val tintAlpha: Float?,
        val matrix: FloatArray?
    )

    /** Parsed catalog: version + entries; duplicate ids keep the first. */
    class GradePackCatalog(
        val version: Int,
        val entries: List<GradePackEntry>
    ) {
        val byId: Map<String, GradePackEntry> = entries.associateBy { it.id }
    }

    /**
     * Merges a verified pack over the builtin filter list:
     * - an id inside [builtin] overrides that filter (recipe always; name/
     *   tint/matrix only when the pack defines them);
     * - unknown ids are appended as new filters (matrix defaults identity,
     *   tint defaults transparent — the lite path then shows no live look,
     *   grade-only, which is the correct degradation).
     * Pure: no Android calls, so unit tests can drive it directly.
     */
    fun applyPack(builtin: List<CameraFilter>, pack: GradePackCatalog): List<CameraFilter> {
        val unused = mutableSetOf<String>().apply { addAll(pack.byId.keys) }
        val merged = builtin.map { f ->
            val e = pack.byId[f.id] ?: return@map f
            unused.remove(f.id)
            CameraFilter(
                id = f.id,
                name = e.name ?: f.name,
                colorMatrix = e.matrix ?: f.colorMatrix,
                tintColor = e.tintColor ?: f.tintColor,
                tintAlpha = e.tintAlpha ?: f.tintAlpha,
                recipe = e.recipe
            )
        }
        val extras = pack.entries.filter { it.id in unused }.map { e ->
            CameraFilter(
                id = e.id,
                name = e.name ?: prettyId(e.id),
                colorMatrix = e.matrix ?: identityMatrix(),
                tintColor = e.tintColor ?: 0x00000000,
                tintAlpha = e.tintAlpha ?: 0f,
                recipe = e.recipe
            )
        }
        return merged + extras
    }

    /** "#RRGGBB" -> 0xFFrrggbb, "#AARRGGBB" -> 0xAARRGGBB; null otherwise. */
    private fun parseHexArgb(hex: String): Int? {
        val digits = hex.removePrefix("#")
        if (!(digits.length == 6 || digits.length == 8)) return null
        val v = digits.toLongOrNull(16)?.toInt() ?: return null
        return if (digits.length == 6) (0xFF shl 24) or (v and 0xFFFFFF) else v
    }

    private fun prettyId(id: String) = id.split('_', '-').joinToString(" ") { seg ->
        if (seg.isEmpty()) seg else seg[0].uppercase() + seg.drop(1)
    }

    private fun identityMatrix() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f
    )

    // ------------------------------------------------------------------
    // Verification (pure).
    // ------------------------------------------------------------------

    /**
     * Validates pack bytes end-to-end and hands back the model. Throws
     * [PackException] with the reason on any violation.
     */
    fun verify(zipBytes: ByteArray): VerifiedPack {
        if (zipBytes.isEmpty()) throw PackException("empty body")
        if (zipBytes.size > MAX_ZIP_BYTES)
            throw PackException("pack too large: ${zipBytes.size} > $MAX_ZIP_BYTES")
        var catalogText: String? = null
        val files = HashMap<String, ByteArray>()
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) throw PackException("too many entries")
                    val name = e.name
                    checkEntryName(name)
                    val bytes = readCapped(zis, MAX_ENTRY_BYTES, name)
                    when {
                        name == CATALOG_ENTRY ->
                            catalogText = bytes.decodeToString()
                        name.endsWith(".png") -> checkPngHeader(bytes, name)
                        else -> throw PackException("unexpected entry type: $name")
                    }
                    files[name] = bytes
                    zis.closeEntry()
                }
            }
        } catch (t: PackException) {
            throw t
        } catch (t: Throwable) {
            throw PackException("unreadable zip: ${t.message}", t)
        }
        val text = catalogText ?: throw PackException("missing $CATALOG_ENTRY")
        val catalog = parseCatalog(text, null)
        for (e in catalog.entries) {
            e.recipe.curve?.let { if (!files.containsKey(it))
                throw PackException("${e.id}: missing curve asset $it") }
            for (ov in e.recipe.overlays) {
                if (!files.containsKey(ov.texture))
                    throw PackException("${e.id}: missing overlay asset ${ov.texture}")
            }
        }
        return VerifiedPack(catalog.version, text, files)
    }

    /** Zip-slip + character-set defense: relative, deep, lowercase paths only. */
    private fun checkEntryName(name: String) {
        if (name.isEmpty() || name.length > 128) throw PackException("bad entry name: $name")
        if (name.startsWith("/") || name.contains('\\')) throw PackException("bad entry name: $name")
        for (seg in name.split("/")) {
            if (seg.isEmpty() || seg == "." || seg == "..") throw PackException("bad entry name: $name")
        }
        for (c in name) {
            val ok = c in 'a'..'z' || c in '0'..'9' || c == '/' || c == '_' || c == '.' || c == '-'
            if (!ok) throw PackException("bad entry name: $name")
        }
    }

    private fun readCapped(zis: ZipInputStream, cap: Long, name: String): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zis.read(chunk)
            if (n < 0) break
            total += n
            if (total > cap) throw PackException("entry too large: $name")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /**
     * PNG signature + IHDR sanity (dims/color type/depth). Full decode is a
     * platform concern — a broken body still fails gracefully on use.
     */
    private fun checkPngHeader(bytes: ByteArray, name: String) {
        if (bytes.size < 33
            || bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte()
            || bytes[2] != 0x4E.toByte() || bytes[3] != 0x47.toByte()
            || bytes[4] != 0x0D.toByte() || bytes[5] != 0x0A.toByte()
            || bytes[6] != 0x1A.toByte() || bytes[7] != 0x0A.toByte()
        ) throw PackException("not a png: $name")
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte()
            || bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) throw PackException("no IHDR: $name")
        fun u32(i: Int) = (bytes[i].toInt() and 0xFF).let {
            (it shl 24) or (bytes[i + 1].toInt() and 0xFF shl 16) or
                (bytes[i + 2].toInt() and 0xFF shl 8) or (bytes[i + 3].toInt() and 0xFF)
        }
        val w = u32(16)
        val h = u32(20)
        if (w == 0 || h == 0 || w > 2048 || h > 2048)
            throw PackException("png dims out of range: $name ${w}x$h")
        val depth = bytes[24].toInt() and 0xFF
        val colorType = bytes[25].toInt() and 0xFF
        val interlace = bytes[28].toInt() and 0xFF
        if (depth !in intArrayOf(1, 2, 4, 8, 16) || colorType !in intArrayOf(0, 2, 3, 4, 6)
            || interlace != 0
        ) throw PackException("unsupported png: $name depth=$depth colorType=$colorType")
    }

    /**
     * Parses the pack catalog (extended schema). [packDir] roots the recipe's
     * asset paths when recipes come from disk (null = resource-relative, used
     * only by [verify]). Throws [PackException] on any structural violation.
     */
    fun parseCatalog(text: String, packDir: String?): GradePackCatalog {
        val root = JSONObject(text)
        val version = root.optInt("version", 0)
        if (version <= 0) throw PackException("catalog version must be >= 1")
        val filters = root.optJSONArray("filters") ?: throw PackException("no filters[]")
        val entries = ArrayList<GradePackEntry>(filters.length())
        val seen = HashSet<String>()
        for (i in 0 until filters.length()) {
            try {
                val f = filters.getJSONObject(i)
                val id = f.optString("id")
                if (id.isEmpty() || !id.matches(Regex("[a-z0-9_]{1,32}"))) continue
                if (!seen.add(id)) continue // duplicate: first wins
                val recipe = GradeCatalog.parse(
                    """{"filters":[${f.toString()}]}"""
                )[id] ?: continue // malformed grade part: skipped, never fatal
                val name = f.optString("name").takeIf { it.isNotEmpty() }
                    ?.takeIf { it.length <= 24 } // label fits the carousel chip
                val tintObj = f.optJSONObject("tint")
                val tintHex = tintObj?.optString("color").orEmpty()
                val tintColor = tintHex.takeIf { it.matches(Regex("#[0-9a-fA-F]{6,8}")) }
                    ?.let { parseHexArgb(it) }
                val tintAlpha = tintObj?.run {
                    if (has("alpha")) optDouble("alpha", 0.0).toFloat()
                        .takeIf { it >= 0f && it <= 1f } else null
                }
                val matrixArr = f.optJSONArray("matrix")
                var matrix: FloatArray? = null
                if (matrixArr != null) {
                    if (matrixArr.length() != 20) throw PackException("${id}: matrix must have 20 floats")
                    matrix = FloatArray(20) { j -> matrixArr.optDouble(j).toFloat() }
                    matrix.forEach { if (!it.isFinite()) throw PackException("${id}: non-finite matrix") }
                }
                entries.add(GradePackEntry(id, name, recipe.copy(packDir = packDir), tintColor, tintAlpha, matrix))
            } catch (t: PackException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "pack filter entry skipped", t)
            }
        }
        return GradePackCatalog(version, entries)
    }

    class PackException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ------------------------------------------------------------------
    // Android disk layer: staged directories + atomic pointer.
    // ------------------------------------------------------------------

    private fun packRoot(context: Context) = File(context.filesDir, "grade_pack")

    /** Directory of the installed pack's assets, or null when none. */
    fun activeDir(context: Context): String? {
        val ptr = File(packRoot(context), "active")
        val name = runCatching { ptr.readText().trim() }.getOrNull() ?: return null
        val dir = File(packRoot(context), name)
        // Trust chain: the ptr is only ever written by swap() AFTER a full
        // verify, so a pointer to a real dir implies a validated pack.
        return if (dir.isDirectory) dir.absolutePath else null
    }

    /** Installed pack version, or 0 when none/invalid (drives the 304 GET). */
    fun installedVersion(context: Context): Int {
        val dir = activeDir(context) ?: return 0
        val text = runCatching { File(dir, CATALOG_ENTRY).readText() }.getOrNull()
            ?: return 0
        return runCatching {
            parseCatalog(text, "").version
        }.getOrDefault(0)
    }

    /** Full pack state, or null when none installed. */
    fun loadActivePack(context: Context): GradePackCatalog? {
        val dir = activeDir(context) ?: return null
        val text = runCatching { File(dir, CATALOG_ENTRY).readText() }.getOrNull() ?: return null
        return runCatching { parseCatalog(text, dir) }
            .onFailure { Log.w(TAG, "installed pack invalid — ignoring", it) }
            .getOrNull()
    }

    /** Atomically installs a verified pack (staged dir + pointer rename). */
    fun swap(context: Context, pack: VerifiedPack) {
        val root = packRoot(context)
        val dir = File(root, "v${pack.version}")
        if (dir.exists()) dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("cannot stage ${dir.path}")
        for ((name, bytes) in pack.files) {
            val out = File(dir, name)
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }
        val ptr = File(root, "active")
        val tmp = File(root, "active.tmp")
        tmp.writeText("v${pack.version}")
        if (!tmp.renameTo(ptr)) {
            // Some filesystems refuse rename-over-existing; fall back.
            ptr.delete()
            check(tmp.renameTo(ptr)) { "active pointer write failed" }
        }
        // Prune other versions + staging leftovers; keep the active dir.
        root.listFiles()?.forEach { f ->
            if (f != dir && f.isDirectory && f.name.startsWith("v") && f.name != "active.tmp")
                f.deleteRecursively()
        }
        Log.i(TAG, "pack v${pack.version} installed (${pack.files.size} files)")
    }

    // ------------------------------------------------------------------
    // Network fetch (loopback/HTTPS by contract).
    // ------------------------------------------------------------------

    /**
     * Downloads+installs a newer pack. Returns the installed version
     * (>0), 0 when the server said not-modified, -1 on any failure.
     */
    suspend fun fetch(context: Context, url: String, installedVersion: Int): Int =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("X-Grade-Pack-Version", "$installedVersion")
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 304 -> 0
                        !resp.isSuccessful -> {
                            Log.w(TAG, "pack fetch failed: HTTP ${resp.code}")
                            -1
                        }
                        else -> {
                            val body = resp.body?.bytes()
                                ?: throw IOException("empty body")
                            val verified = verify(body)
                            swap(context, verified)
                            verified.version
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pack refresh skipped", t)
                -1
            }
        }
}
