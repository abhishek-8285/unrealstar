#!/usr/bin/env python3
"""
Build CamStar's bundled color-grade assets and catalog (assets/grade/).

Provenance — why this script exists (see docs/FILTER_PIPELINE_SPECIFICATION.md
and rea-out/FILTER_SYSTEM_FINDINGS.md):

- REA of com.instagram.android 452.0.0.13.64 showed filters are data-driven GPU
  pass chains: a per-channel color-curve LUT strip (assets/filters/<brand>/
  map.png, 256x3 / 256x1 RGB) followed by grayscale/RGB mask overlays
  (overlay_map / soft_light_map / vignette / glow) composited with named blend
  modes and opacity uniforms (default/min/max). The curve and mask textures for
  Instagram's brands are their copyrighted pixels; they are NOT reused here.
- REA of com.snapchat.android 14.28.0.54 showed the same idea as .lns shader
  scene packages: scene.scn + multi-pass GLSL (lookup.glsl) + LUT textures.
- CamStar therefore ships ONE canonical mechanism — a 256x3 RGB curve strip
  (rows = per-channel transfer functions) plus up to 3 screen-space overlay
  masks — with curves and masks generated procedurally here from curated tonal
  recipes (lift/gain/gamma/contrast + radial vignettes/glows + value-noise
  paper/grain). Everything is seed-deterministic and reproducible.

Outputs (committed):
  camstar/android/app/src/main/assets/grade/<filter>/curve.png     (256x3 RGB)
  camstar/android/app/src/main/assets/grade/<filter>/*.png         (overlays)
  camstar/android/app/src/main/assets/grade/catalog.json

Run:  python3 camstar/scripts/build_grade_assets.py
"""

import json
import math
import os
import random
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: pip install Pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
CAMSTAR = os.path.dirname(HERE)
OUT_ROOT = os.path.join(CAMSTAR, "android", "app", "src", "main", "assets", "grade")


# --------------------------------------------------------------------------
# Curve synthesis. A curve strip is 256 entries per channel: out = f(in).
# --------------------------------------------------------------------------

def _piecewise(x, anchors):
    """Monotone piecewise-linear evaluation of [(x0,y0),(x1,y1),...] anchors."""
    if x <= anchors[0][0]:
        return anchors[0][1]
    for i in range(1, len(anchors)):
        x0, y0 = anchors[i - 1]
        x1, y1 = anchors[i]
        if x <= x1:
            t = (x - x0) / (x1 - x0) if x1 > x0 else 0.0
            return y0 + (y1 - y0) * t
    return anchors[-1][1]


def tone_curve(r, g, b):
    """Build one 256-entry curve per channel from anchor lists [x,y] in 0..1."""
    lut = []
    for ch in (r, g, b):
        table = [min(1.0, max(0.0, _piecewise(i / 255.0, ch))) for i in range(256)]
        lut.append([int(round(v * 255.0)) for v in table])
    return lut


def identity_anchors():
    return [(0.0, 0.0), (1.0, 1.0)]


def anchors_from_params(lift=0.0, gain=1.0, gamma=1.0, contrast=0.0, tint=(0.0, 0.0, 0.0)):
    """Curated-recipe helper: lift shadows, gain highlights, gamma, S-curve,
    plus a constant per-channel tint gain (applied multiplicatively at the end).
    Returns three anchor lists in 0..1 space."""
    def curve(ch_tint):
        def fn(x):
            y = x
            # S-curve: push away from mid.
            if contrast:
                s = 0.5 + (y - 0.5)
                c = y - (s - y) * contrast
                y = c
            y = y * gain
            if lift:
                # shadow lift scaled by (1-x) so highlights are untouched.
                y = y + lift * (1.0 - y)
            if gamma != 1.0:
                y = max(0.0, min(1.0, y)) ** gamma
            y = max(0.0, min(1.0, y)) * (1.0 + ch_tint)
            return max(0.0, min(1.0, y))
        # Sample the analytic shape into anchors (dense = exact enough).
        xs = [0.0, 0.25, 0.5, 0.75, 1.0]
        return [(x, fn(x)) for x in xs]
    return curve(tint[0]), curve(tint[1]), curve(tint[2])


def write_curve_png(path, luts):
    """256x3 RGB strip: row 0 = R mapping, row 1 = G, row 2 = B (each stored in
    the texture's own channel — exactly how the strip is sampled in GLSL)."""
    img = Image.new("RGB", (256, 3))
    px = img.load()
    for i in range(256):
        px[i, 0] = (luts[0][i], 0, 0)
        px[i, 1] = (0, luts[1][i], 0)
        px[i, 2] = (0, 0, luts[2][i])
    img.save(path)


