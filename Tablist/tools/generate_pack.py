#!/usr/bin/env python3
"""
Generates the Tablist resource pack: slices a source image into a custom
bitmap font so it can be displayed inside the (text-only) tab list header.

Usage:
    python3 generate_pack.py <source_image> <output_dir>

Outputs into <output_dir>:
    header.png        - the resized banner texture
    header.json        - the bitmap font provider definition
    header_chars.txt   - the literal string of unicode chars (UTF-8) that,
                          rendered with the "effectsmp:header" font, draws
                          the banner. The plugin reads this file.
    pack.mcmeta
    pack.zip            - the finished, zipped resource pack

Feel free to re-run this with different CROP_* / TARGET_WIDTH / HEIGHT /
ASCENT values below and re-zip if the banner looks mis-sized or
mis-aligned in game -- bitmap font scaling can only really be judged by
eye, in a real client.
"""
import sys
import os
import zipfile
from PIL import Image

# ---- tunables -------------------------------------------------------
CROP_TOP = 340
CROP_BOTTOM = 740
TARGET_WIDTH = 384
COLUMN_WIDTH = 2          # px per character glyph in the source texture
FONT_HEIGHT = 64          # in-game scaled height (Minecraft font units)
FONT_ASCENT = 8           # vertical baseline alignment
PACK_FORMAT = 34          # 1.21 baseline (bitmap fonts have worked since ~1.13; this
                          # number barely matters, it just avoids a version warning)
SUPPORTED_MIN = 6
SUPPORTED_MAX = 999       # generous upper bound so this never shows an "outdated
                          # pack" warning, now or on future 1.21.x/1.22+ updates
PUA_START = 0xE000        # start of the BMP Private Use Area
# -----------------------------------------------------------------------

def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src_path, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

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
    import json
    with open(os.path.join(out_dir, "header.json"), "w", encoding="utf-8") as f:
        json.dump(font_json, f, indent=2)

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

    print(f"Done. {num_chars} chars, texture {target_width}x{target_height}px.")
    print(f"pack.zip -> {zip_path}")

if __name__ == "__main__":
    main()
