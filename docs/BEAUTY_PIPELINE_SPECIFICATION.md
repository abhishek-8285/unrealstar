# CamStar Beauty & Filter Compositing Pipeline Specification

Reverse-engineered from live production assets (`com.srang.tlive` / `rescdn.kktv9.com` / `api.ranglive.com`).

---

## 1. Architectural Overview

The competitor's beauty camera layer is **not** a 3D AR mesh engine. It is a **segmentation-matte overlay compositing pipeline** operating on a standard 750×1334 reference canvas with facial landmark warping and 2D shader blending.

```
+-------------------------------------------------------------------+
|                        Camera Video Frame                         |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|     Face Landmarker (e.g. MediaPipe Face Mesh / 106 Landmarks)    |
+-------------------------------------------------------------------+
       |                  |                |               |
       v                  v                v               v
  [Mouth Bounds]    [Cheek Bounds]   [Brow Bounds]   [Eye Bounds]
       |                  |                |               |
       v                  v                v               v
  (Lipstick)           (Blush)         (Eyebrow)      (Eyeshadow & Lash)
  Tag: MASK_LUT       Tag: BLUSH     Tag: EYE_BROW   Tag: EYE_SHADOW/LASH
  Shader: LUT + Alpha Shader: Multiply Shader: Multiply Shader: Multiply
       |                  |                |               |
       +------------------+----------------+---------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                   Strength Weighted Alpha Blend                   |
|              Intensity Slider (0..100, Default: 60)               |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                       Output Composited Frame                     |
+-------------------------------------------------------------------+
```

---

## 2. Asset Schema & Categories

Fetched at app launch via `FuncTag 50001101`, `key="stMackup"` from `https://api.ranglive.com/meShow/entrance`.

| Category ID | Name | Shipped Items | Tag | Shader Blend Mode | Core Textures |
|---|---|---|---|---|---|
| **402** | `Lipstick` | 20 (`matte_1..10`, `natural_1..10`) | `MASK_LUT` | **LUT** color grade via 2D texture table | `mouth.png` (alpha mask) + `table.png` (LUT) |
| **403** | `Blush` | 7 (`blusha..g`) | `BLUSH` | **Multiply** | `face.png` (cheek mask) |
| **405** | `Eyebrows` | 8 (`browa..h`) | `EYE_BROW` | **Multiply** | `brow.png` (brow mask) |
| **406** | `Eyeshadow` | 11 (`eyeshadowa..l`) | `EYE_SHADOW` | **Multiply** | `eyeshadowa.png` (eyelid mask) |
| **408** | `Eyelashes` | 3 (`eyelash_1..3`) | `EYE_LASH` | **Multiply** | `eyelashb.png` (lash stroke mask) |

---

## 3. Reference Canvas Coordinates & Anchors

All masks are defined on normalized or 750×1334 pixel coordinate frames:

- **Reference Canvas**: `width = 750`, `height = 1334`, `targetFPS = 25`
- **Lipstick Anchor**: `position = [310, 714]`, `imageScale = 0.99`
- **Blush Anchor**: `position = [0, 455]`, `imageScale = 1.0`
- **Eyebrow Anchor**: `position = [144, 440]`, `imageScale = 1.0`
- **Eyeshadow & Lash Anchor**: `position = [200, 483]`, `imageScale = 1.0`
- **Default Strength**: `60` (range 0..100)

---

## 4. Shader Implementation

### 4.1. LUT Blend Mode (Lipstick)

```glsl
// Fragment shader for MASK_LUT
precision mediump float;
varying vec2 v_TexCoord;
uniform sampler2D u_CameraTexture;
uniform sampler2D u_MaskTexture;   // mouth.png (alpha)
uniform sampler2D u_LutTexture;    // table.png (256x16 or 512x512 LUT)
uniform float u_Strength;          // 0.0 to 1.0 (default 0.6)

vec3 lookupLut(sampler2D lut, vec3 color) {
    float blueColor = color.b * 63.0;
    vec2 quad1;
    quad1.y = floor(floor(blueColor) / 8.0);
    quad1.x = floor(blueColor) - (quad1.y * 8.0);
    vec2 quad2;
    quad2.y = floor(ceil(blueColor) / 8.0);
    quad2.x = ceil(blueColor) - (quad2.y * 8.0);
    
    vec2 texPos1 = vec2(quad1.x * 0.125 + 0.5/512.0 + ((0.125 - 1.0/512.0) * color.r),
                        quad1.y * 0.125 + 0.5/512.0 + ((0.125 - 1.0/512.0) * color.g));
    vec2 texPos2 = vec2(quad2.x * 0.125 + 0.5/512.0 + ((0.125 - 1.0/512.0) * color.r),
                        quad2.y * 0.125 + 0.5/512.0 + ((0.125 - 1.0/512.0) * color.g));
    
    vec3 newColor1 = texture2D(lut, texPos1).rgb;
    vec3 newColor2 = texture2D(lut, texPos2).rgb;
    return mix(newColor1, newColor2, fract(blueColor));
}

void main() {
    vec4 baseColor = texture2D(u_CameraTexture, v_TexCoord);
    float maskAlpha = texture2D(u_MaskTexture, v_TexCoord).a;
    
    vec3 lutColor = lookupLut(u_LutTexture, baseColor.rgb);
    vec3 blended = mix(baseColor.rgb, lutColor, maskAlpha * u_Strength);
    gl_FragColor = vec4(blended, baseColor.a);
}
```

