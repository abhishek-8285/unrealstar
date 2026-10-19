package com.unrealstar.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Beauty-makeup catalog: real segmentation masks shipped from the
 * reverse-engineered RangLive packs (see scripts/build_beauty_assets.py and
 * docs/BEAUTY_PIPELINE_SPECIFICATION.md).
 *
 * Every item zip inside a category is byte-identical, so CamStar ships one
 * canonical mask per category (anchored on the pack's 750x1334 canvas) and
 * reproduces per-item variation as a color [tint] applied at render time.
 * [tint] is a per-channel multiplier over the shipped texture; (1, 1, 1)
 * renders exactly like the shipped pack.
 */
data class MatteItem(
    val id: String,
    val name: String,
    /** UI swatch color. */
    val color: Int,
    val tint: FloatArray,
    /** Default application strength 0..1. */
    val strength: Float
)

/** One makeup category backed by a real alpha-masked texture. */
data class MatteCategory(
    val id: Int,
    val name: String,
    val tag: String,
    /** True = lipstick LUT recolor; false = multiply blend. */
    val lutMode: Boolean,
    /** Mask anchor on the 750x1334 canvas, in canvas px. */
    val anchorX: Float,
    val anchorY: Float,
    /** Uniform scale applied to the shipped texture, from the pack metadata. */
    val imageScale: Float,
    val maskAsset: String,
    val lutAsset: String?,
    val items: List<MatteItem>
)

/** A user's active makeup choice. */
data class MatteSelection(val category: MatteCategory, val item: MatteItem, val intensity: Float)

/** A selection with resolvable pixels — what the render paths consume. */
data class MatteLayer(
    val category: MatteCategory,
    val item: MatteItem,
    val intensity: Float,
    val mask: Bitmap,
    val lut: Bitmap?
)

/** Canonical landmark positions on the mask canvas, in canvas px. */
class CanonicalFace(
    val canvasW: Float,
    val canvasH: Float,
    val eyeLx: Float, val eyeLy: Float,
    val eyeRx: Float, val eyeRy: Float,
    val mouthX: Float, val mouthY: Float
) {
    companion object {
        /** Fallback straight from the analysed pack (see build script). */
        val EMPTY = CanonicalFace(750f, 1334f, 263.2f, 531.7f, 504.8f, 531.7f, 386.1f, 772.8f)
    }
}

class MatteCatalog private constructor(
    val face: CanonicalFace,
    val categories: List<MatteCategory>
) {

    companion object {
        private const val TAG = "MatteCatalog"
        private const val PATH = "beauty/catalog.json"

        /** Never throws; makeup silently disables when assets are missing. */
        fun load(context: Context): MatteCatalog = try {
            val text = context.assets.open(PATH).bufferedReader().use { it.readText() }
            parse(text)
        } catch (t: Throwable) {
            Log.w(TAG, "beauty catalog unavailable; makeup disabled", t)
            MatteCatalog(CanonicalFace.EMPTY, emptyList())
        }

        fun parse(text: String): MatteCatalog {
            val root = JSONObject(text)
            val canvas = root.getJSONArray("canvas")
            val f = root.getJSONObject("face")
            val eyeL = f.getJSONArray("eyeL")
            val eyeR = f.getJSONArray("eyeR")
            val mouth = f.getJSONArray("mouth")
            val face = CanonicalFace(
                canvasW = canvas.getDouble(0).toFloat(),
                canvasH = canvas.getDouble(1).toFloat(),
                eyeLx = eyeL.getDouble(0).toFloat(), eyeLy = eyeL.getDouble(1).toFloat(),
                eyeRx = eyeR.getDouble(0).toFloat(), eyeRy = eyeR.getDouble(1).toFloat(),
                mouthX = mouth.getDouble(0).toFloat(), mouthY = mouth.getDouble(1).toFloat()
            )
            val cats = root.getJSONArray("categories")
            val categories = ArrayList<MatteCategory>(cats.length())
            for (i in 0 until cats.length()) {
                val c = cats.getJSONObject(i)
                val anchor = c.getJSONArray("anchor")
                val items = c.getJSONArray("items")
                val parsed = ArrayList<MatteItem>(items.length())
                for (j in 0 until items.length()) {
                    val it = items.getJSONObject(j)
                    val tint = it.getJSONArray("tint")
                    parsed += MatteItem(
                        id = it.getString("id"),
                        name = it.getString("name"),
                        color = it.getString("color").toColorInt(),
                        tint = floatArrayOf(
                            tint.getDouble(0).toFloat(),
                            tint.getDouble(1).toFloat(),
                            tint.getDouble(2).toFloat()
                        ),
                        strength = it.getDouble("strength").toFloat().coerceIn(0f, 1f)
                    )
                }
                categories += MatteCategory(
                    id = c.getInt("id"),
                    name = c.getString("name"),
                    tag = c.getString("tag"),
                    lutMode = c.getString("blend").equals("LUT", ignoreCase = true),
                    anchorX = anchor.getDouble(0).toFloat(),
                    anchorY = anchor.getDouble(1).toFloat(),
                    imageScale = c.getDouble("imageScale").toFloat(),
                    maskAsset = "beauty/" + c.getString("mask"),
                    lutAsset = c.optString("lut", "").takeIf { it.isNotEmpty() }?.let { "beauty/$it" },
                    items = parsed
                )
            }
            return MatteCatalog(face, categories)
        }
    }
}

/** Decoded mask/LUT bitmaps, cached process-wide (6 small textures total). */
object MatteAssets {

    private const val TAG = "MatteAssets"
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun bitmap(context: Context, assetPath: String): Bitmap? {
        cache[assetPath]?.let { if (!it.isRecycled) return it }
        return try {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
                ?.also { cache[assetPath] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "mask decode failed: $assetPath", t)
            null
        }
    }

    /** Resolves a selection to pixels, or null when the selection or assets are missing. */
    fun resolve(context: Context, selection: MatteSelection?): MatteLayer? {
        val sel = selection ?: return null
        val mask = bitmap(context, sel.category.maskAsset) ?: return null
        val lut = sel.category.lutAsset?.let { bitmap(context, it) }
        if (sel.category.lutMode && lut == null) return null
        return MatteLayer(sel.category, sel.item, sel.intensity, mask, lut)
    }
}
