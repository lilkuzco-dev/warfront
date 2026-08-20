#!/usr/bin/env python3
"""Render a Minecraft structure NBT to a comparison-friendly isometric PNG."""
from __future__ import annotations

import argparse
import io
import struct
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

def read_voxels(path: Path):
    bridge = Path(__file__).with_name("structure-voxels.js")
    data = subprocess.check_output(["node", str(bridge), str(path)])
    stream = io.BytesIO(data)
    if stream.read(4) != b"WFVX":
        raise ValueError("invalid voxel bridge output")
    size = struct.unpack(">III", stream.read(12))
    palette_count, block_count = struct.unpack(">II", stream.read(8))
    palette = []
    for _ in range(palette_count):
        length = struct.unpack(">H", stream.read(2))[0]
        palette.append(stream.read(length).decode("utf-8"))
    blocks = []
    for _ in range(block_count):
        blocks.append(struct.unpack(">HHHH", stream.read(8)))
    return size, palette, blocks


def base_color(name: str):
    rules = (
        ("blackstone", (39, 36, 46)),
        ("basalt", (50, 47, 57)),
        ("deepslate", (48, 51, 59)),
        ("obsidian", (32, 25, 45)),
        ("nether_brick", (48, 24, 31)),
        ("stained_glass", (74, 57, 86)),
        ("glass", (86, 102, 117)),
        ("mycelium", (78, 62, 75)),
        ("coarse_dirt", (91, 65, 47)),
        ("dirt", (84, 62, 44)),
        ("grass", (69, 83, 56)),
        ("moss", (61, 77, 52)),
        ("stone", (100, 98, 101)),
        ("andesite", (109, 106, 105)),
        ("diorite", (151, 148, 143)),
        ("granite", (119, 83, 72)),
        ("cobble", (92, 91, 92)),
        ("wood", (73, 48, 34)),
        ("plank", (82, 54, 38)),
        ("log", (75, 49, 34)),
        ("bookshelf", (111, 75, 43)),
        ("cobweb", (178, 181, 185)),
        ("lantern", (216, 143, 51)),
        ("torch", (220, 132, 45)),
        ("red_", (118, 35, 39)),
        ("purple_", (85, 48, 105)),
        ("iron", (132, 137, 143)),
        ("chain", (92, 95, 101)),
        ("bed", (91, 42, 44)),
        ("chest", (125, 80, 38)),
        ("barrel", (100, 70, 42)),
    )
    for needle, color in rules:
        if needle in name:
            return color
    return (93, 88, 88)


def shade(color, factor):
    return tuple(max(0, min(255, round(channel * factor))) for channel in color)