### 4.2. Multiply Blend Mode (Blush, Eyebrow, Shadow, Lash)

```glsl
// Fragment shader for Multiply blend
precision mediump float;
varying vec2 v_TexCoord;
uniform sampler2D u_CameraTexture;
uniform sampler2D u_MakeupTexture; // face.png / brow.png / etc.
uniform float u_Strength;          // 0.0 to 1.0

void main() {
    vec4 baseColor = texture2D(u_CameraTexture, v_TexCoord);
    vec4 makeupColor = texture2D(u_MakeupTexture, v_TexCoord);
    
    // Standard multiply blend
    vec3 multiplied = baseColor.rgb * makeupColor.rgb;
    vec3 finalColor = mix(baseColor.rgb, multiplied, makeupColor.a * u_Strength);
    gl_FragColor = vec4(finalColor, baseColor.a);
}
```

---

## 5. CamStar Implementation Status

Implemented in `android/app/src/main/java/com/unrealstar/live/` over the bundled pack assets in `android/app/src/main/assets/beauty/`. This section records what shipped and where the implementation deliberately deviates from the competitor's pipeline.

### 5.1. Shipped assets & per-item variation

- One canonical alpha mask per category, rebuilt from the packs by `scripts/build_beauty_assets.py`: `mouth.png` + `table.png` (64×64 LUT) for lipstick, `face.png` (blush), `brow.png`, `eyeshadow.png`, `eyelash.png`.
- Every item zip inside a category is byte-identical in the packs, so per-item variation ships as `catalog.json` metadata instead of duplicated textures: 10 lipstick items with real icon-extracted tints (applied to the LUT per channel), curated tints for blush / brow / eyeshadow, and 3 lash strengths (0.42 / 0.60 / 0.82).
- `tint` is a per-channel multiplier over the shipped texture ((1, 1, 1) renders exactly like the shipped pack); the UI swatch shows the resulting color.

### 5.2. Anchoring & the coordinate-space contract

- `FaceTracker` reports `FaceGeometry` in upright, front-mirrored frame fractions (ML Kit: both eyes + mouth corners) on the ~400 ms tracking cadence.
- `MatteGeometry` fits a weighted similarity (eyes w=1, mouth-center w=0.5) from the 750×1334 canvas to view pixels, with eyes-only and box-only fallbacks. No tracked face → no makeup is drawn anywhere.
- Space contract: tracker output is top-down; the live GLES view is bottom-up, so exactly one y-flip happens at the GL boundary (`GpuPreviewView`). The lite overlay and poster stills consume top-down fractions directly; `cropToView` maps frame fractions into the center-cropped preview, while stills use raw fractions (`crop = false`).

### 5.3. Render paths

| Path | Grade | Beauty | Reshape | Makeup | Tint |
|---|---|---|---|---|---|
| GPU live (`GpuPreviewView`) | in-shader | in-shader | in-shader | up to 5 in-shader matte slots (LUT recolor or multiply) | not applied (the shader grade carries the look) |
| Lite live (SurfaceView) | tint overlay View | soft-glow overlay | sliders hidden | Canvas overlay (`MatteCompositor.renderOverlayInto`), source-over approximation | tint overlay View |
| Poster still (all devices) | bake | bake | bake | per-pixel CPU, exact shader parity (incl. 24×24 mesh warp) | baked only when the live path showed it as an overlay (`bakeTint = !gpuActive`) |

The compositing order is identical on every path: sample → smooth → eyes/teeth retouch → sharpen → mattes → grade → whiten → look knobs → (lite) tint. The offscreen baker falls back to the CPU path on any GL failure, so captures never lose the look.

### 5.4. Deliberate deviations

- Lite live makeup is source-over only: an overlay View cannot multiply against the camera surface. Stills still bake the exact multiply/LUT math.
- The slim-face / big-eyes sliders are hidden on lite devices so a poster can never bake a reshape the preview never showed.
- Masks ship per category rather than per item because the packs themselves are byte-identical per category; per-item color comes from tint metadata.

### 5.5. Verification

- `./gradlew :app:testDebugUnitTest` — 14 `MatteGeometryTest` cases on the JVM (fit recovery, crop mapping, mask UV/matrix round-trips) alongside the existing model/API suites.

---

## 6. REA-Derived Beauty Upgrade (adaptive smoothing + retouch + knobs)

