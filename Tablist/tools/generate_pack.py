#!/usr/bin/env python3
"""
Generates the Tablist resource pack: slices a source image into a custom
bitmap font so it can be displayed inside the (text-only) tab list header,
and builds a matching "divider" bar (a single wide custom-font character)
used for the purple lines above/below the banner and around the footer.

Usage:
    python3 generate_pack.py <source_image> <output_dir>

Outputs into <output_dir>:
    header.png          - the resized banner texture
    header.json          - the bitmap font provider definition for the banner
    header_chars.txt     - the literal string of unicode chars (UTF-8) that,
                            rendered with the "effectsmp:header" font, draws
                            the banner. The plugin reads this file.
    divider.png          - a wide gradient bar texture
    divider.json          - the bitmap font provider definition for the divider
    divider_char.txt      - the single unicode char (UTF-8) that draws it
    pack.mcmeta
    pack.zip              - the finished, zipped resource pack

Feel free to re-run this with different CROP_* / TARGET_WIDTH / HEIGHT /
ASCENT / DIVIDER_* values below and re-zip if the banner or divider looks
mis-sized or mis-aligned in game -- bitmap font scaling can only really be
judged by eye, in a real client.

Both the banner and the divider force the tab list panel to be at least as
wide as whichever one of them is widest (the client sizes the panel to fit
its widest line of header/footer/player-entry text) - that's what makes the
divider line look like it spans "the whole tab list": it doesn't measure
the panel and match it, it defines the panel's width.
"""
import sys
import os
import json
import zipfile
from PIL import Image

# ---- banner tunables -------------------------------------------------
CROP_TOP = 340
CROP_BOTTOM = 740
TARGET_WIDTH = 384
COLUMN_WIDTH = 2          # px per character glyph in the source texture
FONT_HEIGHT = 64          # in-game scaled height (Minecraft font units)
FONT_ASCENT = 8           # vertical baseline alignment
PUA_START = 0xE000        # start of the BMP Private Use Area, for the banner

# ---- divider tunables --------------------------------------------------
DIVIDER_WIDTH_PX = 420    # a little wider than the banner so it's never the
                          # narrower of the two and always "wins" the width
DIVIDER_HEIGHT_PX = 8
DIVIDER_FONT_HEIGHT = 8   # scale 1:1 with the source - keep it a thin line
DIVIDER_FONT_ASCENT = 6
DIVIDER_CHAR = chr(0xE0C0)  # one char, well clear of the banner's PUA range
DIVIDER_EDGE_COLOR = (59, 30, 94, 255)     # dark purple, matches the logo glow
DIVIDER_CENTER_COLOR = (230, 90, 255, 255)  # bright magenta, matches "EFFECT"

# ---- pack.mcmeta tunables ------------------------------------------------
PACK_FORMAT = 34          # 1.21 baseline (bitmap fonts have worked since ~1.13; this
                          # number barely matters, it just avoids a version warning)
SUPPORTED_MIN = 6
SUPPORTED_MAX = 999       # generous upper bound so this never shows an "outdated
                          # pack" warning, now or on future 1.21.x/1.22+ updates
# -----------------------------------------------------------------------


def build_banner(src_path, out_dir):
    im = Image.open(src_path).convert("RGBA")
    w, h = im.size
    cropped = im.crop((0, CROP_TOP, w, min(CROP_BOTTOM, h)))

    aspect = cropped.height / cropped.width
    target_height = round(TARGET_WIDTH * aspect)
    # keep width an exact multiple of COLUMN_WIDTH
    target_width = (TARGET_WIDTH // COLUMN_WIDTH) * COLUMN_WIDTH
    resized = cropped.resize((target_width, target_height), Image.LANCZOS)

    num_chars = target_width // COLUMN_WIDTH
    if num_chars > 6400:
        raise SystemExit("Image too wide for the BMP Private Use Area; "
                          "increase COLUMN_WIDTH or reduce TARGET_WIDTH.")

    chars = "".join(chr(PUA_START + i) for i in range(num_chars))

    header_png = os.path.join(out_dir, "header.png")
    resized.save(header_png)

    with open(os.path.join(out_dir, "header_chars.txt"), "w", encoding="utf-8") as f:
        f.write(chars)

    font_json = {
        "providers": [
            {
                "type": "bitmap",
                "file": "effectsmp:font/header.png",
                "ascent": FONT_ASCENT,
                "height": FONT_HEIGHT,
                "chars": [chars],
            }
        ]
    }
    with open(os.path.join(out_dir, "header.json"), "w", encoding="utf-8") as f:
        json.dump(font_json, f, indent=2)

    print(f"Banner: {num_chars} chars, texture {target_width}x{target_height}px.")
    return header_png


def build_divider(out_dir):
    bar = Image.new("RGBA", (DIVIDER_WIDTH_PX, DIVIDER_HEIGHT_PX))
    center = DIVIDER_WIDTH_PX / 2
    for x in range(DIVIDER_WIDTH_PX):
        # distance from center, 0 at the middle, 1 at the edges
        t = abs(x - center) / center
        t = min(1.0, t)
        color = tuple(
            round(DIVIDER_CENTER_COLOR[i] + (DIVIDER_EDGE_COLOR[i] - DIVIDER_CENTER_COLOR[i]) * t)
            for i in range(4)
        )
        for y in range(DIVIDER_HEIGHT_PX):
            bar.putpixel((x, y), color)

    divider_png = os.path.join(out_dir, "divider.png")
    bar.save(divider_png)

    with open(os.path.join(out_dir, "divider_char.txt"), "w", encoding="utf-8") as f:
        f.write(DIVIDER_CHAR)

    font_json = {
        "providers": [
            {
                "type": "bitmap",
                "file": "effectsmp:font/divider.png",
                "ascent": DIVIDER_FONT_ASCENT,
                "height": DIVIDER_FONT_HEIGHT,
                "chars": [DIVIDER_CHAR],
            }
        ]
    }
    with open(os.path.join(out_dir, "divider.json"), "w", encoding="utf-8") as f:
        json.dump(font_json, f, indent=2)

    print(f"Divider: texture {DIVIDER_WIDTH_PX}x{DIVIDER_HEIGHT_PX}px.")
    return divider_png


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src_path, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

    header_png = build_banner(src_path, out_dir)
    divider_png = build_divider(out_dir)

    pack_mcmeta = {
        "pack": {
            "pack_format": PACK_FORMAT,
            "supported_formats": {"min_inclusive": SUPPORTED_MIN, "max_inclusive": SUPPORTED_MAX},
            "description": "Tablist plugin - header banner"
        }
    }
    with open(os.path.join(out_dir, "pack.mcmeta"), "w", encoding="utf-8") as f:
        json.dump(pack_mcmeta, f, indent=2)

    zip_path = os.path.join(out_dir, "pack.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(out_dir, "pack.mcmeta"), "pack.mcmeta")
        z.write(header_png, "assets/effectsmp/textures/font/header.png")
        z.write(os.path.join(out_dir, "header.json"), "assets/effectsmp/font/header.json")
        z.write(divider_png, "assets/effectsmp/textures/font/divider.png")
        z.write(os.path.join(out_dir, "divider.json"), "assets/effectsmp/font/divider.json")

    print(f"pack.zip -> {zip_path}")


if __name__ == "__main__":
    main()
