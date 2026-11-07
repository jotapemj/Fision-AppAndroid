"""Post-processing of the README captures: captures_out/*.png -> captures_out/final/.

- Status bar cropped (the phone ignores the system UI demo mode).
- Song covers pixelated; the cloud icon over a cover that is not downloaded
  is kept sharp.
- Debug-only "DBG" chip of the 3D scene painted over with the wall behind it.
- Server address replaced by a generic one.

Usage, from the repo root:
    python tools/captures/process.py app/src/main/res/font/noto_sans_regular.ttf
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "captures_out")
DST = os.path.join(SRC, "final")
FONT = sys.argv[1]

STATUS_BAR = 75          # px, from the statusBars inset of the capture phone
BLOCK = 14               # pixelation block (px)
SERVER = "your-server:8000"
SERVER_VERSION = "v2.1.0"    # version shown in the account dialog capture

# Cover boxes (x1, y1, x2, y2) of the list rows: 52 dp covers, 189 px pitch.
COVER_X = (42, 179)
ROW_H = 137
PITCH = 189
LIST_BOTTOM = 2132       # top of the bottom navigation


def rows(first_top, count):
    return [(COVER_X[0], t, COVER_X[1], min(t + ROW_H, LIST_BOTTOM))
            for t in (first_top + i * PITCH for i in range(count))]


def quads(box, keep):
    """2x2 playlist mosaic: every quarter except the [keep] indexes."""
    x1, y1, x2, y2 = box
    mx, my = (x1 + x2) // 2, (y1 + y2) // 2
    q = [(x1, y1, mx, my), (mx, y1, x2, my), (x1, my, mx, y2), (mx, my, x2, y2)]
    return [b for i, b in enumerate(q) if i not in keep]


# Josh Woodward covers (Airplane Mode, Faded War) are CC BY and stay sharp:
# the first two library rows, the first playlist row and the top-left
# quarter of the mosaics. The 3D scene shows Airplane Mode.
COVERS = {
    "b1_library": rows(724, 8)[2:],
    "b2_search": rows(724, 3)[2:],
    "c1_playlists": quads((42, 724, 179, 861), keep={0}),
    "c2_playlist_detail": quads((332, 285, 752, 705), keep={0}) + rows(944, 5)[1:],
}


def pixelate(im, box):
    x1, y1, x2, y2 = box
    region = im.crop(box)
    small = region.resize((max(1, (x2 - x1) // BLOCK), max(1, (y2 - y1) // BLOCK)), Image.BILINEAR)
    blocks = small.resize(region.size, Image.NEAREST)
    # Bright overlay (cloud / lock icon on a dimmed cover) stays on top.
    lum = region.convert("L")
    dimmed = sum(lum.getdata()) / (lum.width * lum.height) < 70
    if dimmed:
        mask = lum.point(lambda v: 0 if v < 110 else min(255, (v - 110) * 255 // 90))
        blocks.paste(region, (0, 0), mask)
    im.paste(blocks, box[:2])


def hide_dbg_chip(im):
    """The chip sits over a dark acoustic panel (left) and the wall (right);
    each part is refilled from the same row a little further out."""
    x1, y1, x2, y2 = 32, 364, 138, 426
    px = im.load()
    out = im.copy()
    po = out.load()
    for y in range(y1, y2):
        # Panel right edge, slanted with the perspective.
        edge = 69 + (y - 340) * 0.182
        for x in range(x1, x2):
            if x < edge:
                po[x, y] = px[4 + (x % 24), y]      # panel texture at the screen edge
            else:
                po[x, y] = px[139 + (x % 40), y]    # wall strip right of the chip
    patch = out.crop((x1, y1, x2, y2)).filter(ImageFilter.GaussianBlur(0.6))
    im.paste(patch, (x1, y1))


def replace_text(im, box, bg, text, color, size, x=None, y_base=None):
    """Paints [box] with [bg] and writes [text] left-aligned at its start."""
    d = ImageDraw.Draw(im)
    d.rectangle(box, fill=bg)
    f = ImageFont.truetype(FONT, size)
    d.text((x if x is not None else box[0], y_base), text, font=f, fill=color, anchor="ls")


def fix_e1(im):
    # "http://jotapeveloper-server:8000", 13 sp grey under the user name.
    replace_text(im, (326, 404, 712, 450), (20, 20, 20), "http://" + SERVER,
                 (122, 122, 122), 25, x=328, y_base=436)


def fix_e2(im):
    # Server pill: icon + "host:port · version", centred under the name.
    pill = (285, 1236, 797, 1298)
    icon = im.crop((300, 1244, 352, 1290))
    bg = (24, 24, 24)
    d = ImageDraw.Draw(im)
    d.rectangle((pill[0] - 4, pill[1] - 4, pill[2] + 4, pill[3] + 4), fill=bg)
    f = ImageFont.truetype(FONT, 25)
    text = SERVER + " · " + SERVER_VERSION
    tw = f.getbbox(text)[2]
    pad_l, gap, pad_r = 300 - pill[0], 369 - 352, pill[2] - 761
    w = pad_l + icon.width + gap + tw + pad_r
    cx = (pill[0] + pill[2]) // 2
    left = cx - w // 2
    h = pill[3] - pill[1]
    d.rounded_rectangle((left, pill[1], left + w, pill[3]), radius=h // 2,
                        outline=(44, 44, 44), width=3)
    im.paste(icon, (left + pad_l, 1244))
    d.text((left + pad_l + icon.width + gap, 1274), text, font=f, fill=(207, 207, 207), anchor="ls")


FIXES = {"e1_settings": fix_e1, "e2_account": fix_e2}


def main():
    os.makedirs(DST, exist_ok=True)
    for name in sorted(os.listdir(SRC)):
        if not name.endswith(".png"):
            continue
        key = name[:-4]
        im = Image.open(os.path.join(SRC, name)).convert("RGB")
        for box in COVERS.get(key, []):
            pixelate(im, box)
        if key in ("d4_scene_3d", "d6_dance"):
            hide_dbg_chip(im)
        if key in FIXES:
            FIXES[key](im)
        im = im.crop((0, STATUS_BAR, im.width, im.height))
        im.save(os.path.join(DST, name), optimize=True)
        print("  " + name)


if __name__ == "__main__":
    main()
