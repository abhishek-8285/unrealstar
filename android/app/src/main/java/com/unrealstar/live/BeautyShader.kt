package com.unrealstar.live

/**
 * Shared GLSL for the upgraded beauty stage: adaptive smoothing composite
 * (Banuba `smooth.frag` port), eyes/teeth retouch, and look knobs.
 *
 * REA provenance: `rea-out/camfilter/banuba/extraction/Beauty/` (decompiled
 * from Tango's shipped effect package) and `TANGO_BANUBA_BEAUTY.md` §2.3.
 * Text is the exact twin of [BeautyMath]'s CPU formulas (unit-tested).
 *
 * Deviations from the shipped original (documented, deliberate):
 *  - Banuba gates the composite with a segmentation-NN skin mask; CamStar has
 *    no NN, so [beautySkin] is a soft YCbCr skin-tone window. Conservative by
 *    design: non-skin (or unrealistic) colors fall out, lips/eyes stay crisp.
 *  - Banuba runs blur, composite and the legacy bilateral as separate passes;
 *    here they are merged into one pass (the blurred buffer doubles as the
 *    composite's `gauss` and the soft-skin source).
 *  - The shipped build binds no whitening LUTs (sharpen-only); the eyes/teeth
 *    stage below whitens directly instead of sampling a 64^3 flipbook — same
 *    visible goal, no extra sampler units.
 *
 * Uniform extras (floats; no new texture units):
 *   uSharpen/uContrast/uSaturation/uRuddy/uEyeTeeth (0..1 sliders), and
 *   uMouth = (centerX, centerY, radius, enabled) in view/frame fractions.
 */
object BeautyShader {

    const val SMOOTH_EPS = 0.003f

    /** Banuba: UnsharpAmount = 0.05 s, UnsharpThreshold = 0.1 s, alpha = 0.4 s. */
    const val UNSHARP_AMOUNT = 0.05f
    const val UNSHARP_THRESHOLD = 0.1f
    const val MASK_ALPHA = 0.4f

    /** Folded `soft_skin.frag` blend factor (their legacy pass constant). */
    const val SOFT_SKIN = 0.7f

    /** Global sharpen kernel mix stays half of the slider (kernel is strong). */
    const val SHARPEN_SCALE = 0.5f

    fun uniformDecls(): String = buildString {
        append("uniform float uSharpen;\nuniform float uContrast;\n")
        append("uniform float uSaturation;\nuniform float uRuddy;\nuniform float uEyeTeeth;\n")
        append("uniform vec4 uMouth;\n")
    }

    /** Shared math: skin window, adaptive composite, whiten, sharpen, knobs. */
    const val HELPERS = "" +
        // Soft skin-tone weight in [0,1]: the stand-in for Banuba's NN mask.
        // Classic YCbCr skin window (Cb ~0.30..0.53, Cr ~0.50..0.73) with a
        // luma floor; gray/blue/green fall out, warm skin passes.
        "float beautySkin(vec3 c) {\n" +
        "  float cb = dot(c, vec3(-0.169, -0.331, 0.5)) + 0.5;\n" +
        "  float cr = dot(c, vec3(0.5, -0.419, -0.081)) + 0.5;\n" +
        "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "  float w = smoothstep(0.30, 0.36, cb) * (1.0 - smoothstep(0.47, 0.53, cb));\n" +
        "  w *= smoothstep(0.50, 0.55, cr) * (1.0 - smoothstep(0.68, 0.73, cr));\n" +
        "  w *= smoothstep(0.10, 0.22, l);\n" +
        "  return w;\n" +
        "}\n" +
        // Banuba smooth.frag, line-for-line (see object KDoc for the mask swap).
        // val2 = steep window over |gb| of original vs blur: vivid content
        // (lips, eyes, edges) keeps the original, flat skin gets the curve.
        "vec3 beautyComposite(vec3 rgb, vec3 blurred, float s) {\n" +
        "  float skin = beautySkin(rgb);\n" +
        "  vec3 curve = clamp(-0.391731 * rgb * rgb + 1.4554 * rgb - 0.0637, vec3(0.0), vec3(1.0));\n" +
        "  float val2 = clamp(length(rgb.gb) - length(blurred.gb) + 0.5, 0.0, 1.0);\n" +
        "  vec2 case1 = vec2(val2, 1.0 - val2);\n" +
        "  case1 *= case1;\n" +
        "  case1 *= case1;\n" +
        "  case1 = case1 * case1 * 128.0;\n" +
        "  val2 = mix(1.0 - case1.y, case1.x, step(val2, 0.5));\n" +
        "  vec3 origCurve = mix(curve, rgb, val2);\n" +
        "  vec3 difference = blurred - rgb;\n" +
        "  float mixAmount = step(0.01 * s * s, dot(difference, difference));\n" +
        "  vec3 comp = origCurve + (mixAmount * 0.05 * s) * difference;\n" +
        "  comp = mix(comp, blurred, skin * 0.7 * s);\n" +
        "  return mix(rgb, comp, skin * 0.4 * s);\n" +
        "}\n" +
        // Eyes/teeth whitening: desaturate toward luma + lift toward white.
        // NB: 'flat' is a reserved keyword in GLSL ES — keep this name valid.
        "vec3 beautyWhiten(vec3 c, float amount) {\n" +
        "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "  vec3 desat = mix(c, vec3(l), 0.6);\n" +
        "  vec3 lifted = desat + (1.0 - desat) * 0.28;\n" +
        "  return mix(c, lifted, amount);\n" +
        "}\n" +
        // Banuba retouch.frag sharpen: 5c - 4 diagonal neighbours (diagonals
        // summed by the caller; taps are per-shader because the sampler types
        // differ between the live (OES) and bake (2D) paths).
        "vec3 beautySharpen(vec3 c, vec3 diagSum, float amount) {\n" +
        "  vec3 sharp = clamp(5.0 * c - diagSum, vec3(0.0), vec3(1.0));\n" +
        "  return mix(c, sharp, amount);\n" +
        "}\n" +
        // Saturation / contrast / ruddy: cheap look knobs, skin-gated warmth.
        "vec3 beautyKnobs(vec3 c) {\n" +
        "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "  c = mix(vec3(l), c, 1.0 + 0.6 * uSaturation);\n" +
        "  c = (c - 0.5) * (1.0 + 0.5 * uContrast) + 0.5;\n" +
        "  c += uRuddy * beautySkin(c) * vec3(0.09, 0.015, -0.045);\n" +
        "  return clamp(c, vec3(0.0), vec3(1.0));\n" +
        "}\n"

