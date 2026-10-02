#!/usr/bin/env python3
"""Build a side-by-side and an overlay image from an app capture and a Figma export of the same node.

Usage: design-compare.py APP_PNG FIGMA_PNG OUT_PREFIX

Writes OUT_PREFIX-side-by-side.png (Figma left, app right, each labelled) and OUT_PREFIX-overlay.png
(the two blended 50/50). The Figma export is scaled to the capture's pixel size when they differ, so a
capture at density 1.0 and an export at 1x line up pixel for pixel. Needs Pillow.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

LABEL_HEIGHT = 20
GAP = 8


def load_pair(app_path, figma_path):
    app = Image.open(app_path).convert("RGB")
    figma = Image.open(figma_path).convert("RGB")
    if figma.size != app.size:
        figma = figma.resize(app.size, Image.LANCZOS)
    return app, figma


def side_by_side(app, figma):
    width, height = app.size
    out = Image.new("RGB", (width * 2 + GAP, height + LABEL_HEIGHT), (255, 255, 255))
    out.paste(figma, (0, LABEL_HEIGHT))
    out.paste(app, (width + GAP, LABEL_HEIGHT))
    draw = ImageDraw.Draw(out)
    draw.text((4, 4), "Figma", fill=(0, 0, 0))
    draw.text((width + GAP + 4, 4), "App", fill=(0, 0, 0))
    return out


def overlay(app, figma):
    return Image.blend(figma, app, 0.5)


def main(argv):
    if len(argv) != 4:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    app, figma = load_pair(argv[1], argv[2])
    prefix = Path(argv[3])
    prefix.parent.mkdir(parents=True, exist_ok=True)
    side_by_side(app, figma).save(f"{prefix}-side-by-side.png")
    overlay(app, figma).save(f"{prefix}-overlay.png")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
