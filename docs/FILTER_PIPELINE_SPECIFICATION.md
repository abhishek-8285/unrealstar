# CamStar Filter Pipeline — Recipe-Driven Grade Stage (Specification)

Companion to `BEAUTY_PIPELINE_SPECIFICATION.md` (the makeup matte pipeline).
Reverse-engineering provenance: `rea-out/com.instagram.android/FILTER_FINDINGS.md`,
`rea-out/com.snapchat.android/FILTER_FINDINGS.md`, synthesis in
`rea-out/FILTER_SYSTEM_FINDINGS.md`.

---

## 1. Why this stage exists

The original CamStar "Instagram-style" filters were **ColorMatrix
approximations + flat tint overlays**. REA of both market leaders showed what a
production filter actually is:

- **Instagram (IGLU):** ordered GPU passes — a per-channel curve strip LUT
  (`assets/filters/<brand>/map.png`, 256×3 / 256×1 RGB, no alpha), then screen-
  space blend passes over masks (overlay/soft-light/vignette/glow/grain —
  grayscale `mode L` or RGB), each with named `(default, min, max)` uniforms.
  Declared in JSON manifests + `.iglu` DSL; catalog order is config-side.
- **Snapchat:** filters *are* lenses — `.lns` (`LZC\0`) containers with
  `scene.scn + multi-pass GLSL (lookup.glsl, smoothingnew.glsl, gaussvh9.glsl)
  + LUT/PNG textures + optional JS`, run on one native engine; shipped set is
  only 4 filters, the rest is fetched (`LensResource.file_url`).

CamStar adopts the architecture both share — **filter = data**: a recipe-driven
grade stage between our existing matte and whiten passes. Competitor textures
are copyrighted; the equivalent-curve/mask assets here are **procedurally
generated** by `scripts/build_grade_assets.py` from curated tonal recipes.

---

## 2. Data model (`GradeRecipe.kt`)

```
Recipe          := { id, curve: AssetPath?, overlays: [Overlay..3],
                    strength: {default, min, max} }
Overlay         := { texture: AssetPath, blend: MULTIPLY|SCREEN|OVERLAY|SOFT_LIGHT,
                    opacity: 0..1 }
```

- Recipes keyed by `CameraFilter.id` (`assets/grade/catalog.json`), attached by
  `FilterEngine.filters` (same order/ids as `INSTAGRAM_FILTERS` — UI/API ids
  unchanged).
- A recipe **with a curve replaces the 4×5 color matrix** on GPU/bake paths;
  the matrix remains the declared approximation on lite devices.
- Matrix-only looks (moon, sepia, inkwell) keep the matrix — a per-channel
  curve cannot mix channels (true B&W / sepia need Y from RGB). Sepia and
  inkwell therefore ship **no catalog entry at all**; moon pairs its matrix
  with a vignette overlay.

## 3. Assets (`scripts/build_grade_assets.py` → `assets/grade/`)

