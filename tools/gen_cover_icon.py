#!/usr/bin/env python3
"""Draws the MatChat mark for the Kyocera cover card's icon slot.

The cover template draws the icon from a resource id only (no runtime
images), scaled into a small square slot; a 96x96 nodpi square drew best on
the DuraXV (docs/COVER-DISPLAY.md, "Clock on the card"). The outer screen is
black and white, so this is the monochrome form of the same bubble+"M"
geometry as app/src/main/res/drawable/ic_launcher.xml: a solid black bubble
with the "M" knocked out (transparent), on transparent, scaled to fill most
of the square. (The launcher itself is the colour version — indigo bubble,
black "M" — but the geometry is shared.) Re-run when the launcher mark's
geometry changes:

    python3 tools/gen_cover_icon.py

Needs Pillow.
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 96
SUPERSAMPLE = 8
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable-nodpi/ic_cover_matchat.png"

# The bubble in ic_launcher.xml's 48-unit viewport: a rounded body from
# (5,10) to (43,38), corner radius 5 — centred in the square — with a tail
# down to (13,46).
BODY = (5, 10, 43, 38)
RADIUS = 5
TAIL = [(20, 38), (13, 46), (13, 37)]
# The bubble's bounding box in that viewport (body plus tail).
BOX = (5, 10, 43, 46)
# The "M" cut out of the body (transparent), the same points as the second
# sub-path of ic_launcher.xml.
M_CUTOUT = [
    (14.2, 31.84), (14.2, 16.16), (17.96, 16.16), (24, 25.25), (30.04, 16.16), (33.8, 16.16),
    (33.8, 31.84), (30.04, 31.84), (30.04, 22.56), (24, 29.58), (17.96, 22.56), (17.96, 31.84),
]
FILL = 0.9  # share of the square the bubble's larger side takes


def main() -> None:
    big = SIZE * SUPERSAMPLE
    w, h = BOX[2] - BOX[0], BOX[3] - BOX[1]
    scale = big * FILL / max(w, h)
    off_x = (big - w * scale) / 2 - BOX[0] * scale
    off_y = (big - h * scale) / 2 - BOX[1] * scale

    def pt(x: float, y: float) -> tuple[float, float]:
        return (x * scale + off_x, y * scale + off_y)

    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([pt(BODY[0], BODY[1]), pt(BODY[2], BODY[3])], radius=RADIUS * scale, fill=(0, 0, 0, 255))
    draw.polygon([pt(x, y) for x, y in TAIL], fill=(0, 0, 0, 255))
    draw.polygon([pt(x, y) for x, y in M_CUTOUT], fill=(0, 0, 0, 0))  # transparent "M"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.resize((SIZE, SIZE), Image.LANCZOS).save(OUT, optimize=True)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
