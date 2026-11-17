#!/usr/bin/env python3
"""Android 15 launcher smoke test with the native image-pack folder browser."""

from __future__ import annotations

import base64
import pathlib
import json
import re
import shutil
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib
import zipfile

PACKAGE = "com.wayward.launcher.official.debug"
ACTIVITY = f"{PACKAGE}/com.wayward.launcher.MainActivity"
PICKER_TEST_ACTIVITY = f"{PACKAGE}/com.wayward.launcher.ImagePackPickerAcceptanceActivity"
WORKSPACE = pathlib.Path.cwd()
DEBUG_APK = WORKSPACE / "app/build/outputs/apk/debug/app-debug.apk"
ARTIFACTS = WORKSPACE / "artifacts"
DEVICE_XML = "/sdcard/wayward-launcher-home.xml"
LOCAL_XML = pathlib.Path("/tmp/wayward-launcher-home.xml")
LOCAL_PID = pathlib.Path("/tmp/wayward-launcher-app-pid.txt")
LOCAL_CRASH = pathlib.Path("/tmp/wayward-launcher-crash.log")
FIXTURE_FOLDER = "/sdcard/Download"
GAME_FOLDER = "/sdcard/Download/WaywardGameTest"
GAME_FOLDER_TWO = "/sdcard/Download/WaywardGameTestTwo"


def run(command: list[str], *, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        command,
        check=check,
        text=capture,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def adb(*args: str, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    return run(["adb", *args], check=check, capture=capture)


def parse_wayward_recent_task_records(dumpsys_output: str) -> list[tuple[int, str]]:
    """Read only canonical Recent tasks entries, not the repeated visible-task summary."""
    section = re.search(
        r"(?ms)^\s*Recent tasks:\s*(.*?)(?=^\s*Visible recent tasks\b|\Z)",
        dumpsys_output,
    )
    if section is None:
        return []

    records: list[tuple[int, str]] = []
    entries = re.finditer(
        r"(?ms)^\s*\*\s*Recent #\d+:\s*Task\{([^}]*)\}(.*?)"
        r"(?=^\s*\*\s*Recent #\d+:|\Z)",
        section.group(1),
    )
    for entry in entries:
        task_id = re.search(r"(?:^|\s)#(\d+)(?:\s|$)", entry.group(1))
        component = re.search(r"^\s*mActivityComponent=([^\s]+)", entry.group(2), re.MULTILINE)
        if component is None:
            component = re.search(r"^\s*intent=.*?\bcmp=([^\s}]+)", entry.group(2), re.MULTILINE)
        if task_id is None or component is None:
            continue
        name = component.group(1)
        if name.split("/", 1)[0] == PACKAGE:
            records.append((int(task_id.group(1)), name))
    return records


def ensure_previous_diagnostic_journals() -> set[str]:
    """Ensure both rotated journals exist so the manual ZIP path verifies their inclusion."""
    seeded: set[str] = set()
    # run-as starts the shell with an implementation-dependent working directory;
    # write to the exact getFilesDir() path used by ImagePackDiagnostics.
    diagnostics_dir = "/data/user/0/" + PACKAGE + "/files/diagnostics"
    fixtures = {
        "launcher.previous.ndjson": {
            "id": "ci-previous-launcher",
            "timestamp": 1,
            "level": "INFO",
            "component": "ci",
            "event": "previous_launcher_journal_fixture",
        },
        "imagepack.previous.ndjson": {
            "timestamp": 1,
            "sessionId": "ci-previous",
            "requestId": "ci-previous",
            "stage": "previous_imagepack_journal_fixture",
            "status": "info",
            "sdk": 35,
            "details": {},
        },
    }
    for name, event in fixtures.items():
        current = adb("shell", "run-as", PACKAGE, "cat", "files/diagnostics/" + name,
                      check=False, capture=True)
        if current.returncode == 0 and (current.stdout or "").strip():
            continue
        encoded = base64.b64encode(
            (json.dumps(event, separators=(",", ":")) + "\n").encode("utf-8")
        ).decode("ascii")
        adb(
            "shell", "run-as", PACKAGE, "sh", "-c",
            "mkdir -p " + diagnostics_dir + " && echo " + encoded
            + " | base64 -d > " + diagnostics_dir + "/" + name,
        )
        seeded.add(name)
    return seeded


def make_png() -> bytes:
    def chunk(name: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data) & 0xFFFFFFFF)

    raw = b"\x00\xff\x20\x40\xff"
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def dump_ui(local_path: pathlib.Path, device_path: str) -> str:
    last_error = "uiautomator did not produce output"
    for attempt in range(2):
        # Never accept a stale hierarchy from a previous screen.
        adb("shell", "rm", "-f", device_path, check=False, capture=True)
        result = adb("shell", "uiautomator", "dump", "--compressed", device_path,
                     check=False, capture=True)
        pull = adb("pull", device_path, str(local_path), check=False, capture=True)
        if pull.returncode == 0 and local_path.is_file():
            payload = local_path.read_text(encoding="utf-8", errors="replace")
            try:
                root = ET.fromstring(payload)
                if root.tag == "hierarchy" and next(root.iter("node"), None) is not None:
                    if result.returncode != 0:
                        print(
                            "WARN: uiautomator exited " + str(result.returncode)
                            + " after writing a valid fresh hierarchy; using the verified XML."
                        )
                    return payload
            except ET.ParseError:
                pass
        last_error = (
            "uiautomator exit=" + str(result.returncode)
            + " pull exit=" + str(pull.returncode)
            + " output=" + (result.stderr or result.stdout or "none")[-800:]
            + " pullOutput=" + (pull.stderr or pull.stdout or "none")[-400:]
        )
        if attempt == 0:
            time.sleep(1)
    raise RuntimeError("Android UI hierarchy dump failed after retry: " + last_error)


def node_bounds(xml: str, labels: tuple[str, ...], *, require_clickable: bool = True,
                require_enabled: bool = False) -> tuple[int, int, int, int] | None:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        text = node.attrib.get("text", "")
        description = node.attrib.get("content-desc", "")
        clickable = node.attrib.get("clickable", "false") == "true"
        resource_id = node.attrib.get("resource-id", "")
        if require_clickable and not clickable:
            continue
        if require_enabled and node.attrib.get("enabled", "true") != "true":
            continue
        matches_label = any(label.casefold() in (text + " " + description).casefold() for label in labels)
        is_confirmation_button = resource_id == "android:id/button1" and any(
            marker.casefold() in (text + " " + description).casefold()
            for marker in ("allow", "허용")
        )
        if matches_label or is_confirmation_button:
            match = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
            if match:
                return tuple(int(value) for value in match.groups())
    return None


def tap_bounds(bounds: tuple[int, int, int, int]) -> None:
    left, top, right, bottom = bounds
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def node_bounds_all_exact(xml: str, labels: tuple[str, ...]) -> list[tuple[int, int, int, int]]:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    wanted = {label.casefold() for label in labels}
    found = []
    for node in root.iter("node"):
        if node.attrib.get("clickable", "false") != "true" or node.attrib.get("enabled", "true") != "true":
            continue
        text = node.attrib.get("text", "").strip().casefold()
        description = node.attrib.get("content-desc", "").strip().casefold()
        if text not in wanted and description not in wanted:
            continue
        match = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if match:
            found.append(tuple(int(value) for value in match.groups()))
    return found


def node_bounds_exact(xml: str, labels: tuple[str, ...]) -> tuple[int, int, int, int] | None:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    wanted = {label.casefold() for label in labels}
    for node in root.iter("node"):
        if node.attrib.get("clickable", "false") != "true" or node.attrib.get("enabled", "true") != "true":
            continue
        text = node.attrib.get("text", "").strip().casefold()
        description = node.attrib.get("content-desc", "").strip().casefold()
        if text not in wanted and description not in wanted:
            continue
        match = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if match:
            return tuple(int(value) for value in match.groups())
    return None


def screenshot(path: pathlib.Path) -> None:
    with path.open("wb") as output:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], check=True, stdout=output)


