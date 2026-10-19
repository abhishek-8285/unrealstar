package com.unrealstar.live

/**
 * The shared GLSL color-grade stage + binding constants, appended after the
 * matte loop in both GPU paths (the live [GpuPreviewView] shader and the still
 * baker [GpuFilterProcessor] — same stage order: curve|matrix → whiten →
 * overlays → tint).
 *
 * The shader text is the exact twin of [GradeMath]'s CPU formulas:
 *  - 256x3 curve strip, nearest-texel lookup at `floor(c*255)` — 8-bit camera
 *    inputs land exactly on texel centers, so GPU == CPU LUT indexing;
 *  - multiply / screen / overlay / soft-light blends with `mix(..., opacity)`.
 *
 * Texture units: camera (0) + 5 matte slots (1..5) + matte LUT (6) + curve (7)
 * + 3 overlay slots (8..10) = 11. GPU-mode devices are ES3 (>= 16 units
 * guaranteed); the still baker falls back to the CPU bake if the driver
 * reports fewer units.
 */
object GradeShader {

    const val MAX_OVERLAYS = 3
    const val CURVE_UNIT = 7
    const val OVL_UNIT_BASE = 8

    // Uniform declarations appended with the other grade uniforms.
    fun uniformDecls(): String = buildString {
        append("uniform sampler2D uCurveTex;\n")
        append("uniform float uCurveOn;\nuniform float uCurveStrength;\n")
        for (i in 0 until MAX_OVERLAYS) {
            append("uniform sampler2D uOvlTex$i;\n")
            // vec4: x = blend-mode index, y = opacity.
            append("uniform vec4 uOvlA$i;\n")
        }
    }

    /** GLSL functions: curve lookup + blend modes + one unrolled slot pass. */
    const val HELPERS = "" +
        "vec3 gradeBlend(float mode, vec3 base, vec3 src) {\n" +
        "  vec3 b = clamp(base, vec3(0.0), vec3(1.0));\n" +
        "  vec3 s = clamp(src, vec3(0.0), vec3(1.0));\n" +
        "  vec3 x2s = 2.0 * s;\n" +
        "  vec3 r = b * s;\n" +                                             // 0 multiply
        "  if (mode > 0.5) r = vec3(1.0) - (vec3(1.0) - b) * (vec3(1.0) - s);\n" + // 1 screen
        "  if (mode > 1.5) r = mix(2.0 * b * s,\n" +                        // 2 overlay
        "      1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(vec3(0.5), b));\n" +
        "  if (mode > 2.5) r = mix(2.0 * b * s + b * b * (vec3(1.0) - x2s),\n" +  // 3 soft-light
        "      b + (x2s - vec3(1.0)) * (sqrt(clamp(b, vec3(0.0), vec3(1.0))) - b),\n" +
        "      step(vec3(0.5), s));\n" +
        "  return r;\n" +
        "}\n" +
        "vec3 gradeCurve(vec3 rgb, sampler2D curve, float strength) {\n" +
        "  vec3 c = clamp(rgb, vec3(0.0), vec3(1.0));\n" +
        "  vec3 o;\n" +
        "  o.r = texture2D(curve, vec2((floor(c.r * 255.0) + 0.5) / 256.0, 0.5 / 3.0)).r;\n" +
        "  o.g = texture2D(curve, vec2((floor(c.g * 255.0) + 0.5) / 256.0, 1.5 / 3.0)).g;\n" +
        "  o.b = texture2D(curve, vec2((floor(c.b * 255.0) + 0.5) / 256.0, 2.5 / 3.0)).b;\n" +
        "  return mix(rgb, o, strength);\n" +
        "}\n" +
        "vec3 gradeSlot(vec3 rgb, sampler2D ovl, vec4 conf, vec2 suv) {\n" +
        "  if (conf.y <= 0.003) { return rgb; }\n" +
        "  vec3 m = texture2D(ovl, suv).rgb;\n" +
        "  return mix(rgb, gradeBlend(conf.x, rgb, m), conf.y);\n" +
        "}\n"

    /**
     * Stage applications for `main()`. [suvExpr] is the screen-space varying
     * expression for full-screen masks (vignette/glow/grain are screen-space
     * like Instagram's blend passes, not camera-frame space).
     */
    fun curveCall(rgbVar: String): String =
        "  if (uCurveOn > 0.5) { $rgbVar = gradeCurve($rgbVar, uCurveTex, uCurveStrength); }\n"

    fun overlayCalls(suvExpr: String, rgbVar: String): String = buildString {
        for (i in 0 until MAX_OVERLAYS) {
            append("  $rgbVar = gradeSlot($rgbVar, uOvlTex$i, uOvlA$i, $suvExpr);\n")
        }
    }
}
