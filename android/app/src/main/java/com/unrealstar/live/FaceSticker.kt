package com.unrealstar.live

import android.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * Face sticker model.
 *
 * Phase 1: center-anchored overlay above the preview (no face tracking).
 * The [anchorY] fraction positions the emoji where a face typically is
 * (0.32 = forehead for ears/halos, 0.45 = eyes for glasses).
 * Phase 2 hook: feed ML Kit face landmarks into the overlay position.
 */
data class FaceSticker(
    val id: String,
    val name: String,
    val emoji: String,
    /** Vertical anchor as fraction of preview height. */
    val anchorY: Float = 0.32f,
    /** Picker-row swatch accent; [Color.TRANSPARENT] = leave the default. */
    val tintColor: Int = Color.TRANSPARENT
)

val BUILTIN_STICKERS: List<FaceSticker> = listOf(
    FaceSticker("none", "None", ""),
    FaceSticker("ears", "Kitty", "\uD83D\uDC31", 0.28f, "#F9A8D4".toColorInt()),
    FaceSticker("halo", "Halo", "\uD83D\uDE07", 0.26f, "#FEF3C7".toColorInt()),
    FaceSticker("glasses", "Shades", "\uD83D\uDE0E", 0.42f, "#94A3B8".toColorInt()),
    FaceSticker("clown", "Clown", "\uD83E\uDD21", 0.40f, "#FB923C".toColorInt()),
    FaceSticker("crown", "Crown", "\uD83D\uDC51", 0.26f, "#FACC15".toColorInt()),
    FaceSticker("hearts", "Love", "\uD83D\uDE0D", 0.40f, "#FB7185".toColorInt()),
    FaceSticker("star", "Star", "\uD83E\uDD29", 0.38f, "#FCD34D".toColorInt()),
    FaceSticker("fire", "Fire", "\uD83D\uDD25", 0.30f, "#F97316".toColorInt()),
    FaceSticker("flower", "Flower", "\uD83C\uDF38", 0.28f, "#86EFAC".toColorInt())
)
