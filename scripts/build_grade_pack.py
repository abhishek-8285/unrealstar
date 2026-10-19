#!/usr/bin/env python3
"""Builds a runtime filter pack ZIP for GradePackLoader (reusable, no
competitor pixels — everything procedurally generated, same policy as
build_grade_assets.py).

Output: <OUTPUT_DIR>/grade_pack.zip plus the loose staging dir
<OUTPUT_DIR>/grade_pack/<id>/… so `python3 -m http.server` can serve both.

Pack contents (proves every contract surface):
- `sakura`  NEW filter: pink lift curve + warm glow SCREEN overlay + tint.
- `cyber`   NEW filter: magenta-cyan contrast curve + hard vignette MULTIPLY.
- `gingham` OVERRIDE of a builtin: deeper cool wash (name stays via pack name).

Serve for device verification (debug build with
-PgradePackUrl=http://127.0.0.1:9373/grade_pack.zip):
  cd <OUTPUT_DIR> && python3 -m http.server 9373
  adb reverse tcp:9373 tcp:9373
"""

import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from build_grade_assets import (  # noqa: E402
    anchors_from_params,
    tone_curve,
    write_curve_png,
    write_vignette,
    write_glow,
)

OUT_ROOT = os.path.join(os.path.dirname(HERE), "..", "rea-out", "grade-pack")
STAGE = os.path.join(OUT_ROOT, "pack")
VERSION = 1


def pwl(lut_from_anchors):
    idx = 0


def make_curve(luts, rel_path):
    os.makedirs(os.path.dirname(rel_path), exist_ok=True)
    write_curve_png(rel_path, luts)


def main() -> int:
    os.makedirs(STAGE, exist_ok=True)

    # --- sakura: gentle pink lift, warm glow -------------------------------
    luts = tone_curve(*anchors_from_params(
        lift=0.06, gain=1.02, gamma=0.96, contrast=0.04,
        tint=(0.045, -0.01, 0.02)))
    write_curve_png(os.path.join(STAGE, "sakura_curve.png"), luts)
    write_glow(os.path.join(STAGE, "sakura_glow.png"),
               color=(255, 183, 213), falloff=1.2)

    # --- cyber: magenta-cyan push, hard vignette ---------------------------
    luts = tone_curve(*anchors_from_params(
        lift=-0.02, gain=1.12, gamma=1.06, contrast=0.16,
        tint=(0.055, -0.03, 0.05)))
    write_curve_png(os.path.join(STAGE, "cyber_curve.png"), luts)
    write_vignette(os.path.join(STAGE, "cyber_vignette.png"), strength=0.6)

    # --- gingham override: deeper cool wash than the shipped recipe --------
    luts = tone_curve(*anchors_from_params(
        lift=0.03, gain=0.94, gamma=1.08, contrast=0.08,
        tint=(-0.02, 0.0, 0.05)))
    write_curve_png(os.path.join(STAGE, "gingham_curve.png"), luts)

    catalog = {
        "version": VERSION,
        "filters": [
            {
                "id": "sakura",
                "name": "Sakura",
                "tint": {"color": "#F472B6", "alpha": 0.09},
                "curve": "sakura_curve.png",
                "overlays": [
                    {"texture": "sakura_glow.png", "blend": "screen",
                     "opacity": 0.38}
                ],
                "strength": {"default": 0.85, "min": 0.0, "max": 1.0},
            },
            {
                "id": "cyber",
                "name": "Cyber",
                "tint": {"color": "#22D3EE", "alpha": 0.1},
                "curve": "cyber_curve.png",
                "overlays": [
                    {"texture": "cyber_vignette.png", "blend": "multiply",
                     "opacity": 0.5}
                ],
                "strength": {"default": 1.0, "min": 0.0, "max": 1.0},
            },
            {
                "id": "gingham",
                "name": "Gingham+",
                "curve": "gingham_curve.png",
                "overlays": [],
                "strength": {"default": 1.0, "min": 0.0, "max": 1.0},
            },
        ],
    }
    catalog_text = json.dumps(catalog, indent=2)
    with open(os.path.join(STAGE, "catalog.json"), "w") as f:
        f.write(catalog_text)

    zip_path = os.path.join(OUT_ROOT, "grade_pack.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("catalog.json", catalog_text)
        for name in [
            "sakura_curve.png", "sakura_glow.png",
            "cyber_curve.png", "cyber_vignette.png",
            "gingham_curve.png",
        ]:
            zf.write(os.path.join(STAGE, name), name)

    names = ", ".join(f["id"] for f in catalog["filters"])
    print(f"pack v{VERSION}: {zip_path} ({os.path.getsize(zip_path)} bytes, "
          f"{len(catalog['filters'])} filters: {names})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
