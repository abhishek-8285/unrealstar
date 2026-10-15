package com.unrealstar.live

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
    val anchorY: Float = 0.32f
)

val BUILTIN_STICKERS: List<FaceSticker> = listOf(
    FaceSticker("none", "None", ""),
    FaceSticker("ears", "Kitty", "\uD83D\uDC31", 0.28f),
    FaceSticker("halo", "Halo", "\uD83D\uDE07", 0.26f),
    FaceSticker("glasses", "Shades", "\uD83D\uDE0E", 0.42f),
    FaceSticker("clown", "Clown", "\uD83E\uDD21", 0.40f),
    FaceSticker("crown", "Crown", "\uD83D\uDC51", 0.26f),
    FaceSticker("hearts", "Love", "\uD83D\uDE0D", 0.40f),
    FaceSticker("star", "Star", "\uD83E\uDD29", 0.38f),
    FaceSticker("fire", "Fire", "\uD83D\uDD25", 0.30f),
    FaceSticker("flower", "Flower", "\uD83C\uDF38", 0.28f)
)
