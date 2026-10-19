#!/usr/bin/env python3
"""
Build CamStar's server-served store assets: My Bag cosmetic icons and gift icons.

Provenance — why this script exists (same rule as build_beauty_assets.py /
build_grade_assets.py): the API advertises these images as static media under
<PUBLIC_ORIGIN>/assets/cosmetics/*.png and /assets/gifts/*.png. They are NOT
third-party art — every icon here is drawn procedurally from Pillow primitives
(rings, silhouettes, gradients), so the repo ships no borrowed pixels.

media-seed/ is gitignored (generated, large binaries); this script is the
reproducible source. Run it after cloning, and upload the outputs to the
server's MEDIA_ROOT (default /srv/camstar/media):

  camstar/media-seed/cosmetics/frame_gold.png    avatar frame: 24K gold band
  camstar/media-seed/cosmetics/frame_neon.png    avatar frame: cyan/magenta neon
  camstar/media-seed/cosmetics/frame_royal.png   avatar frame: royal purple crown
  camstar/media-seed/cosmetics/frame_rose.png    avatar frame: rose blossom
  camstar/media-seed/cosmetics/ride_car.png      entry ride: Supercar GT
  camstar/media-seed/cosmetics/ride_dragon.png   entry ride: Golden Dragon
  camstar/media-seed/cosmetics/bubble_neon.png   chat bubble: neon glow
  camstar/media-seed/cosmetics/medal_poet.png    medal: AI Mushaira Poet
  camstar/media-seed/gifts/rose.png              gift: Rose
  camstar/media-seed/gifts/chai.png              gift: Chai
  camstar/media-seed/gifts/sparkler.png          gift: Diwali Sparkler/Fireworks
  camstar/media-seed/gifts/supercar.png          gift: Golden Supercar
  camstar/media-seed/gifts/palace.png            gift: Taj Palace

Run:  python3 camstar/scripts/build_store_assets.py [--out DIR]
"""

import argparse
import math
import os
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("Pillow is required: pip install Pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
CAMSTAR = os.path.dirname(HERE)
DEFAULT_OUT = os.path.join(CAMSTAR, "media-seed")

SIZE = 256  # shipped icon size
SS = 4  # supersample factor: draw at 1024, downscale with LANCZOS
C = SIZE * SS // 2  # canvas centre in drawing space

GOLD = (245, 195, 71)
GOLD_LIGHT = (255, 233, 170)
GOLD_DARK = (191, 140, 22)
CYAN = (0, 229, 255)
MAGENTA = (255, 0, 200)
ROYAL = (123, 77, 255)
ROSE = (255, 111, 165)
ROSE_DARK = (219, 68, 126)


def canvas():
    return Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))


def save(img, out_dir, name):
    os.makedirs(out_dir, exist_ok=True)
    img.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(out_dir, name))
    print("  " + os.path.relpath(os.path.join(out_dir, name)))


def ring(d, r, width, color, glow=True):
    """Circle band centred on the canvas with a soft glow underneath."""
    box = [C - r, C - r, C + r, C + r]
    if glow:
        for extra, alpha in (
            (int(width * 1.9), 36),
            (int(width * 1.45), 64),
            (int(width * 1.15), 108),
        ):
            d.ellipse(box, outline=color + (alpha,), width=extra)
    d.ellipse(box, outline=color + (255,), width=width)