def wait_for_log(marker: str, *, timeout_seconds: int = 45) -> str:
    latest = ""
    for _ in range(timeout_seconds):
        result = adb("logcat", "-d", "-s", "WaywardImagePackTest:V",
                     check=False, capture=True)
        latest = result.stdout or ""
        if "FAIL " in latest:
            raise RuntimeError("Image-pack native folder acceptance failed: " + latest[-4000:])
        if marker in latest:
            return latest
        time.sleep(1)
    full_log = adb("logcat", "-d", "-v", "time", check=False, capture=True).stdout or ""
    (ARTIFACTS / "imagepack-acceptance-logcat.txt").write_text(full_log[-120000:], encoding="utf-8")
    raise RuntimeError("Timed out waiting for " + marker + ". Last acceptance log: " + latest[-4000:])


def wait_for_ui_contains(local_path: pathlib.Path, device_path: str, marker: str,
                         *, timeout_seconds: int = 30) -> str:
    latest = ""
    deadline = time.monotonic() + timeout_seconds
    while True:
        latest = dump_ui(local_path, device_path)
        if marker in latest:
            return latest
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(2, remaining))
    raise RuntimeError("Timed out waiting for UI marker " + marker + ": " + latest[-2500:])


def count_main_activity_instances() -> tuple[int, set[int], str]:
    state = adb("shell", "dumpsys", "activity", "activities", check=False, capture=True).stdout or ""
    records = set()
    task_ids = set()
    for match in re.finditer(r"ActivityRecord\{([^}]*)\}", state):
        body = match.group(1)
        if PACKAGE not in body or not any(
                component in body for component in ("MainActivity", "GameSessionActivity")
        ):
            continue
        fields = body.split()
        if fields:
            records.add(fields[0])
        task = re.search(r"\bt(\d+)\b", body)
        if task:
            task_ids.add(int(task.group(1)))
    return len(records), task_ids, state


def read_launcher_events() -> list[dict]:
    events = []
    for journal in ("launcher.previous.ndjson", "launcher.ndjson"):
        result = adb("shell", "run-as", PACKAGE, "cat", "files/diagnostics/" + journal,
                     check=False, capture=True)
        if result.returncode != 0:
            continue
        for line in (result.stdout or "").splitlines():
            try:
                item = json.loads(line)
                details = item.get("details", "")
                if isinstance(details, str):
                    try:
                        item["parsedDetails"] = json.loads(details) if details else {}
                    except json.JSONDecodeError:
                        item["parsedDetails"] = {}
                else:
                    item["parsedDetails"] = details if isinstance(details, dict) else {}
                events.append(item)
            except json.JSONDecodeError:
                continue
    return events


def session_events(events: list[dict], event_name: str, session_id: str) -> list[dict]:
    return [
        event for event in events
        if event.get("event") == event_name
        and event.get("parsedDetails", {}).get("sessionId") == session_id
    ]


def capture_game_route_diagnostics(label: str, local_path: pathlib.Path, device_path: str) -> dict:
    safe_label = re.sub(r"[^A-Za-z0-9_-]", "_", label)
    ui = dump_ui(local_path, device_path)
    (ARTIFACTS / f"android15-game-route-{safe_label}-ui.xml").write_text(ui, encoding="utf-8")
    screenshot(ARTIFACTS / f"android15-game-route-{safe_label}.png")
    activity = adb("shell", "dumpsys", "activity", "activities", check=False, capture=True)
    recents = adb("shell", "dumpsys", "activity", "recents", check=False, capture=True)
    (ARTIFACTS / f"android15-game-route-{safe_label}-activities.txt").write_text(
        activity.stdout or activity.stderr or "", encoding="utf-8"
    )
    (ARTIFACTS / f"android15-game-route-{safe_label}-recents.txt").write_text(
        recents.stdout or recents.stderr or "", encoding="utf-8"
    )
    launcher = adb("shell", "run-as", PACKAGE, "cat", "files/diagnostics/launcher.ndjson",
                   check=False, capture=True)
    (ARTIFACTS / f"android15-game-route-{safe_label}-launcher.ndjson").write_text(
        launcher.stdout or launcher.stderr or "", encoding="utf-8"
    )
    imagepack = adb("shell", "run-as", PACKAGE, "cat", "files/diagnostics/imagepack.ndjson",
                    check=False, capture=True)
    (ARTIFACTS / f"android15-game-route-{safe_label}-imagepack.ndjson").write_text(
        imagepack.stdout or imagepack.stderr or "", encoding="utf-8"
    )
    logcat = adb("logcat", "-d", "-v", "time", check=False, capture=True)
    relevant = [
        line for line in (logcat.stdout or "").splitlines()
        if any(token in line for token in (
            "Wayward", "MainActivity", "AndroidRuntime", "chromium", "WebView",
            "ActivityTaskManager", "ActivityManager"
        )) and any(token in line.lower() for token in (
            "error", "crash", "destroy", "new_intent", "resume", "pause", "fail",
            "launch", "webview", "activity"
        ))
    ]
    (ARTIFACTS / f"android15-game-route-{safe_label}-logcat.txt").write_text(
        "\\n".join(relevant[-2000:]), encoding="utf-8"
    )
    events = read_launcher_events()
    wanted = {
        "activity_create", "activity_new_intent", "activity_resume", "activity_pause",
        "activity_destroy", "game_launch_requested", "game_launch_reordered_existing_activity",
        "game_launch_new_activity", "game_webview_created", "game_webview_destroyed",
        "game_page_started", "game_page_finished", "game_page_error",
        "game_session_brought_to_front", "game_session_visible", "game_session_destroyed"
    }
    summary = []
    for event in events:
        if event.get("event") not in wanted:
            continue
        details = event.get("parsedDetails", {})
        summary.append({
            "event": event.get("event"),
            "level": event.get("level"),
            "timestamp": event.get("timestamp"),
            "activityInstanceId": details.get("activityInstanceId"),
            "taskId": details.get("taskId"),
            "sessionId": details.get("sessionId"),
            "launchRequestId": details.get("launchRequestId"),
            "webViewInstanceId": details.get("webViewInstanceId"),
            "webViewVisible": details.get("webViewVisible"),
            "homeVisible": details.get("homeVisible"),
            "gamePageLoaded": details.get("gamePageLoaded"),
            "reason": details.get("reason")
        })
    (ARTIFACTS / f"android15-game-route-{safe_label}-events.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return {
        "uiBytes": len(ui),
        "launcherLogAvailable": launcher.returncode == 0,
        "activityDumpBytes": len(activity.stdout or ""),
        "events": summary[-40:]
    }