# --------------------------------------------------------------------------
# Overlay synthesis (screen-space mask textures, generated procedurally).
# --------------------------------------------------------------------------

def write_vignette(path, strength=0.55, center_widen=0.62, size=256):
    """Radial mask: white at center -> dark corners (used with MULTIPLY)."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    c = (size - 1) / 2.0
    maxr = math.hypot(c, c)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c) / maxr
            t = max(0.0, (d - center_widen) / (1.0 - center_widen)) if d > center_widen else 0.0
            v = 1.0 - strength * (t * t * (3.0 - 2.0 * t))
            val = int(round(v * 255.0))
            px[x, y] = (val, val, val)
    img.save(path)


def write_glow(path, color=(255, 214, 165), falloff=1.15, size=64):
    """Warm radial halo: bright tinted center -> black edges (used with SCREEN)."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    c = (size - 1) / 2.0
    maxr = math.hypot(c, c)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c) / maxr
            v = max(0.0, 1.0 - math.pow(d, falloff)) ** 2
            px[x, y] = tuple(int(round(v * ch)) for ch in color)
    img.save(path)


def write_paper(path, seed=7, contrast=14, warm=8, size=256):
    """Subtle value-noise sheet around mid-gray (used with SOFT_LIGHT; 0.5 gray
    is the neutral element). Seeded so the file is reproducible byte-for-byte
    for a given script version."""
    rng = random.Random(seed)
    img = Image.new("RGB", (size, size))
    px = img.load()
    # Two octaves of smooth value noise via low-res random + bilinear upscale.
    small = size // 8
    base = [(rng.random(), rng.random()) for _ in range(small * small)]
    def noise(x, y, scale):
        fx, fy = (x / scale) % small, (y / scale) % small
        x0, y0 = int(fx), int(fy)
        tx, ty = fx - x0, fy - y0
        x1, y1 = (x0 + 1) % small, (y0 + 1) % small
        a = base[y0 * small + x0][0] * (1 - tx) + base[y0 * small + x1][0] * tx
        b = base[y1 * small + x0][0] * (1 - tx) + base[y1 * small + x1][0] * tx
        return a * (1 - ty) + b * ty
    for y in range(size):
        for x in range(size):
            n = 0.65 * noise(x, y, 32) + 0.35 * noise(x, y, 128)
            v = 0.5 + (n - 0.5) * (contrast / 128.0 * 4.0)
            r = max(0.0, min(1.0, v + warm / 512.0))
            g = max(0.0, min(1.0, v))
            b = max(0.0, min(1.0, v - warm / 512.0))
            px[x, y] = (int(round(r * 255)), int(round(g * 255)), int(round(b * 255)))
    img.save(path)


def write_grain(path, seed=11, density=0.28, size=128):
    """Sparse bright speckles around black (used with SCREEN at low opacity)."""
    rng = random.Random(seed)
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            if rng.random() < density:
                v = rng.randint(60, 200)
                px[x, y] = (v, v, v)
    img.save(path)


# --------------------------------------------------------------------------
# The curated recipes. Names/ids stay identical to the existing
# INSTAGRAM_FILTERS (camstar CameraFilter.kt) so the UI and API id are stable.
# 'curve' recipes REPLACE the color matrix on GPU/bake paths; matrix-only
# filters keep their matrix (cross-channel looks a per-channel curve can't do:
# true B&W / sepia need channel mixing).
# --------------------------------------------------------------------------