| Asset | Format | Role |
|---|---|---|
| `<id>/curve.png` | 256×3 RGB; row 0 = R curve, row 1 = G, row 2 = B (written into the texture's own channel) | per-channel tone mapping |
| `<id>/vignette.png` | 256×256 RGB radial white→black | MULTIPLY darkening |
| `<id>/glow.png` | 64×64 warm radial | SCREEN brightening |
| `<id>/paper.png` | 256×256 value-noise around 0.5 gray | SOFT_LIGHT texture (0.5 neutral) |
| `<id>/grain.png` | 128×128 sparse speckle | SCREEN at low opacity |

All seed-deterministic (Pillow), no alpha channel anywhere (matches IG's
grayscale-mask convention).

## 4. Shader stage (`GradeShader.kt`), added to both GPU paths

Stage order in the they-shared single pass:

```
sample(OES y-flip crop [live] / quad [bake])
→ warpView reshape (slim-face / big-eyes)
→ bilateral-ish smoothing (uSmooth)
→ matte slots ×5 (LUT recolor | multiply, face-anchored)
→ CURVE   (256×3 strip; mirror of GradeMath.applyCurve)
→ 4×5 color matrix  (identity when the curve replaced it)
→ whiten
→ OVERLAYS ×3 (blend-mode × opacity; screen-space UV)
→ (bake only) tint overlay
```

- Curve sampling: `texture2D(curve, vec2((floor(c*255)+0.5)/256, (ch+0.5)/3))`
  — 8-bit camera inputs land exactly on texel centers, so **GL == CPU LUT
  indexing** (this is what makes the parity tests meaningful).
- Blend math mirrors Photoshop/IG semantics (`step(0.5)` splits for
  overlay/soft-light); exact CPU twins in `GradeMath` are unit-tested by hand
  computed expectations (`GradeMathTest`).
- Uniform slots (units): `uCurveTex` = 7, `uOvlTex0..2` = 8..10; unused slots
  bind a neutral 1×1 white texture with opacity 0 (same convention as the
  matte LUT unit) — every sampler stays valid at draw time.

## 5. Paths

| Path | Behavior |
|---|---|
| **GPU live** (`GpuPreviewView`) | curve + overlays bound per frame from `filter.recipe` (decode-once cache + `textureFor` keyed `grade:<asset>`); matrix identity when the curve actives; `gradeStrength` volatile set by the UI slider |
| **GPU still bake** (`GpuFilterProcessor`) | same stage in the offscreen pbuffer pass; requires ≥ 11 sampler units else throws into the CPU fallback (no degraded look on disk) |
| **CPU bake** (`GpuFilterProcessor.cpuBake`) | exact curve LUT per-pixel + per-pixel blend math (`GradeMath`); parity with the shader text |
| **Lite live** (`FilterEngine.applyLive`) | unchanged documented approximation: overlay tint only (no in-shader grade); exact look still arrives on the baked capture |

## 6. Verification

- `GradeMathTest` — hand-computed exactness for strip decode, curve strength
  blending, all four blend modes, opacity mixing, malformed/large-strip guards.
- `GradeCatalogTest` — committed catalog parses; every referenced asset
  exists; blends/opacities/ranges sane; B&W/sepia/inkwell matrix-only;
  cross-checks against the generator's curated table.
- `GradePackTest` — runtime-pack contract: verify() caps/zip-slip/entry types,
  PNG header sanity, version/asset presence, schema parsing (name/tint/
  matrix), duplicate ids, and the builtin merge semantics (override + append).
- Full suite: `./gradlew :app:testDebugUnitTest` (83 tests green).
- **On-device** (target hardware): full program links (the baked-in sampler
  type bug in the live shader was found and fixed during this verification —
  see `rea-out/grade-verify/VERIFY.md`), strength uniform proven on pixels
  (38% vs 100% recipe-consistent deltas), multiply-vignette overlay proven
  live via the radial falloff on X-Pro. Also verified on-device: the Inbox
  layout (`tvEmpty` sibling-constraint fix) renders without the latent
  `NotSibling` crash — screenshot `rea-out/grade-verify/inbox_fixed.png`.
- Whole-app lint sweep: **0 errors** (was 3 errors: 2× `NotSibling`,
  1× `StringFormatMatches` — all fixed); the remaining ~528 warnings are
  policy findings (hard-coded UI copy `SetTextI18n`, dependency-bump
  suggestions), not defects.
- **Runtime packs, full gate**: lint 0 errors + release build + 83 tests
  green with the pack layer in; loopback end-to-end on device (§8).

## 7. Known boundaries

- Strength is wired end-to-end (`FilterEngine.gradeStrength(s)` per-filter
  map → `sbGradeStrength` seekbar → `GpuPreviewView.gradeStrength` uniform /
  `GpuFilterProcessor.apply(gradeStrength)` CPU+GPU bake / scaled lite tint).
  It scales the curve mix and overlay opacities together — one "effect
  amount", as Instagram's per-effect slider does.
- IG's native GLSL, their effect ordering, and Snap's download plumbing are
  not reproduced (not evidenced, not needed for parity of this stage).
- `libscplugin.so` is NOT the reference here — the REA evidence proved it is a
  bootstrap for an unshipped engine; the shipped Scenarium assets informed the
  pass-list shape only.
- On devices below GLES 3.0 the grade live look degrades to the tint
  approximation by design; captures never do (CPU bake is exact).

## 8. Live preview sharpness & face tracking (probed on device)

- **Engine default (product decision).** Live rendering now defaults to the
  lightweight PreviewView path (`FilterEngine.LIVE_GPU_FILTERING = false`):
  system camera preview + tint-overlay look + beauty soft-glow + tracked
  makeup overlay, with every still/poster baking the full look (grade,
  beauty, reshape, mattes) through `GpuFilterProcessor`. The GLES live
  pipeline below stays in the codebase, maintenance-only, behind that
  switch.
- **Stream pair ceiling.** With the YUV analysis stream active, this front
  camera silently caps the preview combination at `[1440x1088 preview,
  1280x960 analysis]`: bounded requests for 1920x1080 / 1920x1440 /
  2160x1080 previews all resolve back to 1440x1088 (the negotiator pairs
  aspect-matched streams; 640x480 resolves as asked, so the selector path
  itself is proven). `CameraLiveActivity.previewResolutionSelector()` pins
  the ceiling explicitly and both engines use it, so GPU and lite render
  from the same stream and crop/upscale identically — switching to the
  lite path cannot gain sharpness, it only loses the live filters.
- **Fragment precision.** All three shaders (live, passthrough, bake) now
  declare `highp` when `GL_FRAGMENT_PRECISION_HIGH` is available. Mediump
  quantizes screen-UV math in steps of roughly one source texel at the
  crop/upscale factors above, which reads as a general softness over the
  whole preview.
- **Face tracking cadence.** The ML Kit tracker now runs at 5 Hz (200 ms
  throttle, was 2.5 Hz) on a subsampled decode (~640x480 upright from the
  1280x960 analyzer frame), holding transient-bitmap churn near the old
  level while halving anchor lag. The analyzer frame size and rotation are
  logged once per session (`CameraLive`) alongside the negotiated stream
  size (`GpuPreview`).
- **Camera-surface lifecycle (black preview after screen off/on).** Turning
  the screen off recreates the GLES context; the one `SurfaceTexture`/
  `Surface`/OES-texture triplet that had been handed to CameraX belongs to
  the dead context and its BufferQueue cannot be revived — the camera keeps
  streaming into it and `updateTexImage` throws every frame (black preview).
  The GL view now creates **one fresh triplet per camera session**:
  `requestSurface()` releases the previous triplet before minting the next,
  and `onSurfaceCreated` drops whatever the old context owned (no GL
  deletes — those ids died with it) and fires `onSurfaceReset` when a
  session had been wired to the dropped triplet, whether it was already
  delivered (`deliveredGen` match) or still in flight on the main thread
  (pending callback). The latter is the cold-start race with the
  entrance-transition surface recreation: if it went undetected, the
  session streamed into a dead queue and never produced a single frame
  (no analyzer logs, no GL errors — just black).
- **Rebind serialization.** CameraX counts the surfaces of a still-attaching
  bind as existing ones, so a rebind racing the previous bind fails with
  "No supported surface combination" and used to latch the lite fallback.
  All heal paths now go through `scheduleRebind()`: at most one bind
  attaches at a time (overlapping requests coalesce) and the retry waits
  ~250 ms for the HAL to detach the previous attempt's surfaces. Verified
  with 8 consecutive cold starts and 4 screen-off/on cycles: every session
  logs its stream size, follows with an analyzer frame ~400 ms later, and
  renders bright (screenshot patch mean ~165 vs 20 black).

## 8. Runtime filter packs (Snap `file_url` contract)

Snap ships 4 filters and fetches the rest at runtime (`LensResource.file_url`,
protobuf catalog on the 42 MB Scenarium engine — REA:
`rea-out/com.snapchat.android/FILTER_FINDINGS.md`). CamStar implements the
same product behavior with a flat, auditable contract ([GradePackLoader]):

- **Endpoint**: `BuildConfig.GRADE_PACK_URL` (blank = off). Release stays
  blank until the backend ships the endpoint; dev/verification injects a
  loopback URL per build: `-PgradePackUrl=http://127.0.0.1:9373/grade_pack.zip`
  + `adb reverse tcp:9373 tcp:9373` + `python3 -m http.server 9373
  --directory rea-out/grade-pack` (cleartext loopback is whitelisted in
  `network_security_config.xml`; production is HTTPS-only).
- **Request**: GET with `X-Grade-Pack-Version: <installed>` so a server can
  304 short-circuit unchanged devices.
- **Pack**: one ZIP (`catalog.json` at top level + the PNGs it references,
  laid out like `assets/grade/`). Schema = the shipped catalog, plus per
  filter: `name` (≤24 chars — the carousel chip label), `tint {color, alpha}`
  (lite-path look), `matrix` (20-float 4x5, lite-path ColorMatrix; biases as
  in the shipped defs). Pack-only ids need no `INSTAGRAM_FILTERS` twin: they
  default to identity matrix + transparent tint (grade-only look on lite).
- **Validate before swap**: byte caps (12 MB pack / 6 MB entry / 96 entries),
  zip-slip + charset-safe entry names, PNG signature/IHDR sanity (≤2048²,
  supported depth/color type), catalog parse, and every referenced asset
  present — all in memory BEFORE anything touches disk. A broken download can
  never degrade the camera.
- **Atomic install**: staged dir `filesDir/grade_pack/v<N>/` + atomic pointer
  write (`active.tmp` → rename `active`), other versions pruned after swap.
  Recipes from the pack carry `packDir` so [GradeAssets]/[GpuPreviewView]
  resolve their textures from the pack dir (decode cache keys include the
  source root, so an overriding pack never collides with shipped assets).
- **Merge semantics** (`applyPack`, unit-covered): pack entries with builtin
  ids override recipe/name/tint/matrix in place (order stable); unknown ids
  append. `FilterEngine.refreshGrades()` (@IO) fetches → verifies → installs
  → rebuilds `filters` → clears grade bitmap/strength caches (a pack
  replacement invalidates both by id) → keeps the user's selection by id.
  The Filter drawer rebuilds its carousel and re-arms the one-shot thumbnail
  capture when the count changes.
- **Generator**: `camstar/scripts/build_grade_pack.py` — demo pack v1 with
  `sakura` (new, glow SCREEN + pink tint, strength default 0.85), `cyber`
  (new, vignette MULTIPLY), plus a `gingham` override (proves overrides on a
  builtin id) — used by the device verification below.
- **Device evidence** (`rea-out/grade-pack-server/`): `pack v1 installed
  (6 files)` in logcat over the reverse tunnel; carousel rebuilt with the
  pack labels/thumbnails (`Sakura`, `Cyber`, `Gingham+` with the override's
  baked look); Sakura live on the lite path moved R +8..+13 with G/B ~flat at
  every sampled point — the pack's `#F472B6 @ 9%` tint, rendered through the
  same strength plumbing as shipped filters.