def choose_download_as_game_folder(local_path: pathlib.Path, device_path: str,
                                   folder_name: str = "WaywardGameTest") -> str:
    """Drive the real MainActivity game-folder SAF picker and select a child of Download."""
    home_xml = dump_ui(local_path, device_path)
    add_button = node_bounds(home_xml, ("현재 기기 폴더", "Add device folder"))
    if add_button is None:
        raise RuntimeError("MainActivity did not expose its device-folder registration button.")
    tap_bounds(add_button)

    download_selected = False
    game_folder_opened = False
    latest = ""
    last_saved_state = None
    picker_seen = False
    home_streak = 0
    safe_folder_name = re.sub(r"[^A-Za-z0-9_-]", "_", folder_name)
    for iteration in range(50):
        latest = dump_ui(local_path, device_path)
        if latest != last_saved_state:
            state_path = ARTIFACTS / f"android15-folder-picker-{safe_folder_name}-{iteration:02d}.xml"
            state_path.write_text(latest, encoding="utf-8")
            last_saved_state = latest

        is_launcher_home = "내 게임" in latest or "My games" in latest
        if not is_launcher_home:
            picker_seen = True
            home_streak = 0
        elif not picker_seen:
            # startActivityForResult is asynchronous: the first hierarchy dump
            # can still be MainActivity home even though the click was accepted.
            home_streak += 1
            if home_streak >= 8:
                events = read_launcher_events()
                launcher_log = adb(
                    "shell", "run-as", PACKAGE, "cat", "files/diagnostics/launcher.ndjson",
                    check=False, capture=True
                )
                (ARTIFACTS / f"android15-folder-picker-{safe_folder_name}-launcher.ndjson").write_text(
                    launcher_log.stdout or launcher_log.stderr or "", encoding="utf-8"
                )
                activity_dump = adb(
                    "shell", "dumpsys", "activity", "activities", check=False, capture=True
                )
                (ARTIFACTS / f"android15-folder-picker-{safe_folder_name}-activities.txt").write_text(
                    activity_dump.stdout or activity_dump.stderr or "", encoding="utf-8"
                )
                opened = [
                    event.get("event") for event in events
                    if event.get("event") in ("folder_picker_opened", "folder_picker_cancelled",
                                              "folder_permission_failed", "game_folder_registered")
                ]
                raise RuntimeError(
                    "DocumentsUI did not appear after tapping the game-folder button for "
                    + folder_name + "; observed launcher events=" + repr(opened)
                )
        elif is_launcher_home and folder_name not in latest:
            events = read_launcher_events()
            launcher_log = adb(
                "shell", "run-as", PACKAGE, "cat", "files/diagnostics/launcher.ndjson",
                check=False, capture=True
            )
            (ARTIFACTS / f"android15-folder-picker-{safe_folder_name}-launcher.ndjson").write_text(
                launcher_log.stdout or launcher_log.stderr or "", encoding="utf-8"
            )
            activity_dump = adb(
                "shell", "dumpsys", "activity", "activities", check=False, capture=True
            )
            (ARTIFACTS / f"android15-folder-picker-{safe_folder_name}-activities.txt").write_text(
                activity_dump.stdout or activity_dump.stderr or "", encoding="utf-8"
            )
            registration_events = [
                event.get("event") for event in events
                if event.get("event") in ("folder_picker_opened", "folder_picker_cancelled",
                                          "folder_permission_persisted", "folder_permission_failed",
                                          "game_folder_registered")
            ]
            raise RuntimeError(
                "The folder picker returned to launcher without registering "
                + folder_name + "; latest registration events=" + repr(registration_events)
            )

        if download_selected and not game_folder_opened:
            child_folder = node_bounds(latest, (folder_name,), require_clickable=False)
            if child_folder is not None:
                tap_bounds(child_folder)
                game_folder_opened = True
                time.sleep(2)
                continue

        if game_folder_opened:
            use_folder = node_bounds(
                latest, ("이 폴더 사용", "Use this folder", "Use folder"), require_enabled=True
            )
            if use_folder is not None:
                tap_bounds(use_folder)
                break

        download_row = node_bounds(latest, ("Downloads", "Download"), require_clickable=False)
        if download_row is not None and not download_selected:
            tap_bounds(download_row)
            download_selected = True
            time.sleep(2)
            continue

        roots_button = node_bounds(
            latest, ("Show roots", "Show navigation drawer", "Browse")
        )
        if roots_button is not None and not download_selected:
            tap_bounds(roots_button)
            time.sleep(1)
            continue

        time.sleep(1)
    else:
        (ARTIFACTS / "android15-game-tree-picker.xml").write_text(latest, encoding="utf-8")
        raise RuntimeError("DocumentsUI did not allow the real launcher to select Download/" + folder_name + ".")

    # Android may ask for a second explicit grant after the tree button is pressed.
    time.sleep(1)
    latest = dump_ui(local_path, device_path)
    (ARTIFACTS / "android15-game-tree-picker-after-use.xml").write_text(latest, encoding="utf-8")
    screenshot(ARTIFACTS / "android15-game-tree-picker-after-use.png")
    for _ in range(25):
        latest = dump_ui(local_path, device_path)
        if "내 게임" in latest or "My games" in latest:
            (ARTIFACTS / "android15-game-tree-picker-returned.xml").write_text(latest, encoding="utf-8")
            if folder_name not in latest:
                raise RuntimeError(
                    "The SAF picker returned to MainActivity without showing registered profile "
                    + folder_name + ". " + latest[-2500:]
                )
            return latest

        # Match a positive button exactly; substring matching "Allow" could hit "Don't allow".
        allow_button = node_bounds_exact(latest, ("허용", "Allow", "Allow access"))
        if allow_button is not None:
            tap_bounds(allow_button)
            time.sleep(1)
            continue

        use_button = node_bounds(latest, ("이 폴더 사용", "Use this folder", "Use folder"))
        if use_button is not None:
            tap_bounds(use_button)
            time.sleep(1)
            continue
        time.sleep(1)

    (ARTIFACTS / "android15-game-tree-picker.xml").write_text(latest, encoding="utf-8")
    raise RuntimeError("The launcher did not return to its home screen after the SAF grant; last DocumentsUI state: "
                       + latest[-4500:])
    raise RuntimeError("The launcher did not return to its home screen after the SAF grant; last DocumentsUI state: "
                       + latest[-4500:])


def open_download_directory_in_documents_ui(local_path: pathlib.Path, device_path: str,
                                                *, timeout_seconds: int = 45) -> str:
    latest = ""
    download_selected = False
    for _ in range(timeout_seconds):
        latest = dump_ui(local_path, device_path)
        if download_selected:
            return latest
        download_row = node_bounds(latest, ("Downloads", "Download"))
        if download_row is None:
            download_row = node_bounds(latest, ("Downloads", "Download"), require_clickable=False)
        if download_row is not None:
            tap_bounds(download_row)
            download_selected = True
            time.sleep(2)
            continue
        roots_button = node_bounds(latest, ("Show roots", "Show navigation drawer", "Browse"))
        if roots_button is not None:
            tap_bounds(roots_button)
            time.sleep(1)
            continue
        time.sleep(1)
    (ARTIFACTS / "android15-documentsui-download-navigation.xml").write_text(
        latest, encoding="utf-8"
    )
    raise RuntimeError("DocumentsUI did not expose a Downloads/Download location.")


def pick_single_document(local_path: pathlib.Path, device_path: str, filename: str) -> None:
    open_download_directory_in_documents_ui(local_path, device_path)
    latest = ""
    item = None
    for _ in range(12):
        latest = dump_ui(local_path, device_path)
        item = node_bounds(latest, (filename,))
        if item is None:
            item = node_bounds(latest, (filename,), require_clickable=False)
        if item is not None:
            tap_bounds(item)
            return
        # Downloads can contain many entries; scroll the SAF list rather than
        # treating a filename below the first viewport as a missing file.
        adb("shell", "input", "swipe", "540", "1850", "540", "650", "350")
        time.sleep(1)
    (ARTIFACTS / "android15-single-file-picker-missing.xml").write_text(latest, encoding="utf-8")
    raise RuntimeError("The file picker did not expose " + filename + " after scrolling Downloads.");


