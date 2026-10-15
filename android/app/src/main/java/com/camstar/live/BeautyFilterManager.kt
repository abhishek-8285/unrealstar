package com.camstar.live

import android.graphics.*
import com.camstar.live.models.BeautyCategory
import com.camstar.live.models.BeautyItem

class BeautyFilterManager {

    val categories: List<BeautyCategory> = listOf(
        BeautyCategory(
            category_id = 402,
            name = "Lipstick",
            items = (1..10).map { i ->
                BeautyItem(
                    id = "matte_$i",
                    name = "Matte #$i",
                    tag = "MASK_LUT",
                    blend_mode = "LUT",
                    default_intensity = 0.65f
                )
            }
        ),
        BeautyCategory(
            category_id = 403,
            name = "Blush",
            items = listOf("a", "b", "c", "d", "e", "f", "g").mapIndexed { idx, ch ->
                BeautyItem(
                    id = "blush_$ch",
                    name = "Rosy $ch".uppercase(),
                    tag = "BLUSH",
                    blend_mode = "Multiply",
                    default_intensity = 0.50f
                )
            }
        ),
        BeautyCategory(
            category_id = 405,
            name = "Eyebrows",
            items = (1..8).map { i ->
                BeautyItem(
                    id = "brow_$i",
                    name = "Arch #$i",
                    tag = "EYE_BROW",
                    blend_mode = "Multiply",
                    default_intensity = 0.60f
                )
            }
        ),
        BeautyCategory(
            category_id = 406,
            name = "Eyeshadow",
            items = (1..11).map { i ->
                BeautyItem(
                    id = "shadow_$i",
                    name = "Smokey #$i",
                    tag = "EYE_SHADOW",
                    blend_mode = "Multiply",
                    default_intensity = 0.55f
                )
            }
        ),
        BeautyCategory(
            category_id = 408,
            name = "Eyelashes",
            items = (1..3).map { i ->
                BeautyItem(
                    id = "lash_$i",
                    name = "Volume #$i",
                    tag = "EYE_LASH",
                    blend_mode = "Multiply",
                    default_intensity = 0.70f
                )
            }
        )
    )

    var selectedItem: BeautyItem? = categories[0].items[0]
    var intensity: Float = 0.60f // 0.0 to 1.0

    /**
     * Composites the active segmentation matte and blend mode onto a preview bitmap
     */
    fun applyCompositeFilter(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val item = selectedItem ?: return bitmap
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (item.tag) {
            "MASK_LUT" -> {
                // Lipstick LUT overlay on lips anchor [310, 714] scaled to canvas
                paint.color = Color.argb((intensity * 255).toInt(), 230, 40, 80)
                val cx = width * 0.50f
                val cy = height * 0.68f
                val rx = width * 0.15f
                val ry = height * 0.06f
                canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
            }
            "BLUSH" -> {
                // Cheeks Multiply blend
                paint.color = Color.argb((intensity * 180).toInt(), 255, 120, 130)
                val cy = height * 0.55f
                val leftCx = width * 0.30f
                val rightCx = width * 0.70f
                val r = width * 0.12f
                canvas.drawCircle(leftCx, cy, r, paint)
                canvas.drawCircle(rightCx, cy, r, paint)
            }
            "EYE_BROW" -> {
                // Eyebrow darkening blend
                paint.color = Color.argb((intensity * 220).toInt(), 45, 30, 25)
                paint.strokeWidth = 14f
                paint.style = Paint.Style.STROKE
                val browY = height * 0.40f
                canvas.drawLine(width * 0.25f, browY + 10, width * 0.42f, browY, paint)
                canvas.drawLine(width * 0.58f, browY, width * 0.75f, browY + 10, paint)
            }
            "EYE_SHADOW" -> {
                // Eyeshadow blend
                paint.color = Color.argb((intensity * 190).toInt(), 180, 80, 140)
                val eyeY = height * 0.45f
                val leftX = width * 0.33f
                val rightX = width * 0.67f
                canvas.drawCircle(leftX, eyeY, width * 0.08f, paint)
                canvas.drawCircle(rightX, eyeY, width * 0.08f, paint)
            }
            "EYE_LASH" -> {
                // Eyelash stroke accents
                paint.color = Color.argb((intensity * 240).toInt(), 20, 20, 20)
                paint.strokeWidth = 6f
                paint.style = Paint.Style.STROKE
                val lashY = height * 0.44f
                canvas.drawLine(width * 0.28f, lashY, width * 0.38f, lashY - 8, paint)
                canvas.drawLine(width * 0.62f, lashY - 8, width * 0.72f, lashY, paint)
            }
        }

        return bitmap
    }
}
