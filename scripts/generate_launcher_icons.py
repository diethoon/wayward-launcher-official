#!/usr/bin/env python3
"""Generate density-specific launcher icons from the pinned uploaded source image."""
from __future__ import annotations

import hashlib
import io
from pathlib import Path

from PIL import Image, ImageEnhance, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "1794430759353.png"
EXPECTED_BYTES = 1_427_230
EXPECTED_GIT_BLOB_SHA1 = "2f23c94d7aea3091bb3bf394131f550422fd61d1"

# This central crop keeps the seated subject from just above the head to the knees.
# The coordinates were measured against the actual source preview at 320x320.
CROP = (0.12, 0.065, 0.88, 0.83)

LEGACY_DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
ADAPTIVE_DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def git_blob_sha1(data: bytes) -> str:
    header = f"blob {len(data)}\\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True, compress_level=9)


def main() -> None:
    if not SOURCE.is_file():
        raise FileNotFoundError(f"Launcher icon source image is missing: {SOURCE}")

    raw = SOURCE.read_bytes()
    if len(raw) != EXPECTED_BYTES:
        raise ValueError(
            f"Unexpected icon source size: {len(raw)} bytes; expected {EXPECTED_BYTES}"
        )
    actual_blob_sha1 = git_blob_sha1(raw)
    if actual_blob_sha1 != EXPECTED_GIT_BLOB_SHA1:
        raise ValueError(
            "Icon source image changed unexpectedly: "
            f"Git blob SHA-1 {actual_blob_sha1}"
        )

    source = Image.open(io.BytesIO(raw)).convert("RGB")
    width, height = source.size
    if abs(width - height) > 2:
        raise ValueError(f"Expected a square source image, received {width}x{height}")

    left, top, right, bottom = CROP
    box = (
        round(width * left),
        round(height * top),
        round(width * right),
        round(height * bottom),
    )
    cropped = source.crop(box)
    side = min(cropped.size)
    x0 = (cropped.width - side) // 2
    y0 = (cropped.height - side) // 2
    cropped = cropped.crop((x0, y0, x0 + side, y0 + side))
    cropped = ImageEnhance.Sharpness(cropped).enhance(1.04)

    res_dir = ROOT / "app" / "src" / "main" / "res"
    generated = 0

    # Legacy launcher icon bitmaps for devices below Android 8.
    for density, size in LEGACY_DENSITIES.items():
        icon = cropped.resize((size, size), Image.Resampling.LANCZOS)
        mipmap_dir = res_dir / f"mipmap-{density}"
        save_png(icon, mipmap_dir / "ic_launcher.png")
        save_png(icon, mipmap_dir / "ic_launcher_round.png")
        generated += 2

    # Adaptive icon foreground layers use the 108dp design canvas per density.
    for density, size in ADAPTIVE_DENSITIES.items():
        foreground = cropped.resize((size, size), Image.Resampling.LANCZOS)
        save_png(
            foreground,
            res_dir / f"drawable-{density}" / "ic_launcher_foreground.png",
        )
        generated += 1

    expected_outputs = [
        res_dir / f"mipmap-{density}" / filename
        for density in LEGACY_DENSITIES
        for filename in ("ic_launcher.png", "ic_launcher_round.png")
    ] + [
        res_dir / f"drawable-{density}" / "ic_launcher_foreground.png"
        for density in ADAPTIVE_DENSITIES
    ]
    missing = [str(path.relative_to(ROOT)) for path in expected_outputs if not path.is_file()]
    if missing:
        raise RuntimeError(f"Generated icon assets are missing: {missing}")

    print(
        "Launcher icon assets generated: "
        f"source={width}x{height}, crop={box}, "
        f"crop_size={cropped.size}, outputs={generated}"
    )


if __name__ == "__main__":
    main()