def star(cx, cy, outer, inner, points=5):
    pts = []
    for i in range(points * 2):
        r = outer if i % 2 == 0 else inner
        a = -math.pi / 2 + i * math.pi / points
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def draw_car(d, color, glass):
    """Side-view sports car facing right with motion streaks behind it."""
    for i, (x0, thick, alpha) in enumerate(((76, 30, 150), (50, 22, 110), (30, 16, 80))):
        y = 545 + i * 52
        d.rounded_rectangle([x0, y, x0 + 150, y + thick], radius=thick // 2, fill=color + (alpha,))
    body = [
        (231, 700), (266, 590), (446, 590), (541, 495), (751, 495),
        (831, 590), (891, 600), (906, 660), (881, 715), (241, 715),
    ]
    d.polygon(body, fill=color + (255,))
    d.polygon([(561, 512), (724, 512), (788, 588), (496, 588)], fill=glass + (255,))
    d.rounded_rectangle([426, 700, 676, 726], radius=13, fill=GOLD_LIGHT + (200,))
    for cx in (376, 811):
        d.ellipse([cx - 88, 632, cx + 88, 808], fill=(30, 30, 36, 255))
        d.ellipse([cx - 44, 676, cx + 44, 764], fill=GOLD_LIGHT + (255,))


def _horn(d, p0, p1, p2, w0, w1, color, steps=24):
    """Filled tapered horn: quadratic Bezier path with width w0 -> w1."""
    left, right = [], []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        x = u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0]
        y = u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]
        dx = 2 * u * (p1[0] - p0[0]) + 2 * t * (p2[0] - p1[0])
        dy = 2 * u * (p1[1] - p0[1]) + 2 * t * (p2[1] - p1[1])
        length = math.hypot(dx, dy) or 1
        nx, ny = -dy / length, dx / length
        w = w0 + (w1 - w0) * t
        left.append((x + nx * w / 2, y + ny * w / 2))
        right.append((x - nx * w / 2, y - ny * w / 2))
    d.polygon(left + right[::-1], fill=color + (255,))


def draw_dragon(d, body_color, horn_color, muzzle_color, flame_color):
    """Golden dragon head: tapered horns, angry brows, fanged muzzle, flame crest."""
    _horn(d, (306, 400), (128, 260), (128, 140), 104, 12, horn_color)
    _horn(d, (718, 400), (896, 260), (896, 140), 104, 12, horn_color)
    # Cheek frills.
    d.polygon([(284, 560), (150, 616), (288, 646)], fill=horn_color + (255,))
    d.polygon([(740, 560), (874, 616), (736, 646)], fill=horn_color + (255,))
    # Head and muzzle.
    d.ellipse([270, 320, 754, 810], fill=body_color + (255,))
    d.rounded_rectangle([382, 636, 642, 892], radius=110, fill=muzzle_color + (255,))
    # Flame crest between the horns.
    d.polygon([(468, 358), (492, 252), (512, 312), (532, 252), (556, 358)], fill=flame_color + (255,))
    # Angry brows: inner ends dip toward the muzzle.
    d.polygon([(316, 446), (486, 512), (486, 560), (330, 494)], fill=horn_color + (255,))
    d.polygon([(708, 446), (538, 512), (538, 560), (694, 494)], fill=horn_color + (255,))
    # Eyes.
    d.ellipse([344, 496, 436, 580], fill=(20, 14, 8, 255))
    d.ellipse([588, 496, 680, 580], fill=(20, 14, 8, 255))
    d.ellipse([378, 512, 404, 538], fill=(255, 252, 240, 255))
    d.ellipse([622, 512, 648, 538], fill=(255, 252, 240, 255))
    # Nostrils and fangs.
    d.ellipse([458, 684, 492, 718], fill=horn_color + (255,))
    d.ellipse([522, 684, 556, 718], fill=horn_color + (255,))
    d.polygon([(428, 874), (464, 802), (500, 874)], fill=(255, 252, 240, 255))
    d.polygon([(524, 874), (560, 802), (596, 874)], fill=(255, 252, 240, 255))


def draw_bubble(d, color):
    """Rounded chat bubble with a neon rim, tail and three dots."""
    box = [150, 230, 874, 690]
    for extra, alpha in ((40, 40), (24, 76)):
        d.rounded_rectangle(
            [box[0] - extra, box[1] - extra, box[2] + extra, box[3] + extra],
            radius=112 + extra,
            outline=color + (alpha,),
            width=40,
        )
    d.rounded_rectangle(box, radius=112, fill=(16, 18, 34, 242), outline=color + (255,), width=28)
    d.polygon([(272, 650), (410, 650), (268, 896)], fill=(16, 18, 34, 242))
    d.line([(272, 656), (268, 890)], fill=color + (255,), width=34)
    d.line([(410, 656), (274, 882)], fill=color + (255,), width=34)
    for x in (350, 512, 674):
        d.ellipse([x - 46, 402, x + 46, 494], fill=color + (255,))


