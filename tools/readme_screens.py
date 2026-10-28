"""Screenshot images for the README and the user guide, from the processed
captures (tools/captures/process.py writes them to captures_out/final).

Every capture becomes a card: rounded corners, a thin border and a
transparent outside, so it sits well on both GitHub themes.

Usage, from the repo root:
    python tools/readme_screens.py
Writes docs/readme/screens-*.png and docs/screenshots/<capture>.png.
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "captures", "captures_out", "final")
README_DIR = os.path.join(ROOT, "docs", "readme")
GUIDE_DIR = os.path.join(ROOT, "docs", "screenshots")

BORDER = (61, 68, 77, 255)     # GitHub border grey, readable on both themes
GAP = 28

# Strips for the README (card width in px).
STRIPS = {
    "screens-hero.png": (["b1_library", "d1_player", "d3_lyrics", "d4_scene_3d"], 400),
    "screens-more-1.png": (["d2_chord_diagrams", "d6_dance", "d7_stem_options", "d8_metronome"], 400),
    "screens-more-2.png": (["c2_playlist_detail", "b2_search", "d11_export", "e1_settings"], 400),
    # Android Auto head unit captures (1920x720 landscape).
    "screens-auto.png": (["f1_auto_library", "f2_auto_player"], 828),
}

# Single cards for the guide.
GUIDE = [
    "a1_library_empty", "b1_library", "b2_search", "b4_add_song", "c2_playlist_detail",
    "d1_player", "d2_chord_diagrams", "d3_lyrics", "d4_scene_3d",
    "d6_dance", "d7_stem_options", "d8_metronome", "d9_pitch", "d10_drawer",
    "d11_export", "e1_settings", "e2_account",
]
GUIDE_W = 540
# Textured 3D scene: as JPEG it weighs a sixth. JPEG has no transparency, so
# these cards keep square corners on the dark scene instead.
GUIDE_JPEG = {"d4_scene_3d", "d6_dance"}


def card(name, width):
    im = Image.open(os.path.join(SRC, name + ".png")).convert("RGBA")
    h = round(im.height * width / im.width)
    im = im.resize((width, h), Image.LANCZOS)
    r = round(min(width, h) * 0.085)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, h - 1), radius=r, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    ImageDraw.Draw(out).rounded_rectangle((0, 0, width - 1, h - 1), radius=r,
                                          outline=BORDER, width=2)
    return out


def strip(names, width):
    cards = [card(n, width) for n in names]
    s = Image.new("RGBA", (sum(c.width for c in cards) + GAP * (len(cards) - 1),
                           max(c.height for c in cards)), (0, 0, 0, 0))
    x = 0
    for c in cards:
        s.alpha_composite(c, (x, 0))
        x += c.width + GAP
    return s


def main():
    os.makedirs(README_DIR, exist_ok=True)
    os.makedirs(GUIDE_DIR, exist_ok=True)
    for out, (names, width) in STRIPS.items():
        strip(names, width).save(os.path.join(README_DIR, out), optimize=True)
        print("  docs/readme/" + out)
    for name in GUIDE:
        if name in GUIDE_JPEG:
            im = Image.open(os.path.join(SRC, name + ".png")).convert("RGB")
            im = im.resize((GUIDE_W, round(im.height * GUIDE_W / im.width)), Image.LANCZOS)
            im.save(os.path.join(GUIDE_DIR, name + ".jpg"), quality=85, optimize=True, progressive=True)
            print("  docs/screenshots/" + name + ".jpg")
            continue
        card(name, GUIDE_W).save(os.path.join(GUIDE_DIR, name + ".png"), optimize=True)
        print("  docs/screenshots/" + name + ".png")


if __name__ == "__main__":
    main()
