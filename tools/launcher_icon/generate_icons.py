#!/usr/bin/env python3
"""Generate RescueAuth v2 Android launcher icons from src/icon.svg.

Outputs (all derived from the single source SVG, reproducible):

  res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png        - legacy full icon
  res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png  - legacy round icon
  res/mipmap-anydpi-v26/ic_launcher.xml                  - adaptive icon
  res/mipmap-anydpi-v26/ic_launcher_round.xml            - adaptive round
  res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_background.png  - adaptive bg (gradient)
  res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png  - adaptive fg (ring + hands)
  res/drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_monochrome.png  - Android 13 themed layer

Run:  python3 tools/launcher_icon/generate_icons.py
Requires: pip install cairosvg pillow
"""
from __future__ import annotations

import io
import os
import sys
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "src", "icon.svg")
APP_RES = os.path.abspath(os.path.join(ROOT, "..", "..", "app", "src", "main", "res"))

# Density -> px size of a 108dp adaptive canvas (and of a 48dp legacy icon)
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def svg_to_png(svg_bytes: bytes, width: int, height: int) -> bytes:
    return cairosvg.svg2png(bytestring=svg_bytes, output_width=width, output_height=height)


def ensure_dir(path: str) -> None:
    os.makedirs(path, exist_ok=True)


def write_png(path: str, png: bytes) -> None:
    ensure_dir(os.path.dirname(path))
    with open(path, "wb") as f:
        f.write(png)
    print(f"  {os.path.relpath(path, os.path.join(APP_RES, '..', '..', '..'))}")


# ---------------------------------------------------------------------------
# 1. Legacy full icons (the design as-is: gradient tile circle + ring + hands)
#    rendered on a transparent 512x512 canvas, scaled to each density.
# ---------------------------------------------------------------------------
def _circular_crop(img: Image.Image) -> Image.Image:
    """Return a copy of the icon masked to a circle (for the round launcher)."""
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w - 1, h - 1), fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def gen_legacy(icon_svg: bytes) -> None:
    for density, scale in DENSITIES.items():
        px = int(round(48 * scale))
        png = svg_to_png(icon_svg, px, px)
        write_png(os.path.join(APP_RES, f"mipmap-{density}", "ic_launcher.png"), png)
        # Round icon = circular-cropped legacy icon (design is already round).
        round_img = _circular_crop(Image.open(io.BytesIO(png)).convert("RGBA"))
        rbuf = io.BytesIO()
        round_img.save(rbuf, "PNG")
        write_png(os.path.join(APP_RES, f"mipmap-{density}", "ic_launcher_round.png"), rbuf.getvalue())


# ---------------------------------------------------------------------------
# 2. Adaptive foreground SVG (108x108 viewport, transparent).
#    Content = token ring (dashed, round cap) + clock hands + hub, white family.
#    Geometry preserves the proportions of the source SVG, scaled so the ring
#    outer radius (31dp) sits inside the adaptive safe zone (66dp diameter).
# ---------------------------------------------------------------------------
BACKGROUND_SVG = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#8da2f5"/>
      <stop offset="45%" stop-color="#6f83ec"/>
      <stop offset="100%" stop-color="#4c56c9"/>
    </linearGradient>
  </defs>
  <rect width="108" height="108" fill="url(#bg)"/>