def pick_multiple_documents(
    local_path: pathlib.Path, device_path: str, filenames: tuple[str, str], artifact_name: str
) -> None:
    latest = open_download_directory_in_documents_ui(local_path, device_path)
    first = node_bounds(latest, (filenames[0],), require_clickable=False)
    if first is None:
        raise RuntimeError("The file picker did not list " + filenames[0])
    left, top, right, bottom = first
    x, y = str((left + right) // 2), str((top + bottom) // 2)
    adb("shell", "input", "swipe", x, y, x, y, "1000")
    time.sleep(1)

    latest = dump_ui(local_path, device_path)
    second = node_bounds(latest, (filenames[1],), require_clickable=False)
    if second is None:
        (ARTIFACTS / artifact_name).write_text(latest, encoding="utf-8")
        raise RuntimeError("The file picker did not list " + filenames[1] + " in multi-select mode.")
    tap_bounds(second)
    time.sleep(1)

    for _ in range(15):
        latest = dump_ui(local_path, device_path)
        action = node_bounds(latest, ("Open", "열기", "Select", "선택", "Done", "완료"))
        if action is not None:
            tap_bounds(action)
            return
        time.sleep(1)
    (ARTIFACTS / artifact_name).write_text(latest, encoding="utf-8")
    raise RuntimeError("The file picker did not expose its multi-select confirmation action.")


def pick_multiple_zip_documents(local_path: pathlib.Path, device_path: str) -> None:
    pick_multiple_documents(
        local_path, device_path, ("test-one.zip", "test-two.zip"), "android15-zip-multiselect.xml"
    )


def wait_for_cloudflare_diagnostic_upload(timeout_seconds: int = 75) -> dict:
    deadline = time.monotonic() + timeout_seconds
    last_failure = None
    while time.monotonic() < deadline:
        for journal in ("imagepack.previous.ndjson", "imagepack.ndjson"):
            result = adb("shell", "run-as", PACKAGE, "cat", "files/diagnostics/" + journal,
                         check=False, capture=True)
            if result.returncode != 0:
                continue
            for line in (result.stdout or "").splitlines():
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                stage = event.get("stage")
                details = event.get("details", {})
                if isinstance(details, str):
                    try:
                        details = json.loads(details) if details else {}
                    except json.JSONDecodeError:
                        details = {}
                if stage == "diagnostics_cloud_upload_succeeded":
                    return details if isinstance(details, dict) else {}
                if stage == "diagnostics_cloud_upload_failed":
                    result_code = details.get("result", "unknown") if isinstance(details, dict) else "unknown"
                    last_failure = str(result_code)[:120]
        if last_failure:
            raise RuntimeError("Cloudflare diagnostics ZIP upload failed: " + last_failure)
        time.sleep(2)
    raise RuntimeError("Timed out waiting for Cloudflare diagnostics ZIP upload success event.")


def run_main_activity_game_session_acceptance() -> None:
    """Exercise the real game WebView, folder bridge, back stack, and Recents."""
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture_dir = ARTIFACTS / "main-activity-game-fixture"
    fixture_dir.mkdir(parents=True, exist_ok=True)
    game_page = """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body>
<h1>WAYWARD MAIN ACTIVITY GAME SESSION</h1>
<button id="interact">Interact with game</button>
<div id="interaction-result">WAITING_FOR_GAME_INTERACTION</div>
<button id="return-home">Return to launcher</button>
<button id="open-second">Open second internal page</button>
<button id="pick">Open image-pack folder</button>
<iframe id="picker-frame" src="picker-frame.html" title="Image-pack folder picker frame"
        style="width:1px;height:1px;border:0"></iframe>
<button id="single-button">Pick multiple pack.json files</button>
<input id="single-input" type="file" accept=".json,application/json" multiple hidden>
<button id="zip-button">Pick multiple ZIP files</button>
<input id="zip-input" type="file" accept=".zip,application/zip" multiple hidden>

<div id="result">READY</div><div id="file-result">FILE_READY</div>
<script>
let interactionCount = 0;
document.getElementById('interact').addEventListener('click', () => {
  interactionCount += 1;
  document.getElementById('interaction-result').textContent =
    'GAME_INTERACTION_OK count=' + interactionCount;
});
document.getElementById('return-home').addEventListener('click', () => {
  if (window.WMLHost && typeof window.WMLHost.menu === 'function') window.WMLHost.menu();
});
document.getElementById('open-second').addEventListener('click', () => {
  window.location.assign('session-second.html');
});
document.getElementById('pick').addEventListener('click', async () => {
  try {
    const frame = document.getElementById('picker-frame');
    if (!frame || !frame.contentWindow ||
        typeof frame.contentWindow.pickImagePackFolder !== 'function') {
      throw new Error('The same-origin picker iframe did not load');
    }
    await frame.contentWindow.pickImagePackFolder();
  } catch (error) {
    document.getElementById('result').textContent =
      'FAIL_GAME_SESSION_DIRECTORY_HANDLE ' + String(error && error.name) +
      ': ' + String(error && error.message);
  }
});
document.getElementById('single-button').addEventListener('click', () =>
  document.getElementById('single-input').click());
document.getElementById('single-input').addEventListener('change', event => {
  const names = Array.from(event.target.files || []).map(file => file.name);
  document.getElementById('file-result').textContent =
    names.includes('pack-one.json') && names.includes('pack-two.json')
      ? 'PASS_PACK_JSON_MULTI_FILES names=' + names.join(',')
      : 'FAIL_PACK_JSON_MULTI_FILES names=' + names.join(',');
});
document.getElementById('zip-button').addEventListener('click', () =>
  document.getElementById('zip-input').click());
document.getElementById('zip-input').addEventListener('change', event => {
  const files = Array.from(event.target.files || []).map(file => file.name);
  document.getElementById('file-result').textContent =
    files.length >= 2 && files.includes('test-one.zip') && files.includes('test-two.zip')
      ? 'PASS_ZIP_MULTI_FILES count=' + files.length + ' names=' + files.join(',')
      : 'FAIL_ZIP_MULTI_FILES count=' + files.length + ' names=' + files.join(',');
});
</script>
</body></html>"""
    second_page = """<!doctype html><html><body><h1>SECOND_SESSION_PAGE</h1>
<p>Back must return to the first game page, not the launcher.</p></body></html>"""
    picker_page = """<!doctype html><html><body><script>
window.pickImagePackFolder = async function() {
  const report = parent.document.getElementById('result');
  try {
    const directory = await window.showDirectoryPicker({mode: 'read'});
    if (!directory || directory.kind !== 'directory' ||
        typeof directory.getFileHandle !== 'function' ||
        typeof directory.entries !== 'function') {
      throw new Error('The folder API did not return a directory handle');
    }
    const names = [];
    for await (const [name, handle] of directory.entries()) names.push(name);
    if (!names.includes('pixel.png')) throw new Error('pixel.png missing from directory handle listing');
    const handle = await directory.getFileHandle('pixel.png');
    const file = await handle.getFile();
    const bytes = new Uint8Array(await file.arrayBuffer());
    const signature = Array.from(bytes.slice(0, 8)).join(',');
    if (signature !== '137,80,78,71,13,10,26,10') throw new Error('PNG read failed');
    report.textContent = 'PASS_GAME_SESSION_DIRECTORY_HANDLE listed=' +
      names.length + ' bytes=' + bytes.length + ' frame=iframe';
  } catch (error) {
    report.textContent = 'FAIL_GAME_SESSION_DIRECTORY_HANDLE ' +
      String(error && error.name) + ': ' + String(error && error.message);
    throw error;
  }
};
</script></body></html>"""
    (fixture_dir / "Wayward_WML_ko.html").write_text(game_page, encoding="utf-8")
    second_game_page = game_page.replace(
        "WAYWARD MAIN ACTIVITY GAME SESSION", "WAYWARD SECOND GAME SESSION"
    )
    (fixture_dir / "Wayward_WML_ko-second.html").write_text(second_game_page, encoding="utf-8")
    (fixture_dir / "session-second.html").write_text(second_page, encoding="utf-8")
    (fixture_dir / "picker-frame.html").write_text(picker_page, encoding="utf-8")
    for archive_name in ("test-one.zip", "test-two.zip"):
        with zipfile.ZipFile(fixture_dir / archive_name, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("fixture.txt", archive_name)
    pixel_fixture = fixture_dir / "pixel.png"
    pixel_fixture.write_bytes(make_png())
    for index, filename in enumerate(("pack-one.json", "pack-two.json"), start=1):
        pack_fixture = fixture_dir / filename
        pack_fixture.write_text(
            '{"packFormat":1,"id":"wayward-android-test-' + str(index) +
            '","folder":"wayward-android-test-' + str(index) + '","entries":{}}',
            encoding="utf-8"
        )

    adb("shell", "mkdir", "-p", FIXTURE_FOLDER)
    adb("push", str(pixel_fixture), FIXTURE_FOLDER + "/pixel.png")
    for filename in ("pack-one.json", "pack-two.json"):
        adb("push", str(fixture_dir / filename), FIXTURE_FOLDER + "/" + filename)
    for archive_name in ("test-one.zip", "test-two.zip"):
        adb("push", str(fixture_dir / archive_name), FIXTURE_FOLDER + "/" + archive_name)

    for folder in (GAME_FOLDER, GAME_FOLDER_TWO):
        adb("shell", "mkdir", "-p", folder)
        game_asset = "Wayward_WML_ko-second.html" if folder == GAME_FOLDER_TWO else "Wayward_WML_ko.html"
        adb("push", str(fixture_dir / game_asset), folder + "/Wayward_WML_ko.html")
        adb("push", str(fixture_dir / "session-second.html"), folder + "/session-second.html")
        adb("push", str(fixture_dir / "picker-frame.html"), folder + "/picker-frame.html")
    adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    adb("logcat", "-c")

    picker_local = pathlib.Path("/tmp/wayward-main-activity-game.xml")
    picker_device = "/sdcard/wayward-main-activity-game.xml"
    home_xml = choose_download_as_game_folder(picker_local, picker_device, "WaywardGameTest")
    home_xml = choose_download_as_game_folder(picker_local, picker_device, "WaywardGameTestTwo")
    launch_buttons = node_bounds_all_exact(home_xml, ("실행", "Launch"))
    if len(launch_buttons) != 2:
        raise RuntimeError("The launcher did not retain exactly two registered game profiles.")
    tap_bounds(launch_buttons[0])

    game_xml = wait_for_ui_contains(
        picker_local, picker_device, "Open image-pack folder", timeout_seconds=35
    )
    (ARTIFACTS / "android15-main-activity-game-before-picker.xml").write_text(
        game_xml, encoding="utf-8"
    )
    pick_button = node_bounds(game_xml, ("Open image-pack folder",))
    if pick_button is None:
        raise RuntimeError("The real game WebView did not expose its image-pack folder action.")
    tap_bounds(pick_button)

    # This must be ImagePackFileSystemAccess' own picker, not DocumentsUI or <input type=file>.
    folder_xml = ""
    select_folder = None
    for _ in range(18):
        try:
            folder_xml = dump_ui(picker_local, picker_device)
            select_folder = node_bounds(folder_xml, ("이 폴더 선택", "Select this folder"))
            if select_folder is not None:
                break
        except Exception:
            pass
        time.sleep(2)
    (ARTIFACTS / "android15-main-activity-imagepack-picker.xml").write_text(
        folder_xml, encoding="utf-8"
    )
    if select_folder is None:
        raise RuntimeError("The real game session did not open the launcher's own image-pack browser.")
    if "/Download" not in folder_xml:
        raise RuntimeError("The production image-pack browser did not start from Download.")
    tap_bounds(select_folder)

    game_xml = wait_for_ui_contains(
        picker_local, picker_device, "PASS_GAME_SESSION_DIRECTORY_HANDLE", timeout_seconds=35
    )
    (ARTIFACTS / "android15-main-activity-game-handle-pass.xml").write_text(
        game_xml, encoding="utf-8"
    )
    if "FAIL_GAME_SESSION_DIRECTORY_HANDLE" in game_xml:
        raise RuntimeError("The actual game session could not receive or read the JS directory handle.")
    if "frame=iframe" not in game_xml:
        raise RuntimeError("The folder picker did not execute and resolve inside the same-origin iframe.")
    print("PASS: actual MainActivity game WebView received a JS directory handle, enumerated Download, and read pixel.png.")

    single_button = node_bounds(game_xml, ("Pick multiple pack.json files",))
    if single_button is None:
        raise RuntimeError("The game page did not expose its image-pack JSON multi-file input trigger.")
    tap_bounds(single_button)
    pick_multiple_documents(
        picker_local, picker_device, ("pack-one.json", "pack-two.json"),
        "android15-pack-json-multiselect.xml"
    )
    single_xml = wait_for_ui_contains(
        picker_local, picker_device, "PASS_PACK_JSON_MULTI_FILES", timeout_seconds=25
    )
    if "PASS_PACK_JSON_MULTI_FILES" not in single_xml or "pack-one.json" not in single_xml or "pack-two.json" not in single_xml:
        raise RuntimeError("The actual game pack.json multi-file input did not return both selected JSON files.")
    print("PASS: the actual game pack.json multi-file input returned both selected pack files.")

    zip_button = node_bounds(single_xml, ("Pick multiple ZIP files",))
    if zip_button is None:
        raise RuntimeError("The game page did not expose its multiple-ZIP input trigger.")
    tap_bounds(zip_button)
    pick_multiple_zip_documents(picker_local, picker_device)
    zip_xml = wait_for_ui_contains(picker_local, picker_device, "PASS_ZIP_MULTI_FILES", timeout_seconds=25)
    if "PASS_ZIP_MULTI_FILES" not in zip_xml or "test-one.zip" not in zip_xml or "test-two.zip" not in zip_xml:
        raise RuntimeError("The WebView ZIP multi-select callback did not return both selected URIs.")
    print("PASS: the real game WebView ZIP multi-select callback returned both selected files.")

    first_count, first_tasks, first_state = count_main_activity_instances()
    if first_count != 2 or len(first_tasks) != 2:
        (ARTIFACTS / "android15-game-activity-stack-before-relaunch.txt").write_text(first_state, encoding="utf-8")
        raise RuntimeError("Expected launcher and first game session in separate URI-keyed Tasks; found activities="
                           + str(first_count) + " taskIds=" + repr(sorted(first_tasks)))
    events = read_launcher_events()
    first_created = [event for event in events if event.get("event") == "game_webview_created"]
    if len(first_created) != 1:
        raise RuntimeError("The first game launch did not create exactly one WebView instance.")
    first_session = first_created[0].get("parsedDetails", {}).get("sessionId", "")
    first_activity_instance = first_created[0].get("parsedDetails", {}).get("activityInstanceId", "")
    first_webview_instance = first_created[0].get("parsedDetails", {}).get("webViewInstanceId", "")
    if not first_session or not first_activity_instance or not first_webview_instance:
        raise RuntimeError("The first game WebView creation event is missing instance/session correlation.")

    return_button = node_bounds(zip_xml, ("Return to launcher",))
    if return_button is None:
        raise RuntimeError("The game fixture did not expose its return-to-launcher control.")
    tap_bounds(return_button)
    home_xml = wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=20)
    same_profile_buttons = node_bounds_all_exact(home_xml, ("실행", "Launch"))
    if len(same_profile_buttons) != 2:
        raise RuntimeError("Both registered game launch actions were not present in the in-session launcher.")
    count_before_relaunch, _, state_before_relaunch = count_main_activity_instances()
    tap_bounds(same_profile_buttons[0])
    game_xml = wait_for_ui_contains(
        picker_local, picker_device, "Open image-pack folder", timeout_seconds=25
    )
    count_after_relaunch, _, state_after_relaunch = count_main_activity_instances()
    (ARTIFACTS / "android15-game-activity-stack-same-profile-relaunch.txt").write_text(
        state_after_relaunch, encoding="utf-8"
    )
    if count_before_relaunch != 2 or count_after_relaunch != count_before_relaunch:
        raise RuntimeError(
            "Same-profile relaunch created a duplicate MainActivity. Before="
            + str(count_before_relaunch) + " after=" + str(count_after_relaunch)
        )
    events = read_launcher_events()
    if len(session_events(events, "game_webview_created", first_session)) != 1:
        raise RuntimeError("Same-profile relaunch recreated the existing game's WebView.")
    same_instance_visible = [
        event for event in session_events(events, "game_session_visible", first_session)
        if event.get("parsedDetails", {}).get("activityInstanceId") == first_activity_instance
        and event.get("parsedDetails", {}).get("webViewInstanceId") == first_webview_instance
    ]
    if not same_instance_visible:
        raise RuntimeError("Same-profile relaunch did not re-show the original Activity and WebView instance.")
    interact_button = node_bounds(game_xml, ("Interact with game",))
    if interact_button is None:
        raise RuntimeError("The relaunched game did not expose its interactive game action.")
    tap_bounds(interact_button)
    interaction_xml = wait_for_ui_contains(
        picker_local, picker_device, "GAME_INTERACTION_OK count=1", timeout_seconds=15
    )
    if "GAME_INTERACTION_OK count=1" not in interaction_xml:
        raise RuntimeError("The existing game WebView did not respond to a real user interaction.")
    print("PASS: same-profile relaunch reused one Activity/WebView and the game fixture remained interactive.")

    return_button = node_bounds(interaction_xml, ("Return to launcher",))
    if return_button is None:
        raise RuntimeError("The first game did not expose its launcher return control after interaction.")
    tap_bounds(return_button)
    home_xml = wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=20)
    profile_buttons = node_bounds_all_exact(home_xml, ("실행", "Launch"))
    if len(profile_buttons) != 2:
        raise RuntimeError("The second registered game's launch action is missing.")
    count_before_second, _, _ = count_main_activity_instances()
    tap_bounds(profile_buttons[1])
    second_game_xml = wait_for_ui_contains(
        picker_local, picker_device, "WAYWARD SECOND GAME SESSION", timeout_seconds=35
    )
    count_after_second, second_tasks, second_stack = count_main_activity_instances()
    (ARTIFACTS / "android15-game-activity-stack-two-sessions.txt").write_text(
        second_stack, encoding="utf-8"
    )
    if count_before_second != 2 or count_after_second != 3 or len(second_tasks) != 3:
        raise RuntimeError("Launching a different profile did not create exactly one independent Activity in its own URI-keyed Task. "
                           + "activities=" + str(count_after_second) + " taskIds=" + repr(sorted(second_tasks)))
    events = read_launcher_events()
    all_created = [event for event in events if event.get("event") == "game_webview_created"]
    second_created = next((
        event for event in all_created
        if event.get("parsedDetails", {}).get("sessionId") != first_session
    ), None)
    if second_created is None:
        raise RuntimeError("The second profile did not get its own independent WebView instance.")
    second_session = second_created.get("parsedDetails", {}).get("sessionId", "")
    second_activity_instance = second_created.get("parsedDetails", {}).get("activityInstanceId", "")
    if not second_session or second_activity_instance == first_activity_instance:
        raise RuntimeError("The second profile did not get an independent Activity identity.")
    print("PASS: a different registered game opened in its own independent Activity and WebView.")

    return_button = node_bounds(second_game_xml, ("Return to launcher",))
    if return_button is None:
        raise RuntimeError("The second game fixture did not expose its launcher return control.")
    tap_bounds(return_button)
    home_xml = wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=20)
    profile_buttons = node_bounds_all_exact(home_xml, ("실행", "Launch"))
    count_before_route, _, _ = count_main_activity_instances()
    tap_bounds(profile_buttons[0])
    try:
        first_game_xml = wait_for_ui_contains(
            picker_local, picker_device, "WAYWARD MAIN ACTIVITY GAME SESSION", timeout_seconds=30
        )
    except RuntimeError as route_error:
        route_debug = capture_game_route_diagnostics(
            "existing-first-profile-return", picker_local, picker_device
        )
        raise RuntimeError(
            str(route_error) + "; session route diagnostics=" +
            json.dumps(route_debug, ensure_ascii=False)
        ) from route_error
    count_after_route, routed_tasks, route_state = count_main_activity_instances()
    (ARTIFACTS / "android15-game-activity-stack-routed-existing.txt").write_text(
        route_state, encoding="utf-8"
    )
    if count_before_route != 3 or count_after_route != count_before_route or len(routed_tasks) != 3:
        raise RuntimeError("Returning to an existing profile created another Activity/Task instead of reusing its URI-keyed Task. "
                           + "activities=" + str(count_after_route) + " taskIds=" + repr(sorted(routed_tasks)))
    events = read_launcher_events()
    routed = [
        event for event in session_events(events, "activity_new_intent", first_session)
        if event.get("parsedDetails", {}).get("activityInstanceId") == first_activity_instance
    ]
    if not routed:
        raise RuntimeError("The previous game session did not receive its matching session Intent via onNewIntent.")
    if len(session_events(events, "game_webview_created", first_session)) != 1:
        raise RuntimeError("Returning to the first profile replaced its original WebView.")
    print("PASS: an existing other game session was brought to front by its session URI and original WebView identity.")

    interact_button = node_bounds(first_game_xml, ("Interact with game",))
    if interact_button is None:
        raise RuntimeError("The routed-back game did not expose its interactive action.")
    tap_bounds(interact_button)
    first_interaction = wait_for_ui_contains(
        picker_local, picker_device, "GAME_INTERACTION_OK count=2", timeout_seconds=15
    )
    if "GAME_INTERACTION_OK count=2" not in first_interaction:
        raise RuntimeError("The routed-back game's original JavaScript state did not survive the profile switch.")

    first_interaction_path = ARTIFACTS / "android15-game-before-second-page.xml"
    first_interaction_path.write_text(first_interaction, encoding="utf-8")
    screenshot(ARTIFACTS / "android15-game-before-second-page.png")
    second_links = node_bounds_all_exact(first_interaction, ("Open second internal page",))
    if len(second_links) != 1:
        raise RuntimeError("The game fixture did not expose exactly one visible internal-page button.")
    tap_bounds(second_links[0])
    try:
        second_xml = wait_for_ui_contains(
            picker_local, picker_device, "SECOND_SESSION_PAGE", timeout_seconds=25
        )
    except RuntimeError as navigation_error:
        route_debug = capture_game_route_diagnostics(
            "internal-second-page-navigation", picker_local, picker_device
        )
        raise RuntimeError(
            str(navigation_error) + "; internal page diagnostics=" +
            json.dumps(route_debug, ensure_ascii=False)
        ) from navigation_error
    adb("shell", "input", "keyevent", "4")
    first_xml = wait_for_ui_contains(
        picker_local, picker_device, "WAYWARD MAIN ACTIVITY GAME SESSION", timeout_seconds=25
    )
    if "SECOND_SESSION_PAGE" in first_xml:
        raise RuntimeError("The first BACK did not return from the second page to the first game page.")

    adb("shell", "input", "keyevent", "4")
    home_xml = wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=25)
    if "WAYWARD MAIN ACTIVITY GAME SESSION" in home_xml:
        raise RuntimeError("Finishing the first session did not reveal the prior second-session launcher panel.")

    second_profile_buttons = node_bounds_all_exact(home_xml, ("실행", "Launch"))
    if len(second_profile_buttons) != 2:
        raise RuntimeError("The prior second-session Activity lost its game profile actions.")
    count_before_second_reuse, second_reuse_tasks, _ = count_main_activity_instances()
    tap_bounds(second_profile_buttons[1])
    second_game_xml = wait_for_ui_contains(
        picker_local, picker_device, "WAYWARD SECOND GAME SESSION", timeout_seconds=25
    )
    count_after_second_reuse, second_reuse_tasks_after, _ = count_main_activity_instances()
    if (count_before_second_reuse != 2 or count_after_second_reuse != count_before_second_reuse
            or len(second_reuse_tasks) != 2 or second_reuse_tasks_after != second_reuse_tasks):
        raise RuntimeError("Returning to the second profile did not reuse its original Activity and document Task.")
    if node_bounds(second_game_xml, ("Interact with game",)) is None:
        raise RuntimeError("The second game did not return to its original WebView content.")
    adb("shell", "input", "keyevent", "4")
    home_xml = wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=25)
    final_activity_count, _, final_activity_state = count_main_activity_instances()
    (ARTIFACTS / "android15-game-activity-stack-after-all-sessions.txt").write_text(
        final_activity_state, encoding="utf-8"
    )
    if final_activity_count != 1:
        raise RuntimeError("Returning from both game sessions did not leave exactly one launcher Activity.")

    # Exercise app background/foreground and process termination/restart.
    adb("shell", "input", "keyevent", "3")
    time.sleep(2)
    # Simulate the actual launcher icon intent flags (NEW_TASK | RESET_TASK_IF_NEEDED).
    adb("shell", "am", "start", "-W", "-f", "0x10200000",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", ACTIVITY)
    home_xml = wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=20)
    foreground_count, _, foreground_state = count_main_activity_instances()
    (ARTIFACTS / "android15-game-activity-stack-after-background.txt").write_text(
        foreground_state, encoding="utf-8"
    )
    if foreground_count != 1:
        route_debug = capture_game_route_diagnostics(
            "duplicate-launcher-after-background", picker_local, picker_device
        )
        raise RuntimeError(
            "Background/foreground returned with duplicate launcher/session Activity records: "
            + str(foreground_count) + "; diagnostics=" + json.dumps(route_debug, ensure_ascii=False)
        )
    adb("shell", "am", "force-stop", PACKAGE)
    # Simulate the actual launcher icon intent flags (NEW_TASK | RESET_TASK_IF_NEEDED).
    adb("shell", "am", "start", "-W", "-f", "0x10200000",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", ACTIVITY)
    home_xml = wait_for_ui_contains(picker_local, picker_device, "WaywardGameTestTwo", timeout_seconds=25)
    restart_count, _, restart_state = count_main_activity_instances()
    if restart_count != 1:
        raise RuntimeError("A process restart did not restore a single clean launcher Activity.")
    (ARTIFACTS / "android15-game-activity-stack-after-process-restart.txt").write_text(
        restart_state, encoding="utf-8"
    )
    restart_buttons = node_bounds_all_exact(home_xml, ("실행", "Launch"))
    if len(restart_buttons) != 2:
        raise RuntimeError("Registered game profiles did not survive process termination.")
    tap_bounds(restart_buttons[1])
    restarted_game = wait_for_ui_contains(
        picker_local, picker_device, "WAYWARD SECOND GAME SESSION", timeout_seconds=35
    )
    restart_interact = node_bounds(restarted_game, ("Interact with game",))
    if restart_interact is None:
        raise RuntimeError("The game did not launch after a process restart.")
    tap_bounds(restart_interact)
    wait_for_ui_contains(picker_local, picker_device, "GAME_INTERACTION_OK count=1", timeout_seconds=15)
    adb("shell", "input", "keyevent", "4")
    home_xml = wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=25)

    recent = adb("shell", "dumpsys", "activity", "recents", check=False, capture=True).stdout or ""
    launcher_tasks = parse_wayward_recent_task_records(recent)
    task_ids = [task_id for task_id, _component in launcher_tasks]
    if len(launcher_tasks) != 1 or len(set(task_ids)) != 1:
        (ARTIFACTS / "android15-recents-after-game-back.txt").write_text(recent, encoding="utf-8")
        raise RuntimeError(
            "Expected exactly one Wayward task in canonical Recent tasks after returning from the game; "
            + "found task records " + repr(launcher_tasks) + "."
        )
    (ARTIFACTS / "android15-recents-after-game-back.txt").write_text(recent, encoding="utf-8")
    print(
        "PASS: first BACK navigated the WebView; root BACK returned to the launcher; "
        + "canonical Recents contains Wayward Task ID " + str(task_ids[0]) + "."
    )

    diagnostics_button = node_bounds(home_xml, ("진단",))
    if diagnostics_button is None:
        raise RuntimeError("The launcher home screen did not expose the diagnostics share button.")
    seeded_previous = ensure_previous_diagnostic_journals()
    tap_bounds(diagnostics_button)
    time.sleep(2)
    chooser_state = adb("shell", "dumpsys", "activity", "activities", check=False, capture=True).stdout or ""
    zip_listing = adb("shell", "run-as", PACKAGE, "ls", "-1", "cache/diagnostics",
                      check=False, capture=True)
    (ARTIFACTS / "android15-diagnostics-share-chooser.txt").write_text(
        chooser_state + "\\n\\n" + (zip_listing.stdout or zip_listing.stderr or ""), encoding="utf-8"
    )
    if "ChooserActivity" not in chooser_state and "ResolverActivity" not in chooser_state:
        raise RuntimeError("The Android native sharesheet did not open after tapping diagnostics.")
    zip_names = re.findall(r"wayward-diagnostics-[0-9-]+\.zip", zip_listing.stdout or "")
    if zip_listing.returncode != 0 or not zip_names:
        raise RuntimeError("The diagnostics button did not produce a readable ZIP in app cache.")
    exported_zip = ARTIFACTS / "android15-diagnostics-export.zip"
    with exported_zip.open("wb") as output:
        subprocess.run(
            ["adb", "exec-out", "run-as", PACKAGE, "cat", "cache/diagnostics/" + zip_names[-1]],
            check=True, stdout=output
        )
    source_listing = adb("shell", "run-as", PACKAGE, "ls", "-1", "files/diagnostics",
                           check=False, capture=True)
    if source_listing.returncode != 0:
        raise RuntimeError("The local diagnostics journal directory could not be inspected.")
    available_sources = {line.strip() for line in (source_listing.stdout or "").splitlines()}
    with zipfile.ZipFile(exported_zip, "r") as archive:
        names = set(archive.namelist())
        required = {"metadata.json", "launcher.ndjson", "imagepack.ndjson"}
        expected_previous = {"launcher.previous.ndjson", "imagepack.previous.ndjson"}
        if not expected_previous.issubset(available_sources):
            raise RuntimeError("The diagnostics fixture did not leave both previous journals on disk.")
        if not required.issubset(names) or not expected_previous.issubset(names):
            raise RuntimeError("The shared ZIP is missing active or previous diagnostic journals.")
        media = {name for name in names if name.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".zip"))}
        if media:
            raise RuntimeError("The shared ZIP unexpectedly contains game/user media: " + repr(sorted(media)))
        metadata = json.loads(archive.read("metadata.json").decode("utf-8"))
        if not metadata.get("appVersion") or not metadata.get("androidSdk"):
            raise RuntimeError("The shared ZIP metadata is missing the app or Android build information.")
        previous_payloads = {}
        for name in expected_previous:
            payload = archive.read(name).decode("utf-8")
            lines = [line for line in payload.splitlines() if line.strip()]
            if not lines:
                raise RuntimeError("The ZIP contains an empty previous journal: " + name)
            try:
                for line in lines:
                    json.loads(line)
            except json.JSONDecodeError as error:
                raise RuntimeError("The previous journal is not valid NDJSON: " + name) from error
            previous_payloads[name] = payload
        if ("launcher.previous.ndjson" in seeded_previous
                and "previous_launcher_journal_fixture" not in previous_payloads["launcher.previous.ndjson"]):
            raise RuntimeError("The ZIP did not preserve the seeded previous launcher journal record.")
        if ("imagepack.previous.ndjson" in seeded_previous
                and "previous_imagepack_journal_fixture" not in previous_payloads["imagepack.previous.ndjson"]):
            raise RuntimeError("The ZIP did not preserve the seeded previous image-pack journal record.")
        events = [json.loads(line) for line in archive.read("imagepack.ndjson").decode("utf-8").splitlines() if line.strip()]
        if not events or not any(item.get("requestId") for item in events):
            raise RuntimeError("The ZIP has no correlated image-picker request diagnostics.")
        chooser_calls = [item for item in events if item.get("stage") == "web_file_chooser_called"]
        chooser_results = [item for item in events if item.get("stage") == "web_file_chooser_callback_delivered"]
        json_calls = [item for item in chooser_calls
                      if "application/json" in str(item.get("details", {}).get("acceptTypes", ""))]
        multiple_calls = [item for item in chooser_calls
                          if item.get("details", {}).get("mode") == "multiple"]
        if not json_calls or not multiple_calls or not chooser_results:
            raise RuntimeError(
                "The ZIP is missing pack.json/multiple-file chooser requests or their WebView callback result."
            )
        sensitive_patterns = (
            re.compile(r"(?i)content://[^\s,;\"']+"),
            re.compile(r"(?i)(?:/storage/emulated/\d+|/sdcard|/mnt/user/\d+/primary|/data/(?:user(?:_de)?/\d+|data)|/data/local/tmp)/[^\s,;\"']+"),
            re.compile(r"(?i)Bearer\s+[A-Za-z0-9._~+/-]+=*"),
            re.compile(r"(?i)(?:access|refresh)?_?(?:token|api[_-]?key|secret|password|cookie)\s*[:=]\s*[\"']?[^\s,;\"'}&]+"),
        )
        for name in sorted(value for value in names if value.endswith(".ndjson")):
            payload = archive.read(name).decode("utf-8", errors="replace")
            if any(pattern.search(payload) for pattern in sensitive_patterns):
                raise RuntimeError("The diagnostic ZIP exposes a credential, URI, or absolute path in " + name)
    upload_result = wait_for_cloudflare_diagnostic_upload()
    if not upload_result.get("success"):
        raise RuntimeError("The Cloudflare upload success event did not confirm a successful submission.")
    print("PASS: diagnostic ZIP created, metadata and correlated events verified, Android sharesheet opened.")
    print("PASS: user-triggered diagnostic ZIP received a successful Cloudflare ticketed upload.")

    adb("shell", "input", "keyevent", "4")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=25)
    journal = adb("shell", "run-as", PACKAGE, "ls", "-l", "files/diagnostics",
                  check=False, capture=True)
    if journal.returncode != 0 or "imagepack.ndjson" not in (journal.stdout or ""):
        raise RuntimeError("Image-pack diagnostic journal did not survive app restart.")
    print("PASS: image-pack diagnostic journal survived game exit and app restart.")


