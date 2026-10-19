#!/usr/bin/env python3
"""
Build CamStar's bundled beauty-makeup assets and catalog from the
reverse-engineered RangLive packs (camstar/assets/*.zip).

Provenance and why this script exists (see docs/BEAUTY_PIPELINE_SPECIFICATION.md):

- Every item zip inside a category is byte-identical (verified by md5 in
  verify_pack()). The competitor's per-item variation is the swatch color in
  the icon previews (iconUrl in beauty_catalog.json), not distinct masks.
- CamStar therefore ships ONE canonical mask per category — the real
  segmentation texture with its canvas anchor from the pack metadata — plus
  the 64x64 LUT for lipstick, and reproduces item variation as a per-item
  color tint applied at render time.
- Lipstick item colors are the real icon swatch colors, extracted from the
  CDN icon cache (assets/beauty_icons/). Blush / brows / eyeshadow use
  curated palettes relative to the shipped texture's alpha-weighted mean
  color, so the first item of each category renders exactly like the
  shipped pack (tint = 1.0).
- Eyelash textures are black (#000); tinting black is meaningless, so the
  three lash items vary density via default strength instead.

Canonical face geometry is derived from the masks themselves: the eyelash
texture marks the eye line, the mouth texture marks the mouth center. These
landmarks anchor the runtime similarity transform (MatteGeometry.kt).

Outputs (committed):
  camstar/android/app/src/main/assets/beauty/<category>/*.png
  camstar/android/app/src/main/assets/beauty/catalog.json

Run:  python3 camstar/scripts/build_beauty_assets.py [--no-network]
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.request
import zipfile

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: pip install Pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
CAMSTAR = os.path.dirname(HERE)
ASSETS = os.path.join(CAMSTAR, "assets")
ICON_CACHE = os.path.join(ASSETS, "beauty_icons")
OUT_ROOT = os.path.join(CAMSTAR, "android", "app", "src", "main", "assets", "beauty")

# Canonical pack member per category (all members are identical; any would do).
CANONICAL = {
    "lipstick": ("matte_1.zip", {
        "wumian1/__facemorph/mouth.png": "lipstick/mouth.png",
        "wumian1/__lipTable/table.png": "lipstick/table.png",
    }),
    "blush": ("blusha.zip", {"blusha/__facemorph/face.png": "blush/face.png"}),
    "brow": ("browa.zip", {"browa/__facemorph/brow.png": "brow/brow.png"}),
    "eyeshadow": ("eyeshadowa.zip", {
        "eyeshadowa/__facemorph/eyeshadowa.png": "eyeshadow/eyeshadow.png",
    }),
    "eyelash": ("eyelash_1.zip", {"eyelash/__facemorph/eyelashb.png": "eyelash/eyelash.png"}),
}

# Category zips to cross-check for identity (warn only — tints are curated).
PACK_MEMBERS = {
    "lipstick": [f"matte_{i}.zip" for i in range(1, 11)]
                + [f"natural_{i}.zip" for i in range(1, 11)],
    "blush": [f"blush{x}.zip" for x in "abcdefg"],
    "brow": [f"brow{x}.zip" for x in "abcdefgh"],
    "eyeshadow": ["eyeshadowa.zip", "eyeshadowb.zip", "eyeshadowc.zip",
                  "eyeshadowd.zip", "eyeshadowe.zip", "eyeshadowf.zip",
                  "eyeshadowg.zip", "eyeshadowh.zip", "eyeshadowi.zip",
                  "eyeshadowj.zip", "eyeshadowl.zip"],
    "eyelash": [f"eyelash_{i}.zip" for i in range(1, 4)],
}

# Item palettes. Lipstick hexes are the real extracted icon swatches
# (matte_1..matte_10); other categories are curated around the shipped look.
LIPSTICK = [
    ("lip_rose", "Rose", "#E35081"),
    ("lip_classic_red", "Classic Red", "#E41016"),
    ("lip_deep_red", "Deep Red", "#900507"),
    ("lip_brick", "Brick", "#B34128"),
    ("lip_coral_red", "Coral Red", "#E35157"),
    ("lip_dusky_rose", "Dusky Rose", "#D46163"),
    ("lip_coral", "Coral", "#E35B4F"),
    ("lip_tangerine", "Tangerine", "#E45029"),
    ("lip_scarlet", "Scarlet", "#E2141A"),
    ("lip_wine", "Wine", "#B62C52"),
]
BLUSH = [
    ("blush_rosy", "Rosy", "#FF7679"),      # == shipped texture color
    ("blush_peach", "Peach", "#FFA07A"),
    ("blush_coral", "Coral", "#FF7F50"),
    ("blush_berry", "Berry", "#C4485F"),
    ("blush_nude", "Nude", "#E8B4A0"),
    ("blush_rosewood", "Rosewood", "#B76E79"),
    ("blush_apricot", "Apricot", "#F5A76C"),
]
BROW = [
    ("brow_soft_taupe", "Soft Taupe", "#7A6455"),
    ("brow_ash", "Ash Brown", "#6B5A4E"),
    ("brow_warm", "Warm Brown", "#7A4E32"),
    ("brow_chestnut", "Chestnut", "#8B4A2F"),
    ("brow_espresso", "Espresso", "#3E2A20"),
    ("brow_slate", "Slate", "#5A5A5A"),
    ("brow_chocolate", "Chocolate", "#5C3A28"),
    ("brow_soft_black", "Soft Black", "#2E2A28"),
]
EYESHADOW = [
    ("shadow_champagne", "Champagne", "#E8D5B7"),
    ("shadow_gold", "Gold", "#C9A227"),
    ("shadow_copper", "Copper", "#B87333"),
    ("shadow_bronze", "Bronze", "#8C5A2B"),
    ("shadow_taupe", "Taupe", "#8B7D6B"),
    ("shadow_plum", "Plum", "#6B3A5A"),
    ("shadow_mauve", "Mauve", "#9B7E9E"),
    ("shadow_smokey", "Smokey Grey", "#4A4A52"),
    ("shadow_midnight", "Midnight", "#2B2B3A"),
    ("shadow_emerald", "Emerald", "#2E5E4E"),
    ("shadow_cherry", "Cherry", "#7A2E3A"),
]
EYELASH = [
    ("lash_natural", "Natural", "#101010", 0.42),
    ("lash_volume", "Volume", "#101010", 0.60),
    ("lash_dramatic", "Dramatic", "#101010", 0.82),
]

TINT_MIN, TINT_MAX = 0.05, 6.0


def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def zip_member_bytes(zip_path, member):
    with zipfile.ZipFile(zip_path) as z:
        return z.read(member)


def verify_pack():
    """All member zips inside a category must be byte-identical (pack invariant)."""
    for category, zips in PACK_MEMBERS.items():
        ref_zip, members = CANONICAL[category]
        ref_member = next(iter(members))
        ref = zip_member_bytes(os.path.join(ASSETS, ref_zip), ref_member)
        ref_md5 = hashlib.md5(ref).hexdigest()
        for z in zips:
            path = os.path.join(ASSETS, z)
            if not os.path.exists(path):
                print(f"  ! missing pack member {z}")
                continue
            try:
                data = zip_member_bytes(path, ref_member)
            except KeyError:
                print(f"  ! {z} lacks {ref_member}; pack layout changed")
                continue
            if hashlib.md5(data).hexdigest() != ref_md5:
                print(f"  ! {category}: {z} differs from {ref_zip}; revisit tints")
    print("pack verification done (warnings above, if any)")


def alpha_weighted_mean(path):
    im = Image.open(path).convert("RGBA")
    tot = r = g = b = 0
    for pr, pg, pb, pa in im.getdata():
        if pa > 8:
            tot += pa
            r += pr * pa
            g += pg * pa
            b += pb * pa
    if not tot:
        return (0, 0, 0)
    return (round(r / tot), round(g / tot), round(b / tot))


def blob_centroid_uv(path, half=None):
    """Alpha-weighted centroid in texture UV, optionally split at x = 0.5."""
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    tot = sx = sy = 0
    for i, a in enumerate(im.getchannel("A").getdata()):
        x, y = i % w, i // w
        if half == 0 and x >= w // 2:
            continue
        if half == 1 and x < w // 2:
            continue
        if a:
            tot += a
            sx += a * x
            sy += a * y
    if not tot:
        return None
    return (sx / tot / w, sy / tot / h)


def canvas_pt(anchor, tex_size, scale, uv):
    return [round(anchor[0] + uv[0] * tex_size[0] * scale, 1),
            round(anchor[1] + uv[1] * tex_size[1] * scale, 1)]


def extract_masks():
    for category, (zip_name, members) in CANONICAL.items():
        zip_path = os.path.join(ASSETS, zip_name)
        for member, out_rel in members.items():
            out_path = os.path.join(OUT_ROOT, out_rel)
            os.makedirs(os.path.dirname(out_path), exist_ok=True)
            with open(out_path, "wb") as f:
                f.write(zip_member_bytes(zip_path, member))
            print(f"  {zip_name}:{member} -> {os.path.relpath(out_path, CAMSTAR)}")


def ensure_icons(allow_network):
    os.makedirs(ICON_CACHE, exist_ok=True)
    catalog = json.load(open(os.path.join(ASSETS, "beauty_catalog.json")))
    missing = []
    for cat in catalog["value"]:
        for item in cat["makeupList"]:
            name = item["iconUrl"].split("/")[-1]
            if not os.path.exists(os.path.join(ICON_CACHE, name)):
                missing.append((name, item["iconUrl"]))
    if not missing:
        print(f"icon cache complete ({ICON_CACHE})")
        return
    if not allow_network:
        sys.exit(f"icon cache misses {len(missing)} files and --no-network is set")
    for name, url in missing:
        with urllib.request.urlopen(url, timeout=20) as r, \
                open(os.path.join(ICON_CACHE, name), "wb") as f:
            f.write(r.read())
        print(f"  fetched {name}")
    print(f"icon cache now has {len(os.listdir(ICON_CACHE))} files")


def lipstick_icon_colors():
    """Real swatch hexes for matte_1..10 extracted from the cached icons."""
    colors = {}
    for i in range(1, 11):
        path = os.path.join(ICON_CACHE, f"matte_{i}.png")
        im = Image.open(path).convert("RGBA")
        px = []
        for pr, pg, pb, pa in im.getdata():
            if pa < 200:
                continue
            mx, mn = max(pr, pg, pb), min(pr, pg, pb)
            sat = 0 if mx == 0 else (mx - mn) / mx
            if sat > 0.18 and mx > 60:
                px.append((sat, pr, pg, pb))
        px.sort(reverse=True)
        take = px[:max(1, len(px) // 3)]
        n = len(take)
        colors[i] = (sum(t[1] for t in take) // n,
                     sum(t[2] for t in take) // n,
                     sum(t[3] for t in take) // n)
    return colors


def clamped_tint(target, base):
    out = []
    for t, b in zip(target, base):
        ratio = (t / b) if b > 0 else 1.0
        out.append(round(min(TINT_MAX, max(TINT_MIN, ratio)), 3))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-network", action="store_true",
                    help="fail instead of fetching missing icons")
    args = ap.parse_args()

    print("== verifying pack ==")
    verify_pack()

    print("== extracting canonical masks ==")
    extract_masks()

    print("== icon cache ==")
    ensure_icons(allow_network=not args.no_network)

    print("== deriving canonical face geometry ==")
    lash = os.path.join(OUT_ROOT, "eyelash", "eyelash.png")
    mouth = os.path.join(OUT_ROOT, "lipstick", "mouth.png")
    eye_left = canvas_pt([200, 483], (373, 101), 1.0, blob_centroid_uv(lash, 0))
    eye_right = canvas_pt([200, 483], (373, 101), 1.0, blob_centroid_uv(lash, 1))
    mouth_c = canvas_pt([310, 714], (154, 113), 0.99, blob_centroid_uv(mouth))
    print(f"  eyeL={eye_left} eyeR={eye_right} mouth={mouth_c}")

    print("== computing per-item tints ==")
    lip_colors = lipstick_icon_colors()
    base_lip = lip_colors[1]
    lip_items = []
    for (item_id, name, hex_color), idx in zip(LIPSTICK, range(1, 11)):
        icon = lip_colors[idx]
        lip_items.append({
            "id": item_id, "name": name, "color": hex_color,
            "tint": clamped_tint(icon, base_lip), "strength": 0.60,
        })
        print(f"  {item_id:20s} icon=#{icon[0]:02X}{icon[1]:02X}{icon[2]:02X} "
              f"tint={lip_items[-1]['tint']}")

    blush_base = alpha_weighted_mean(os.path.join(OUT_ROOT, "blush", "face.png"))
    brow_base = alpha_weighted_mean(os.path.join(OUT_ROOT, "brow", "brow.png"))
    shadow_base = alpha_weighted_mean(os.path.join(OUT_ROOT, "eyeshadow", "eyeshadow.png"))
    print(f"  texture bases: blush={blush_base} brow={brow_base} shadow={shadow_base}")

    def palette_items(palette, base):
        out = []
        for item_id, name, hex_color in palette:
            out.append({
                "id": item_id, "name": name, "color": hex_color,
                "tint": clamped_tint(hex_rgb(hex_color), base), "strength": 0.60,
            })
        return out

    lash_items = [{
        "id": item_id, "name": name, "color": hex_color,
        "tint": [1.0, 1.0, 1.0], "strength": strength,
    } for item_id, name, hex_color, strength in EYELASH]

    catalog = {
        "version": 1,
        "canvas": [750, 1334],
        "face": {"eyeL": eye_left, "eyeR": eye_right, "mouth": mouth_c},
        "categories": [
            {
                "id": 402, "name": "Lipstick", "tag": "MASK_LUT", "blend": "LUT",
                "anchor": [310, 714], "imageScale": 0.99,
                "mask": "lipstick/mouth.png", "lut": "lipstick/table.png",
                "items": lip_items,
            },
            {
                "id": 403, "name": "Blush", "tag": "BLUSH", "blend": "MULTIPLY",
                "anchor": [0, 455], "imageScale": 1.0,
                "mask": "blush/face.png",
                "items": palette_items(BLUSH, blush_base),
            },
            {
                "id": 405, "name": "Eyebrows", "tag": "EYE_BROW", "blend": "MULTIPLY",
                "anchor": [144, 440], "imageScale": 1.0,
                "mask": "brow/brow.png",
                "items": palette_items(BROW, brow_base),
            },
            {
                "id": 406, "name": "Eyeshadow", "tag": "EYE_SHADOW", "blend": "MULTIPLY",
                "anchor": [200, 483], "imageScale": 1.0,
                "mask": "eyeshadow/eyeshadow.png",
                "items": palette_items(EYESHADOW, shadow_base),
            },
            {
                "id": 408, "name": "Eyelashes", "tag": "EYE_LASH", "blend": "MULTIPLY",
                "anchor": [200, 483], "imageScale": 1.0,
                "mask": "eyelash/eyelash.png",
                "items": lash_items,
            },
        ],
    }

    out_json = os.path.join(OUT_ROOT, "catalog.json")
    with open(out_json, "w") as f:
        json.dump(catalog, f, indent=2)
        f.write("\n")
    print(f"== wrote {os.path.relpath(out_json, CAMSTAR)} ==")
    total = sum(len(c["items"]) for c in catalog["categories"])
    print(f"done: {len(catalog['categories'])} categories, {total} items")


if __name__ == "__main__":
    main()