    /**
     * Eyes/teeth retouch statements. [wuvVar] is the warped frame UV the eye
     * uniforms are expressed in; [rgbVar] the working color. Low-saturation
     * coverage damping keeps lips/iris from being desaturated (only the
     * near-white sclera/teeth respond).
     */
    fun retouchCall(wuvVar: String, rgbVar: String): String = buildString {
        append("  if (uEyeTeeth > 0.003 && uFace.w > 0.5) {\n")
        append("    float er = max(uFace.z, 0.05) * 0.18;\n")
        append("    float sat = max(max($rgbVar.r, $rgbVar.g), $rgbVar.b) - min(min($rgbVar.r, $rgbVar.g), $rgbVar.b);\n")
        append("    float damp = 1.0 - clamp(sat * 3.0, 0.0, 1.0);\n")
        append("    float t = uEyeTeeth;\n")
        for (eye in listOf("uEyeL", "uEyeR")) {
            append("    if ($eye.z > 0.5) {\n")
            append("      float k = 1.0 - smoothstep(0.0, er, distance($wuvVar, $eye.xy));\n")
            append("      $rgbVar = mix($rgbVar, beautyWhiten($rgbVar, t), k * damp);\n")
            append("    }\n")
        }
        append("    if (uMouth.w > 0.5) {\n")
        append("      float km = 1.0 - smoothstep(0.0, uMouth.z, distance($wuvVar, uMouth.xy));\n")
        append("      $rgbVar = mix($rgbVar, beautyWhiten($rgbVar, t), km * damp);\n")
        append("    }\n")
        append("  }\n")
    }

    /**
     * Global sharpen statements (Banuba retouch kernel, full-frame knob).
     * [uvVar] is the camera-texture UV the four diagonal taps read at one
     * texel ([uTexel]); works on both paths because the sampler call form is
     * identical (`texture2D(uTex, ...)`, 2D vs OES only differs at link time).
     */
    fun sharpenCall(uvVar: String, rgbVar: String): String =
        "  if (uSharpen > 0.003) {\n" +
            "    vec3 dn = texture2D(uTex, $uvVar + vec2(uTexel.x, uTexel.y)).rgb\n" +
            "            + texture2D(uTex, $uvVar + vec2(uTexel.x, -uTexel.y)).rgb\n" +
            "            + texture2D(uTex, $uvVar + vec2(-uTexel.x, uTexel.y)).rgb\n" +
            "            + texture2D(uTex, $uvVar + vec2(-uTexel.x, -uTexel.y)).rgb;\n" +
            "    $rgbVar = beautySharpen($rgbVar, dn, uSharpen * $SHARPEN_SCALE);\n" +
            "  }\n"

    /** Look-knob statements (saturation/contrast/ruddy). */
    fun knobsCall(rgbVar: String): String =
        "  if (uSaturation > 0.003 || uContrast > 0.003 || uRuddy > 0.003) {\n" +
            "    $rgbVar = beautyKnobs($rgbVar);\n" +
            "  }\n"
}