def run_native_folder_acceptance() -> None:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ARTIFACTS / "imagepack-acceptance-pixel.png"
    fixture.write_bytes(make_png())
    adb("shell", "mkdir", "-p", FIXTURE_FOLDER)
    adb("shell", "mkdir", "-p", "/sdcard/Documents")
    adb("shell", "mkdir", "-p", FIXTURE_FOLDER + "/__wayward_nav_child")
    adb("shell", "touch", "/sdcard/Documents/outside.txt")
    adb("shell", "rm", "-f", FIXTURE_FOLDER + "/__wayward_escape")
    symlink_result = adb("shell", "ln", "-s", "/sdcard/Documents", FIXTURE_FOLDER + "/__wayward_escape", check=False, capture=True)
    symlink_fixture_created = symlink_result.returncode == 0
    if not symlink_fixture_created:
        print("SKIP: Android emulator shared storage does not permit creating a symbolic-link fixture.")
        print((symlink_result.stderr or symlink_result.stdout or "").strip())
    adb("push", str(fixture), FIXTURE_FOLDER + "/pixel.png")
    adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PICKER_TEST_ACTIVITY,
        "--ez", "verify_symlink_fixture", str(symlink_fixture_created).lower())

    picker_local = pathlib.Path("/tmp/wayward-imagepack-folder-picker.xml")
    picker_device = "/sdcard/wayward-imagepack-folder-picker.xml"
    picker_xml = ""
    select_folder = None
    for _ in range(40):
        try:
            picker_xml = dump_ui(picker_local, picker_device)
            select_folder = node_bounds(picker_xml, ("이 폴더 선택", "Select this folder"))
            if select_folder is not None:
                break
        except Exception:
            pass
        time.sleep(1)

    (ARTIFACTS / "android15-imagepack-folder-picker.xml").write_text(
        picker_xml, encoding="utf-8"
    )
    screenshot(ARTIFACTS / "android15-imagepack-folder-picker.png")
    if select_folder is None:
        raise RuntimeError("The native image-pack browser did not expose the '이 폴더 선택' button.")

    # Confirm Download and exercise creation, child navigation, and parent navigation.
    if "/Download" not in picker_xml:
        raise RuntimeError("The native image-pack browser did not start in Download.")
    create_folder_button = node_bounds(picker_xml, ("새 폴더",))
    if create_folder_button is None:
        raise RuntimeError("The custom folder browser did not expose its new-folder action.")
    tap_bounds(create_folder_button)
    time.sleep(1)
    adb("shell", "input", "text", "__wayward_ui_created")
    create_button = node_bounds(dump_ui(picker_local, picker_device), ("만들기",))
    if create_button is None:
        raise RuntimeError("The custom folder creation dialog did not expose its create action.")
    tap_bounds(create_button)
    time.sleep(1)
    picker_xml = dump_ui(picker_local, picker_device)
    if "__wayward_ui_created" not in picker_xml:
        raise RuntimeError("The custom browser did not refresh after creating a folder.")

    child_row = node_bounds(picker_xml, ("__wayward_nav_child",), require_clickable=False)
    if child_row is None:
        raise RuntimeError("The custom folder browser did not list its child directory.")
    tap_bounds(child_row)
    time.sleep(1)
    child_xml = dump_ui(picker_local, picker_device)
    if "/Download/__wayward_nav_child" not in child_xml:
        raise RuntimeError("The custom folder browser did not navigate into its child directory.")

    parent_row = node_bounds(child_xml, ("상위 폴더",), require_clickable=False)
    if parent_row is None:
        raise RuntimeError("The custom folder browser did not expose parent navigation.")
    tap_bounds(parent_row)
    time.sleep(1)
    picker_xml = dump_ui(picker_local, picker_device)
    if "/Download/__wayward_nav_child" in picker_xml or "/Download" not in picker_xml:
        raise RuntimeError("The custom folder browser did not return to Download.")
    select_folder = node_bounds(picker_xml, ("이 폴더 선택", "Select this folder"))
    if select_folder is None:
        raise RuntimeError("The folder selection action disappeared after parent navigation.")

    tap_bounds(select_folder)
    first_log = wait_for_log("PASS fs-directory-handle")
    if "FAIL " in first_log:
        raise RuntimeError("Selected-root CRUD or symbolic-link boundary acceptance failed: " + first_log[-4000:])
    if "PASS selected-root-boundary" not in first_log:
        raise RuntimeError("Selected-root CRUD and boundary tests did not report success.")
    if "listed=" not in first_log or "bytes=" not in first_log:
        raise RuntimeError("The returned directory handle did not enumerate and read the selected image.")
    print("PASS: adapter test returned a directory-handle-compatible JS object, listed files, and read Download/pixel.png.")
    print(first_log[-1800:])

    adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(2)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PICKER_TEST_ACTIVITY,
        "--ez", "verify_existing", "true")
    second_log = wait_for_log("PASS persisted-after-restart", timeout_seconds=30)
    print("PASS: the selected filesystem folder and PNG survived a process restart.")
    print(second_log[-1400:])

    adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "deny")
    adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(2)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PICKER_TEST_ACTIVITY,
        "--ez", "verify_revoked", "true")
    revoked_log = wait_for_log("PASS permission-revoked", timeout_seconds=30)
    print("PASS: revoking MANAGE_EXTERNAL_STORAGE denied listing and image reads.")
    print(revoked_log[-1200:])

    adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    if symlink_fixture_created:
        adb("shell", "rm", "-f", FIXTURE_FOLDER + "/__wayward_escape")