RECIPES = [
    {
        "id": "clarendon",
        "curve_params": {"gain": 1.0, "gamma": 0.94, "contrast": 0.10,
                          "tint": (0.00, 0.015, 0.06)},
        "overlays": [],
    },
    {
        "id": "juno",
        "curve_params": {"gamma": 0.98, "contrast": 0.06,
                          "tint": (0.075, 0.030, -0.045)},
        "overlays": [{"kind": "glow", "texture": "glow.png", "blend": "screen",
                      "opacity": 0.20}],
    },
    {
        "id": "gingham",
        "curve_params": {"lift": 0.10, "gain": 0.97, "gamma": 1.03,
                          "tint": (-0.010, 0.010, 0.020)},
        "overlays": [{"kind": "paper", "texture": "paper.png", "blend": "soft_light",
                      "opacity": 0.22}],
    },
    {
        "id": "lark",
        "curve_params": {"lift": 0.05, "gamma": 0.97, "tint": (0.015, 0.045, -0.005)},
        "overlays": [],
    },
    {
        # Cross-channel B&W: matrixFilter; vignette scanline only.
        "id": "moon",
        "overlays": [{"kind": "vignette", "texture": "vignette.png", "blend": "multiply",
                      "opacity": 0.35}],
    },
    {
        # Sepia needs channel mixing: matrix only.
        "id": "sepia",
        "overlays": [],
    },
    {
        "id": "aden",
        "curve_params": {"lift": 0.06, "gain": 0.99, "contrast": 0.04,
                          "tint": (0.045, 0.015, -0.005)},
        "overlays": [{"kind": "glow", "texture": "glow.png", "blend": "screen",
                      "opacity": 0.16}],
    },
    {
        "id": "valencia",
        "curve_params": {"lift": 0.08, "gamma": 1.00, "tint": (0.055, 0.025, -0.010)},
        "overlays": [{"kind": "paper", "texture": "paper.png", "blend": "soft_light",
                      "opacity": 0.28}],
    },
    {
        "id": "xpro",
        "curve_params": {"contrast": 0.16, "gamma": 1.02,
                          "tint": (0.055, 0.015, -0.040)},
        "overlays": [{"kind": "vignette", "texture": "vignette.png", "blend": "multiply",
                      "opacity": 0.45}],
    },
    {
        "id": "lofi",
        "curve_params": {"contrast": 0.12, "tint": (0.030, 0.010, 0.010)},
        "overlays": [
            {"kind": "vignette", "texture": "vignette.png", "blend": "multiply",
             "opacity": 0.50},
            {"kind": "grain", "texture": "grain.png", "blend": "screen",
             "opacity": 0.10},
        ],
    },
    {
        # Hard B&W: matrix only (channel mixing).
        "id": "inkwell",
        "overlays": [],
    },
    {
        "id": "nashville",
        "curve_params": {"lift": 0.07, "gamma": 0.96, "contrast": 0.05,
                          "tint": (0.060, 0.020, -0.020)},
        "overlays": [{"kind": "glow", "texture": "glow.png", "blend": "screen",
                      "opacity": 0.24}],
    },
]


def main() -> int:
    os.makedirs(OUT_ROOT, exist_ok=True)
    catalog = {"version": 1, "source": "scripts/build_grade_assets.py", "filters": []}
    for recipe in RECIPES:
        fid = recipe["id"]
        fdir = os.path.join(OUT_ROOT, fid)
        os.makedirs(fdir, exist_ok=True)
        entry = {
            "id": fid,
            "strength": {"default": 1.0, "min": 0.0, "max": 1.0},
            "overlays": [],
        }
        if "curve_params" in recipe:
            cp = recipe["curve_params"]
            luts = tone_curve(*anchors_from_params(
                lift=cp.get("lift", 0.0),
                gain=cp.get("gain", 1.0),
                gamma=cp.get("gamma", 1.0),
                contrast=cp.get("contrast", 0.0),
                tint=tuple(cp.get("tint", (0.0, 0.0, 0.0))),
            ))
            write_curve_png(os.path.join(fdir, "curve.png"), luts)
            entry["curve"] = f"{fid}/curve.png"
        for ov in recipe["overlays"]:
            path = os.path.join(fdir, ov["texture"])
            kind = ov["kind"]
            if kind == "vignette":
                write_vignette(path)
            elif kind == "glow":
                write_glow(path)
            elif kind == "paper":
                write_paper(path, seed=13, contrast=14, warm=8)
            elif kind == "grain":
                write_grain(path)
            else:
                raise ValueError(f"unknown overlay kind {kind!r}")
            entry["overlays"].append({
                "texture": f"{fid}/{ov['texture']}",
                "blend": ov["blend"],
                "opacity": ov["opacity"],
            })
        catalog["filters"].append(entry)

    out = os.path.join(OUT_ROOT, "catalog.json")
    # Empty recipes (no curve, no overlays — pure matrix looks like sepia or
    # inkwell) carry no data: they stay matrix-only, not catalog entries.
    catalog["filters"] = [
        e for e in catalog["filters"]
        if e.get("curve") or e["overlays"]
    ]
    with open(out, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2, sort_keys=True)
        f.write("\n")

    n_assets = sum(
        len([p for p in os.listdir(os.path.join(OUT_ROOT, pid)) if p.endswith(".png")])
        for pid in {r["id"] for r in RECIPES}
    )
    print(f"grade assets: {len(RECIPES)} filters, {n_assets} PNGs, catalog={out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