def draw_medal(d, ribbon, ribbon_light, disc):
    """Medal: folded ribbon over a gold disc carrying a star."""
    d.polygon([(598, 96), (712, 96), (630, 356), (566, 300)], fill=(168, 30, 34, 255))
    d.polygon([(426, 96), (540, 96), (470, 300), (394, 350)], fill=ribbon + (255,))
    d.polygon([(438, 96), (528, 96), (492, 286), (438, 262)], fill=ribbon_light + (255,))
    d.ellipse([C - 258, 322, C + 258, 838], fill=disc + (255,))
    d.ellipse([C - 258, 322, C + 258, 838], outline=GOLD_DARK + (255,), width=26)
    d.ellipse([C - 172, 408, C + 172, 752], outline=GOLD_LIGHT + (230,), width=20)
    d.polygon(star(C, 580, 150, 62), fill=(255, 252, 240, 255))


def draw_rose(d, petals, petal_light, stem, leaf):
    """Top-view rose bloom over a stem with one leaf."""
    d.line([(C, 560), (C, 906)], fill=stem + (255,), width=34)
    d.polygon([(C, 700), (646, 646), (706, 706), (582, 786)], fill=leaf + (255,))
    d.polygon([(C, 760), (382, 716), (330, 772), (442, 830)], fill=leaf + (200,))
    d.ellipse([C - 250, 110, C + 250, 610], fill=(174, 24, 47, 255))
    for i in range(10):
        a = i * math.pi / 5
        x, y = C + 158 * math.cos(a), 360 + 158 * math.sin(a)
        d.ellipse([x - 105, y - 105, x + 105, y + 105], fill=petals + (255,))
    for i in range(7):
        a = -math.pi / 2 + i * 2 * math.pi / 7
        x, y = C + 96 * math.cos(a), 360 + 96 * math.sin(a)
        d.ellipse([x - 96, y - 96, x + 96, y + 96], fill=petal_light + (255,))
    d.ellipse([C - 84, 276, C + 84, 444], fill=(255, 138, 150, 255))
    d.ellipse([C - 46, 314, C + 46, 406], fill=(120, 12, 30, 255))


def draw_chai(d, tea, tea_dark, steam):
    """Steaming chai kulhad: earthen cup with tea surface and handle."""
    d.polygon([(360, 430), (664, 430), (612, 816), (412, 816)], fill=(176, 96, 54, 255))
    d.polygon([(386, 470), (638, 470), (596, 786), (428, 786)], fill=tea_dark + (255,))
    d.ellipse([360, 386, 664, 474], fill=(226, 202, 178, 255))
    d.ellipse([C - 124, 316 + 106, C + 124, 430 + 66], fill=tea + (255,))
    d.arc([620, 520, 800, 700], start=300, end=60, fill=(226, 202, 178, 255), width=40)
    for i, (x0, bend) in enumerate(((432, -34), (512, 0), (592, 34))):
        d.arc([x0 - 60 + bend, 120, x0 + 60 + bend, 330], start=110, end=250, fill=steam + (170,), width=18)
    d.line([(388, 560), (636, 560)], fill=(140, 74, 40, 120), width=14)


def draw_sparkler(d, stick, spark, core):
    """Sparkler: diagonal stick with a radial burst at the tip."""
    tip = (430, 470)
    d.line([(624, 910), tip[0] + 20, tip[1] + 20], fill=stick + (255,), width=40)
    for i in range(16):
        a = i * math.pi / 8
        length = 200 + (i % 3) * 52
        x1 = tip[0] + length * math.cos(a)
        y1 = tip[1] + length * math.sin(a)
        d.line([tip[0] + 60 * math.cos(a), tip[1] + 60 * math.sin(a), x1, y1], fill=spark + (225,), width=15)
        d.ellipse([x1 - 19, y1 - 19, x1 + 19, y1 + 19], fill=spark + (255,))
    d.ellipse([tip[0] - 96, tip[1] - 96, tip[0] + 96, tip[1] + 96], fill=spark + (70,))
    d.ellipse([tip[0] - 56, tip[1] - 56, tip[0] + 56, tip[1] + 56], fill=core + (255,))