def render(source: Path, output: Path, title: str, width: int = 1800, height: int = 1200,
           crop=None):
    structure_size, palette, source_blocks = read_voxels(source)
    blocks = {}
    for x, y, z, state in source_blocks:
        if crop and not (crop[0] <= x <= crop[3] and crop[1] <= y <= crop[4]
                         and crop[2] <= z <= crop[5]):
            continue
        name = palette[state]
        if name.endswith("air") or name in {"minecraft:structure_void", "minecraft:jigsaw"}:
            continue
        if crop:
            x, y, z = x - crop[0], y - crop[1], z - crop[2]
        blocks[(x, y, z)] = name

    positions = set(blocks)
    surface = []
    for pos, name in blocks.items():
        x, y, z = pos
        top = (x, y + 1, z) not in positions
        left = (x - 1, y, z) not in positions
        right = (x, y, z - 1) not in positions
        if top or left or right:
            surface.append((x, y, z, name, top, left, right))
    surface.sort(key=lambda item: (item[0] + item[2] + item[1] * 0.7, item[1]))

    supersample = 2
    W, H = width * supersample, height * supersample
    image = Image.new("RGB", (W, H), (10, 12, 18))
    pixels = image.load()
    for y in range(H):
        t = y / max(1, H - 1)
        c = (round(9 + 12 * t), round(12 + 10 * t), round(20 + 11 * t))
        for x in range(W):
            pixels[x, y] = c
    draw = ImageDraw.Draw(image)

    if crop:
        size_x, size_y, size_z = (crop[3] - crop[0] + 1, crop[4] - crop[1] + 1,
                                  crop[5] - crop[2] + 1)
    else:
        size_x, size_y, size_z = structure_size
    unit_x = (width * 0.86) / max(1, size_x + size_z)
    unit_x *= supersample
    unit_y = unit_x * 0.50
    vertical = unit_x * 1.12
    center_x = W / 2
    projected_ground = (size_x + size_z) * unit_y
    base_y = H * 0.79 - projected_ground * 0.50

    # A soft footprint shadow separates the structure from the dark sky.
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    rx = (size_x + size_z) * unit_x * 0.43
    ry = (size_x + size_z) * unit_y * 0.30
    sd.ellipse((center_x - rx, base_y + projected_ground * 0.45 - ry,
                center_x + rx, base_y + projected_ground * 0.45 + ry), fill=(0, 0, 0, 115))
    shadow = shadow.filter(ImageFilter.GaussianBlur(max(3, round(12 * supersample))))
    image = Image.alpha_composite(image.convert("RGBA"), shadow).convert("RGB")
    draw = ImageDraw.Draw(image)

    def project(x, y, z):
        return center_x + (x - z) * unit_x, base_y + (x + z) * unit_y - y * vertical

    for x, y, z, name, show_top, show_left, show_right in surface:
        sx, sy = project(x, y, z)
        color = base_color(name)
        top_poly = [(sx, sy - vertical), (sx + unit_x, sy - vertical + unit_y),
                    (sx, sy - vertical + 2 * unit_y), (sx - unit_x, sy - vertical + unit_y)]
        left_poly = [(sx - unit_x, sy - vertical + unit_y), (sx, sy - vertical + 2 * unit_y),
                     (sx, sy + 2 * unit_y), (sx - unit_x, sy + unit_y)]
        right_poly = [(sx + unit_x, sy - vertical + unit_y), (sx, sy - vertical + 2 * unit_y),
                      (sx, sy + 2 * unit_y), (sx + unit_x, sy + unit_y)]
        if show_left:
            draw.polygon(left_poly, fill=shade(color, 0.62))
        if show_right:
            draw.polygon(right_poly, fill=shade(color, 0.78))
        if show_top:
            draw.polygon(top_poly, fill=shade(color, 1.08))

    # Lightweight screenshot caption; labels stay outside the build silhouette.
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf",
                                  24 * supersample)
    except OSError:
        font = ImageFont.load_default()
    caption_y = H - 90 * supersample
    draw.rounded_rectangle((60 * supersample, caption_y - 28 * supersample,
                            (60 + max(330, len(title) * 18)) * supersample, caption_y + 30 * supersample),
                           radius=14 * supersample, fill=(5, 7, 11))
    draw.text((82 * supersample, caption_y - 13 * supersample), title,
              fill=(225, 228, 235), font=font)
    image = ImageEnhance.Contrast(image).enhance(1.06)
    image.resize((width, height), Image.Resampling.LANCZOS).save(output)
    print(f"wrote {output} ({len(blocks)} blocks, {len(surface)} visible surface blocks)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--title", default="Dracula Castle")
    parser.add_argument("--width", type=int, default=1800)
    parser.add_argument("--height", type=int, default=1200)
    parser.add_argument("--crop", help="inclusive x1,y1,z1,x2,y2,z2 structure crop")
    options = parser.parse_args()
    crop = tuple(map(int, options.crop.split(","))) if options.crop else None
    if crop and len(crop) != 6:
        parser.error("--crop needs six comma-separated integers")
    render(options.source, options.output, options.title, options.width, options.height, crop)


if __name__ == "__main__":
    main()
