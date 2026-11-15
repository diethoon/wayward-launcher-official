#!/usr/bin/env python3
"""Verify image-mode button geometry in an Android uiautomator XML dump."""

import re
import sys
import xml.etree.ElementTree as ET


def by_description(nodes, fragment):
    matches = [
        node for node in nodes
        if fragment in node.attrib.get("content-desc", "")
    ]
    if not matches:
        raise AssertionError(f"UI element not found: {fragment}")
    return matches[0]


def rect(node):
    bounds = node.attrib.get("bounds", "")
    values = [int(value) for value in re.findall(r"-?\\d+", bounds)]
    if len(values) != 4:
        raise AssertionError(f"Invalid UI bounds: {bounds}")
    return values


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check-glass-ui-layout.py <uiautomator.xml>")

    root = ET.parse(sys.argv[1]).getroot()
    nodes = list(root.iter("node"))

    server = by_description(nodes, "이미지 생성 서버 실행")
    packs = by_description(nodes, "이미지팩만 사용")
    gear = by_description(nodes, "NovelAI 토큰 설정")

    sx1, sy1, sx2, sy2 = rect(server)
    px1, py1, px2, py2 = rect(packs)
    gx1, gy1, gx2, gy2 = rect(gear)

    server_height = sy2 - sy1
    packs_height = py2 - py1
    if server_height != packs_height:
        raise AssertionError(
            f"Glass button heights differ: server={server_height}px, "
            f"image-pack={packs_height}px"
        )

    if not (sx1 <= gx1 < gx2 <= sx2 and sy1 <= gy1 < gy2 <= sy2):
        raise AssertionError(
            f"NovelAI gear is outside server button: server={rect(server)}, "
            f"gear={rect(gear)}"
        )

    print(
        "Glass UI geometry verified: "
        f"both buttons={server_height}px; gear bounds={rect(gear)} "
        "inside server button."
    )


if __name__ == "__main__":
    main()
