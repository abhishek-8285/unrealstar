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
