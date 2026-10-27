#!/usr/bin/env python3
"""Generates the LeanCast launcher icon set with Pillow.

Adaptive icon (API 26+): solid background + foreground PNG (432x432, art inside
the 66% safe zone). Legacy rasters (API 21-25): rounded-square composited PNGs
in mipmap-mdpi..xxxhdpi. Regenerate: python3 tools/gen_icon.py
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
BG = (21, 101, 192, 255)          # deep blue
FG = (255, 255, 255, 255)         # artwork white
ACCENT = (100, 181, 246, 255)     # light blue waves
FG_SIZE = 432                     # 108dp @ xxxhdpi
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def rounded(draw, box, radius, fill, width=None, outline=None):
    draw.rounded_rectangle(box, radius=radius, fill=fill, width=width, outline=outline)


def draw_foreground(size: int) -> Image.Image:
    """TV screen + play triangle + cast waves, centered in the safe zone."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size / 432.0
    lw = max(3, int(14 * s))

    # TV screen (outline) inside safe zone, slightly left to leave room for waves
    tv = (52 * s, 92 * s, 320 * s, 268 * s)
    rounded(d, tv, radius=22 * s, fill=None, width=lw, outline=FG)
    # stand
    d.line((186 * s, 268 * s, 186 * s, 300 * s), fill=FG, width=lw)
    d.line((130 * s, 300 * s, 242 * s, 300 * s), fill=FG, width=lw)
    # play triangle inside screen
    tri = [(150 * s, 140 * s), (150 * s, 220 * s), (222 * s, 180 * s)]
    d.polygon(tri, fill=FG)
    # cast waves bottom-right
    for i, r in enumerate((34 * s, 58 * s, 82 * s)):
        bbox = (332 - r * 1.55, 316 - r, 332 + r * 0.55, 316 + r)
        d.arc(bbox, start=200, end=340, fill=ACCENT, width=max(3, int(11 * s) - i))

    return img


def compose_legacy(size: int) -> Image.Image:
    """Rounded-square legacy icon: bg + foreground scaled to ~78%."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    radius = int(size * 0.22)
    mask = Image.new("L", (size, size), 0)
    rounded(ImageDraw.Draw(mask), (0, 0, size - 1, size - 1), radius, 255)
    bg = Image.new("RGBA", (size, size), BG)
    img.paste(bg, (0, 0), mask)

    fg = draw_foreground(int(size * 0.86))
    off = (size - fg.width) // 2
    img.alpha_composite(fg, (off, off))
    # apply rounded mask to final
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main() -> int:
    # adaptive foreground + background
    fg = draw_foreground(FG_SIZE)
    fgd = RES / "mipmap-xxxhdpi"
    fgd.mkdir(parents=True, exist_ok=True)
    fg.save(fgd / "ic_launcher_foreground.png")

    bg_xml = RES / "mipmap-anydpi-v26"
    bg_xml.mkdir(parents=True, exist_ok=True)
    (bg_xml / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
        '    <background android:drawable="@color/ic_launcher_bg"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        "</adaptive-icon>\n",
        encoding="utf-8",
    )
    colors = RES / "values"
    colors.mkdir(parents=True, exist_ok=True)
    (colors / "ic_launcher_colors.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <color name="ic_launcher_bg">#1565C0</color>\n</resources>\n',
        encoding="utf-8",
    )

    # legacy rasters
    for bucket, size in LEGACY.items():
        d = RES / f"mipmap-{bucket}"
        d.mkdir(parents=True, exist_ok=True)
        compose_legacy(size).save(d / "ic_launcher.png")

    print("icon set written under", RES)
    return 0


if __name__ == "__main__":
    sys.exit(main())