def draw_palace(d, stone, stone_dark):
    """Taj-style palace silhouette: dome, plinth, arched doorways, minarets."""
    d.rectangle([392, 470, 632, 830], fill=stone + (255,))
    d.ellipse([C - 118, 292, C + 118, 528], fill=stone + (255,))
    d.line([(C, 246), (C, 292)], fill=stone + (255,), width=16)
    d.ellipse([C - 18, 236, C + 18, 272], fill=stone + (255,))
    d.rectangle([472, 470, 552, 560], fill=stone + (255,))
    for x in (300, 676):
        d.rectangle([x, 420, x + 48, 830], fill=stone + (255,))
        d.ellipse([x - 2, 380, x + 50, 436], fill=stone + (255,))
        d.line([(x + 24, 356), (x + 24, 388)], fill=stone + (255,), width=12)
    d.rectangle([350, 830, 674, 890], fill=stone + (255,))
    d.rounded_rectangle([470, 640, 554, 830], radius=42, fill=stone_dark + (255,))
    for x in (430, 594):
        d.rounded_rectangle([x, 700, x + 50, 830], radius=25, fill=stone_dark + (255,))


def build(out_root):
    print("cosmetics:")
    d = ImageDraw.Draw(canvas())

    img = canvas()
    d = ImageDraw.Draw(img)
    ring(d, 330, 58, GOLD)
    d.arc([C - 330, C - 330, C + 330, C + 330], start=-64, end=56, fill=GOLD_LIGHT + (255,), width=20)
    save(img, os.path.join(out_root, "cosmetics"), "frame_gold.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    ring(d, 330, 58, CYAN)
    d.arc([C - 330, C - 330, C + 330, C + 330], start=272, end=448, fill=MAGENTA + (255,), width=58)
    save(img, os.path.join(out_root, "cosmetics"), "frame_neon.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    ring(d, 330, 58, ROYAL)
    d.ellipse([C - 348, C - 348, C + 348, C + 348], outline=GOLD + (255,), width=14)
    d.polygon([(392, 252), (444, 158), (C, 246), (580, 158), (632, 252)], fill=GOLD + (255,))
    d.rectangle([392, 252, 632, 296], fill=GOLD_DARK + (255,))
    d.ellipse([(C - 24), 116, (C + 24), 164], fill=GOLD_LIGHT + (255,))
    save(img, os.path.join(out_root, "cosmetics"), "frame_royal.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    ring(d, 330, 58, ROSE)
    for i in range(6):
        a = -math.pi / 2 + i * math.pi / 3
        x, y = C + 330 * math.cos(a), C + 330 * math.sin(a)
        d.ellipse([x - 46, y - 46, x + 46, y + 46], fill=(255, 170, 195, 255), outline=ROSE_DARK + (255,), width=12)
    save(img, os.path.join(out_root, "cosmetics"), "frame_rose.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    draw_car(d, GOLD, (58, 70, 92))
    save(img, os.path.join(out_root, "cosmetics"), "ride_car.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    draw_dragon(d, GOLD, GOLD_DARK, (228, 172, 58), (255, 120, 40))
    save(img, os.path.join(out_root, "cosmetics"), "ride_dragon.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    draw_bubble(d, CYAN)
    save(img, os.path.join(out_root, "cosmetics"), "bubble_neon.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    draw_medal(d, (206, 48, 52), (236, 92, 92), GOLD)
    save(img, os.path.join(out_root, "cosmetics"), "medal_poet.png")

    print("gifts:")
    img = canvas()
    d = ImageDraw.Draw(img)
    draw_rose(d, (214, 51, 74), (250, 118, 132), (58, 126, 68), (74, 152, 84))
    save(img, os.path.join(out_root, "gifts"), "rose.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    draw_chai(d, (232, 146, 64), (196, 108, 52), (255, 255, 255))
    save(img, os.path.join(out_root, "gifts"), "chai.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    draw_sparkler(d, (150, 140, 128), (255, 214, 96), (255, 252, 214))
    save(img, os.path.join(out_root, "gifts"), "sparkler.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    draw_car(d, GOLD, (58, 70, 92))
    save(img, os.path.join(out_root, "gifts"), "supercar.png")

    img = canvas()
    d = ImageDraw.Draw(img)
    draw_palace(d, (242, 232, 216), (206, 190, 168))
    save(img, os.path.join(out_root, "gifts"), "palace.png")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=DEFAULT_OUT, help="output root (default: camstar/media-seed)")
    args = ap.parse_args()
    build(args.out)
    print(f"done -> {args.out}")


if __name__ == "__main__":
    main()
