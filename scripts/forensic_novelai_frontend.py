#!/usr/bin/env python3
"""Forensically scan text members in the pinned mods ZIP for ImageGen request/poll paths.

Reads each candidate member sequentially in 4096-byte chunks, records per-chunk SHA-256,
and overlaps the prior 1024 bytes so matches crossing chunk boundaries are retained.
Only short contexts around route/poll references are emitted; no complete source file is dumped.
"""
import hashlib
import json
import os
import sys
import zipfile

CHUNK_SIZE = 4096
OVERLAP = 1024
TEXT_SUFFIXES = {".js", ".mjs", ".cjs", ".ts", ".tsx", ".html", ".htm", ".json", ".css"}
PATTERNS = [
    ("generate_route", b"/api/image/generate"),
    ("status_route", b"/api/image/status"),
    ("prompt_id", b"promptid"),
    ("set_interval", b"setinterval"),
    ("poll_word", b"poll"),
]


def scan_member(archive, info):
    digest = hashlib.sha256()
    chunk_hashes = []
    hits = []
    seen = {name: set() for name, _ in PATTERNS}
    tail = b""
    offset = 0
    line_count = 0
    with archive.open(info, "r") as stream:
        while True:
            chunk = stream.read(CHUNK_SIZE)
            if not chunk:
                break
            digest.update(chunk)
            chunk_hashes.append({
                "offset": offset,
                "length": len(chunk),
                "sha256": hashlib.sha256(chunk).hexdigest(),
            })
            scan = tail + chunk
            base_offset = offset - len(tail)
            lowered = scan.lower()
            for name, pattern in PATTERNS:
                cursor = 0
                while True:
                    index = lowered.find(pattern, cursor)
                    if index < 0:
                        break
                    absolute = base_offset + index
                    if absolute not in seen[name] and (name in ("generate_route", "status_route") or len(seen[name]) < 12):
                        seen[name].add(absolute)
                        before_lines = line_count - tail.count(b"\n")
                        line_number = before_lines + scan[:index].count(b"\n") + 1
                        left = max(0, index - 280)
                        right = min(len(scan), index + len(pattern) + 280)
                        context = scan[left:right].decode("utf-8", "replace").replace("\x00", "")
                        hits.append({
                            "kind": name,
                            "offset": absolute,
                            "line": line_number,
                            "context": context[:700],
                        })
                    cursor = index + max(1, len(pattern))
            line_count += chunk.count(b"\n")
            tail = scan[-OVERLAP:]
            offset += len(chunk)

    route_hits = [hit for hit in hits if hit["kind"] in ("generate_route", "status_route")]
    if not route_hits:
        return None
    lower_path = info.filename.lower()
    backend_path = ("/backend/" in lower_path or "/wayward-imagegen/" in lower_path
                    or "/src/routes/" in lower_path or "/src/comfy/" in lower_path)
    return {
        "path": info.filename,
        "size_bytes": info.file_size,
        "sha256": digest.hexdigest(),
        "chunk_size": CHUNK_SIZE,
        "chunk_count": len(chunk_hashes),
        "chunk_hashes": chunk_hashes,
        "is_backend_source": backend_path,
        "matches": sorted(hits, key=lambda item: item["offset"]),
    }


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: forensic_novelai_frontend.py mods.zip report.json")
    archive_path, report_path = sys.argv[1], sys.argv[2]
    if not os.path.isfile(archive_path):
        raise SystemExit("archive not found: " + archive_path)

    results = []
    scanned = 0
    with zipfile.ZipFile(archive_path, "r") as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            suffix = os.path.splitext(info.filename)[1].lower()
            if suffix not in TEXT_SUFFIXES:
                continue
            scanned += 1
            result = scan_member(archive, info)
            if result is not None:
                results.append(result)

    frontend = [item for item in results if not item["is_backend_source"]]
    report = {
        "archive_name": os.path.basename(archive_path),
        "archive_size_bytes": os.path.getsize(archive_path),
        "text_members_scanned": scanned,
        "route_reference_files": results,
        "frontend_route_files": [item["path"] for item in frontend],
        "backend_route_files": [item["path"] for item in results if item["is_backend_source"]],
        "frontend_generate_route_found": any(
            hit["kind"] == "generate_route"
            for item in frontend for hit in item["matches"]
        ),
        "frontend_status_route_found": any(
            hit["kind"] == "status_route"
            for item in frontend for hit in item["matches"]
        ),
    }
    with open(report_path, "w", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
    print(json.dumps({
        "text_members_scanned": scanned,
        "route_reference_files": len(results),
        "frontend_route_files": report["frontend_route_files"],
        "frontend_generate_route_found": report["frontend_generate_route_found"],
        "frontend_status_route_found": report["frontend_status_route_found"],
        "report": report_path,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