</svg>
"""

FOREGROUND_SVG = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <defs>
    <linearGradient id="ring" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#ffffff"/>
      <stop offset="100%" stop-color="#c6d4ff"/>
    </linearGradient>
  </defs>
  <!-- Token ring with a gap (bottom-right), rotating-code meaning.
       Ring: outer r=31, stroke 8 -> inner r=23. Circumference=2*pi*27=169.65.
       Gap of ~53.5deg (=140/942.5 of the source ring) -> dasharray 144.4 25.2. -->
  <g transform="rotate(45 54 54)">
    <circle cx="54" cy="54" r="27" fill="none" stroke="url(#ring)" stroke-width="8"
            stroke-linecap="round" stroke-dasharray="144.4 25.2"/>
  </g>
  <!-- Rounded clock hands: minute -120deg, hour +60deg (proportions of source). -->
  <g stroke-linecap="round">
    <line x1="54" y1="54" x2="54" y2="41" stroke="#ffffff" stroke-width="3.3"
          transform="rotate(-120 54 54)"/>
    <line x1="54" y1="56" x2="54" y2="48.4" stroke="#eef2ff" stroke-width="4.4"
          transform="rotate(60 54 54)"/>
  </g>
  <circle cx="54" cy="54" r="2.7" fill="#ffffff"/>
</svg>
"""


def gen_adaptive(icon_svg: bytes) -> None:
    fg_svg = FOREGROUND_SVG.encode("utf-8")
    bg_svg = BACKGROUND_SVG.encode("utf-8")
    for density, scale in DENSITIES.items():
        px = int(round(108 * scale))
        fg = svg_to_png(fg_svg, px, px)
        write_png(
            os.path.join(APP_RES, f"drawable-{density}", "ic_launcher_foreground.png"), fg
        )

        # Background: the full adaptive canvas filled with the source gradient.
        bg = svg_to_png(bg_svg, px, px)
        write_png(
            os.path.join(APP_RES, f"drawable-{density}", "ic_launcher_background.png"), bg
        )

        # Monochrome layer: flat white silhouette of the foreground design.
        mono = Image.open(io.BytesIO(fg)).convert("RGBA")
        alpha = mono.getchannel("A")
        white = Image.new("RGBA", mono.size, (255, 255, 255, 255))
        white.putalpha(alpha)
        mbuf = io.BytesIO()
        white.save(mbuf, "PNG")
        write_png(
            os.path.join(APP_RES, f"drawable-{density}", "ic_launcher_monochrome.png"),
            mbuf.getvalue(),
        )

    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
"""
    anydpi = os.path.join(APP_RES, "mipmap-anydpi-v26")
    ensure_dir(anydpi)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
            f.write(adaptive)
        print(f"  {name}")


def validate() -> None:
    """Verify generated assets: PNG decode + expected dimensions, XML well-formed."""
    print("\nValidating assets ...")
    errors = []
    expected = {
        "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
        "drawable-mdpi": 108, "drawable-hdpi": 162, "drawable-xhdpi": 216,
        "drawable-xxhdpi": 324, "drawable-xxxhdpi": 432,
    }
    for d, exp in expected.items():
        base = os.path.join(APP_RES, d)
        if not os.path.isdir(base):
            errors.append(f"missing dir {d}")
            continue
        for fname in sorted(os.listdir(base)):
            p = os.path.join(base, fname)
            try:
                with Image.open(p) as im:
                    im.verify()
                with Image.open(p) as im:
                    w, h = im.size
                if (w, h) != (exp, exp):
                    errors.append(f"{p}: size {w}x{h} != {exp}x{exp}")
                print(f"  ok  {d}/{fname} {w}x{h}")
            except Exception as e:  # noqa: BLE001
                errors.append(f"{p}: {e}")
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        p = os.path.join(APP_RES, "mipmap-anydpi-v26", name)
        ET.parse(p)  # raises if malformed
        print(f"  ok  mipmap-anydpi-v26/{name}")
    if errors:
        print("\nERRORS:")
        for e in errors:
            print("  -", e)
        sys.exit(1)
    print("All assets valid.")


def main() -> None:
    if not os.path.exists(SRC):
        sys.exit(f"source SVG not found: {SRC}")
    with open(SRC, "rb") as f:
        icon_svg = f.read()
    print("Generating legacy icons ...")
    gen_legacy(icon_svg)
    print("Generating adaptive icons ...")
    gen_adaptive(icon_svg)
    validate()


if __name__ == "__main__":
    main()
