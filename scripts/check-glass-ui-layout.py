#!/usr/bin/env python3
"""Verify the approved Android image-routing row geometry from a uiautomator dump."""

import re
import sys
import xml.etree.ElementTree as ET


def by_description(nodes, fragment):
    matches = [
        node for node in nodes
        if fragment in node.attrib.get("content-desc", "")
    ]
    if not matches:
        raise AssertionError(f"UI element not found by content description: {fragment}")
    return matches[0]


def rect(node):
    bounds = node.attrib.get("bounds", "")
    values = [int(value) for value in re.findall(r"-?\d+", bounds)]
    if len(values) != 4:
        raise AssertionError(f"Invalid UI bounds: {bounds}")
    return values


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check-glass-ui-layout.py <uiautomator.xml>")

    root = ET.parse(sys.argv[1]).getroot()
    nodes = list(root.iter("node"))

    generation = by_description(nodes, "이미지 생성 서버 실행")
    comfy = by_description(nodes, "ComfyUI 서버 실행")
    packs = by_description(nodes, "이미지팩만 사용")
    gear = by_description(nodes, "NovelAI 토큰 설정")

    gx1, gy1, gx2, gy2 = rect(generation)
    cx1, cy1, cx2, cy2 = rect(comfy)
    px1, py1, px2, py2 = rect(packs)
    tx1, ty1, tx2, ty2 = rect(gear)

    generation_height = gy2 - gy1
    comfy_height = cy2 - cy1
    packs_height = py2 - py1
    if generation_height != comfy_height:
        raise AssertionError(
            f"Server row heights differ: image-generation={generation_height}px, "
            f"ComfyUI={comfy_height}px"
        )
    if abs(gy1 - cy1) > 2 or abs(gy2 - cy2) > 2:
        raise AssertionError(
            f"Image-generation and ComfyUI controls are not aligned in one row: "
            f"generation={rect(generation)}, comfy={rect(comfy)}"
        )
    if not (gx2 <= cx1 or cx2 <= gx1):
        raise AssertionError(
            f"Server controls overlap instead of occupying two columns: "
            f"generation={rect(generation)}, comfy={rect(comfy)}"
        )
    if py1 < max(gy2, cy2):
        raise AssertionError(
            f"Image-pack control must be placed below the server row: "
            f"generation={rect(generation)}, comfy={rect(comfy)}, packs={rect(packs)}"
        )
    if packs_height <= 0 or px2 <= px1:
        raise AssertionError(f"Image-pack control has invalid bounds: {rect(packs)}")
    if not (gx1 <= tx1 < tx2 <= gx2 and gy1 <= ty1 < ty2 <= gy2):
        raise AssertionError(
            f"NovelAI gear is outside image-generation control: "
            f"generation={rect(generation)}, gear={rect(gear)}"
        )

    print(
        "Image routing geometry verified: "
        f"side-by-side server controls={generation_height}px; "
        f"separate image-pack control={packs_height}px; "
        f"NovelAI gear={rect(gear)} inside image-generation control."
    )


if __name__ == "__main__":
    main()