def main() -> int:
    if not DEBUG_APK.is_file() or DEBUG_APK.stat().st_size == 0:
        raise RuntimeError(f"Debug APK was not found: {DEBUG_APK}")

    adb("wait-for-device")
    boot_completed = ""
    for _ in range(60):
        result = adb("shell", "getprop", "sys.boot_completed", check=False, capture=True)
        boot_completed = (result.stdout or "").strip()
        if boot_completed == "1":
            break
        time.sleep(2)
    if boot_completed != "1":
        raise RuntimeError("Android emulator did not complete boot within 120 seconds.")

    for package in ("com.google.android.apps.nexuslauncher", "com.android.launcher3"):
        adb("shell", "am", "force-stop", package, check=False)

    adb("install", "-r", str(DEBUG_APK))
    adb("shell", "am", "force-stop", PACKAGE, check=False)
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    time.sleep(6)

    pid = adb("shell", "pidof", "-s", PACKAGE, check=False, capture=True).stdout or ""
    LOCAL_PID.write_text(pid, encoding="utf-8")
    crash = adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False, capture=True).stdout or ""
    LOCAL_CRASH.write_text(crash, encoding="utf-8")
    if not pid.strip() or f"Process: {PACKAGE}" in crash:
        raise RuntimeError("Launcher process did not remain alive or AndroidRuntime reported a crash.")

    dump_ui(LOCAL_XML, DEVICE_XML)
    xml = LOCAL_XML.read_text(encoding="utf-8", errors="replace")
    if "Pixel Launcher isn't responding" in xml:
        adb("shell", "input", "tap", "540", "1363", check=False)
        time.sleep(3)
        for package in ("com.google.android.apps.nexuslauncher", "com.android.launcher3", PACKAGE):
            adb("shell", "am", "force-stop", package, check=False)
        adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        time.sleep(5)
        dump_ui(LOCAL_XML, DEVICE_XML)
        xml = LOCAL_XML.read_text(encoding="utf-8", errors="replace")

    expected_labels = (
        "이미지 생성 서버 실행",
        "ComfyUI 서버 실행",
        "이미지팩만 사용",
        "이 기기에 설치된 이미지팩만 탐색",
        "NovelAI 토큰 설정",
        "PC 폴더",
        "현재 기기 폴더",
        "페어링",
        "진단",
        "원격 PC 파일매니저",
    )
    missing = [label for label in expected_labels if label not in xml]
    if "Pixel Launcher isn't responding" in xml:
        raise RuntimeError("Pixel Launcher ANR dialog remained on top of Wayward.")
    if missing:
        raise RuntimeError("Android 15 accessibility tree is missing controls: " + ", ".join(missing))

    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    screenshot(ARTIFACTS / "android15-launcher-home.png")
    shutil.copy2(LOCAL_XML, ARTIFACTS / "android15-launcher-home.xml")
    run(["python3", "scripts/check-glass-ui-layout.py", str(LOCAL_XML)])
    if (ARTIFACTS / "android15-launcher-home.png").stat().st_size == 0:
        raise RuntimeError("Android 15 home screenshot is empty.")

    run_main_activity_game_session_acceptance()
    run_native_folder_acceptance()
    run(["gradle", "--no-daemon", "connectedDebugAndroidTest"])

    print("PASS: Android 15 launcher startup and existing native instrumentation tests.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(f"Android 15 emulator command failed ({error.returncode}): {error.cmd}", file=sys.stderr)
        raise SystemExit(error.returncode or 1)
    except Exception as error:
        print(f"Android 15 emulator acceptance failed: {error}", file=sys.stderr)
        raise SystemExit(1)