Provenance: decompiled GLSL from Tango's shipped Banuba effect package (`rea-out/camfilter/banuba/extraction/Beauty/`: `smooth.frag`, `retouch.frag`, `hblur/vblur.frag`), synthesis in `rea-out/camfilter/TANGO_BANUBA_BEAUTY.md` §2.3, roadmap items in `rea-out/camfilter/FINDINGS.md` §4.2. Shipped sources: `BeautyShader.kt` (shared GLSL, exact port) and `BeautyMath.kt` (CPU twins).

### 6.1. Adaptive smoothing composite (replaces the plain mix)

The previous stage was a full-frame bilateral blur mixed by the slider (`rgb = mix(orig, blurred, s)`). It is now Banuba's skin-adaptive composite, merged into one pass:

- Blur: the existing 9-tap edge-aware Gaussian unchanged — it doubles as the composite's `blurred` and (folded in) the legacy `soft_skin` pass.
- Curve: `clamp(-0.391731·x² + 1.4554·x − 0.0637, 0, 1)` per channel — the shipped skin-tone beautify polynomial.
- `val2` discriminator: `length(rgb.gb) − length(blurred.gb) + 0.5` passed through the un-clamped `x⁸·128` steep window; vivid content (lips, eyes, edges) keeps the original while flat skin gets the curve.
- Gated unsharp: `step((0.1·s)², |diff|²)·(0.05·s)·diff` — detail is pulled back toward the blur only where the difference is significant.
- Blend: `mix(rgb, comp, skin·0.4·s)` (the shipped alpha), after folding the legacy soft-skin mix `mix(comp, blurred, skin·0.7·s)` — same order and constants as the shipped layer stack, slider-scaled.

### 6.2. Skin mask: NN stand-in (biggest deviation)

Banuba gates with a segmentation-NN skin mask; CamStar has no NN. `beautySkin()` is a soft YCbCr window (Cb ≈ 0.30–0.53, Cr ≈ 0.50–0.73, luma floor) — gray, green, blue, and shadow tones fall out; warm skin passes. Trade-off: skin-like backgrounds (beige walls) can be treated as skin; the `val2` window keeps lips/eyes safe. This is the piece to re-check on device with real faces.

### 6.3. Eyes/teeth retouch

The shipped build binds no whitening LUTs (sharpen-only despite shipping 64³ flipbooks), so the observable behavior is not LUT-based. CamStar whitens directly inside soft ellipses at both eyes (`uEyeL`/`uEyeR`, radius `faceWidth·0.18`, same as the big-eyes reshape) and the mouth (`uMouth` = mouth-corner centre + corner-distance radius): desaturate 60% + lift 28%, with low-saturation damping (`1 − clamp(sat·3)`) so lips and iris are protected and only near-white sclera/teeth respond.

### 6.4. Look knobs

- Sharpen: Banuba's `5c − 4 diagonals` kernel via `sharpenCall`, mixed at `uSharpen · 0.5` (the kernel is strong; half-scale keeps the slider sane).
- Saturation: `mix(luma, c, 1 + 0.6·u)` — GLSL `mix` extrapolates for the boost; the CPU twin mirrors the extrapolation (caught by a unit test).
- Contrast: `(c − 0.5)·(1 + 0.5·u) + 0.5`.
- Ruddy: skin-gated warmth `u · beautySkin(c) · (0.09, 0.015, −0.045)` — reuses the §6.2 window.

All eight stages add no sampler units: uniform floats + `uMouth` only; the 11-unit budget and CPU-fallback threshold are unchanged.

### 6.5. CPU parity & verification

- `BeautyMath` holds exact twins (including the un-clamped window arithmetic and mix extrapolation); `GpuFilterProcessor.cpuBake` runs composite → retouch → sharpen → knobs in shader order. Only the blur source stays the cheap box approximation, as before.
- 20 `BeautyMathTest` cases cover curve endpoints/midtones, skin-window accept/reject, composite identity/gating/lift directions, whiten + retouch damping (saturated lips protected, gray == whiten, half-coverage midpoint), the sharpen kernel (flat identity, isolated-centre boost), and knob neutrality/directionality.
- On-device (realme RMX3151, GPU path): the full preview program and the offscreen bake program both compile and link clean; measured behavior matches the equations — contrast +50% maps 0.269→0.154 (predicted 0.153), saturation raises the chroma spread (13.8→22.2), sharpen raises edge energy ~12%, and smooth is a no-op on a non-skin scene (the skin gate visibly doing its job). Poster capture bakes through the GPU path with no fallback.
- GLSL pitfall recorded: the ES reserve list applies to locals — an early `vec3 flat` in `beautyWhiten` failed device compilation (all JVM tests stayed green) and the preview silently degraded to the passthrough fallback (`GpuPreview` log line only). Any shader-text change needs a device compile smoke test in addition to the JVM suite.
- Sliders: `smooth` is now skin-gated (background stays crisp — the anti-wax property); sharpen/contrast/saturation/ruddy/eyes-teeth are GPU-only rows (hidden on lite like slim/big-eyes, so posters never bake what the preview did not show).
