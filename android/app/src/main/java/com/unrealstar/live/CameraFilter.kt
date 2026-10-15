package com.unrealstar.live

import android.graphics.Color

/**
 * Instagram-style full-frame color filter.
 *
 * Dual representation so [FilterEngine] can pick a path per device:
 * - Lightweight path: [tintColor] + [tintAlpha] drawn on a fullscreen overlay
 *   View above PreviewView. Zero per-frame cost, works on any device.
 * - GPU / capture path: [colorMatrix] (20 floats, row-major 4x5) applied via
 *   ColorMatrixColorFilter for still captures, and [fragmentShader] reserved
 *   for the future GLSurfaceView pipeline.
 */
data class CameraFilter(
    val id: String,
    val name: String,
    /** 20-float ColorMatrix, row-major 4x5. Identity = Normal. */
    val colorMatrix: FloatArray,
    /** Overlay tint for the lightweight PreviewView path. */
    val tintColor: Int,
    /** 0.0-1.0 overlay strength on the lightweight path. */
    val tintAlpha: Float,
    /** GLSL snippet for the future GPU pipeline (kept alongside the matrix). */
    val fragmentShader: String = DEFAULT_SHADER
) {
    companion object {
        const val DEFAULT_SHADER = "gl_FragColor = texture2D(tex, uv) * uColorMul + uColorAdd;"
    }
}

private fun matrix(
    r: Float = 1f, g: Float = 1f, b: Float = 1f,
    brightness: Float = 0f, saturation: Float = 1f,
    warmth: Float = 0f
): FloatArray {
    // Compose saturation + channel gains + brightness/warmth into a 4x5 matrix.
    val inv = 1f - saturation
    val lr = 0.213f * inv
    val lg = 0.715f * inv
    val lb = 0.072f * inv
    return floatArrayOf(
        lr + saturation * r, lg * r, lb * r, 0f, brightness + warmth * 18f,
        lr * g, lg * g + saturation * g, lb * g, 0f, brightness + warmth * 6f,
        lr * b, lg * b, lb * b + saturation * b, 0f, brightness - warmth * 14f,
        0f, 0f, 0f, 1f, 0f
    )
}

/** 12 Instagram-style looks + Normal. Order matches the swipe bar. */
val INSTAGRAM_FILTERS: List<CameraFilter> = listOf(
    CameraFilter("normal", "Normal",
        floatArrayOf(1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 0f,0f,0f,1f,0f),
        Color.TRANSPARENT, 0f),
    CameraFilter("clarendon", "Clarendon",
        matrix(r = 1.08f, b = 1.12f, saturation = 1.25f, brightness = 4f),
        Color.parseColor("#3B82F6"), 0.10f),
    CameraFilter("juno", "Juno",
        matrix(r = 1.10f, g = 1.04f, saturation = 1.18f, warmth = 0.35f, brightness = 3f),
        Color.parseColor("#F59E0B"), 0.12f),
    CameraFilter("gingham", "Gingham",
        matrix(saturation = 0.72f, brightness = 14f),
        Color.parseColor("#F5F0E6"), 0.16f),
    CameraFilter("lark", "Lark",
        matrix(g = 1.06f, saturation = 0.9f, brightness = 8f),
        Color.parseColor("#A7F3D0"), 0.12f),
    CameraFilter("moon", "Moon",
        floatArrayOf(0.33f,0.33f,0.33f,0f,12f, 0.33f,0.33f,0.33f,0f,12f, 0.33f,0.33f,0.33f,0f,14f, 0f,0f,0f,1f,0f),
        Color.parseColor("#9CA3AF"), 0.08f),
    CameraFilter("sepia", "Sepia",
        floatArrayOf(0.393f,0.769f,0.189f,0f,0f, 0.349f,0.686f,0.168f,0f,0f, 0.272f,0.534f,0.131f,0f,0f, 0f,0f,0f,1f,0f),
        Color.parseColor("#92400E"), 0.14f),
    CameraFilter("aden", "Aden",
        matrix(r = 1.04f, g = 1.0f, b = 0.94f, saturation = 0.85f, warmth = 0.5f, brightness = 6f),
        Color.parseColor("#FBBF24"), 0.14f),
    CameraFilter("valencia", "Valencia",
        matrix(r = 1.08f, saturation = 1.1f, warmth = 0.6f, brightness = 8f),
        Color.parseColor("#FDE68A"), 0.16f),
    CameraFilter("xpro", "X-Pro",
        matrix(r = 1.12f, b = 0.94f, saturation = 1.3f, brightness = -4f),
        Color.parseColor("#7C2D12"), 0.12f),
    CameraFilter("lofi", "Lo-Fi",
        matrix(saturation = 1.35f, brightness = -2f, warmth = 0.15f),
        Color.parseColor("#DC2626"), 0.08f),
    CameraFilter("inkwell", "Inkwell",
        floatArrayOf(0.3f,0.6f,0.1f,0f,8f, 0.3f,0.6f,0.1f,0f,8f, 0.3f,0.6f,0.1f,0f,8f, 0f,0f,0f,1f,0f),
        Color.TRANSPARENT, 0f),
    CameraFilter("nashville", "Nashville",
        matrix(r = 1.06f, b = 0.96f, saturation = 0.9f, warmth = 0.7f, brightness = 10f),
        Color.parseColor("#FCA5A5"), 0.15f)
)
