#!/usr/bin/env python3
"""Android 15 launcher smoke test with the native image-pack folder browser."""

from __future__ import annotations

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
    result = adb("shell", "uiautomator", "dump", "--compressed", device_path,
                 check=False, capture=True)
    if result.returncode != 0:
        raise RuntimeError(
            "Android UI hierarchy dump failed (" + str(result.returncode) + "): " +
            (result.stderr or result.stdout or "no diagnostic output")[-1200:]
        )
    adb("pull", device_path, str(local_path))
    return local_path.read_text(encoding="utf-8", errors="replace")


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


def choose_download_as_game_folder(local_path: pathlib.Path, device_path: str) -> str:
    """Drive the real MainActivity game-folder SAF picker and select a child of Download."""
    home_xml = dump_ui(local_path, device_path)
    add_button = node_bounds(home_xml, ("현재 기기 폴더", "Add device folder"))
    if add_button is None:
        raise RuntimeError("MainActivity did not expose its device-folder registration button.")
    tap_bounds(add_button)

    download_selected = False
    game_folder_opened = False
    latest = ""
    for _ in range(50):
        latest = dump_ui(local_path, device_path)

        if download_selected and not game_folder_opened:
            child_folder = node_bounds(latest, ("WaywardGameTest",), require_clickable=False)
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
        raise RuntimeError("DocumentsUI did not allow the real launcher to select Download/WaywardGameTest.")

    # Android may ask for a second explicit grant after the tree button is pressed.
    time.sleep(1)
    latest = dump_ui(local_path, device_path)
    (ARTIFACTS / "android15-game-tree-picker-after-use.xml").write_text(latest, encoding="utf-8")
    screenshot(ARTIFACTS / "android15-game-tree-picker-after-use.png")
    for _ in range(25):
        latest = dump_ui(local_path, device_path)
        if "내 게임" in latest or "My games" in latest:
            (ARTIFACTS / "android15-game-tree-picker-returned.xml").write_text(latest, encoding="utf-8")
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


def run_main_activity_game_session_acceptance() -> None:
    """Exercise the real game WebView, folder bridge, back stack, and Recents."""
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture_dir = ARTIFACTS / "main-activity-game-fixture"
    fixture_dir.mkdir(parents=True, exist_ok=True)
    game_page = """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body>
<h1>WAYWARD MAIN ACTIVITY GAME SESSION</h1>
<button id="pick">Open image-pack folder</button>
<iframe id="picker-frame" src="picker-frame.html" title="Image-pack folder picker frame"
        style="width:1px;height:1px;border:0"></iframe>
<button id="single-button">Pick multiple pack.json files</button>
<input id="single-input" type="file" accept=".json,application/json" multiple hidden>
<button id="zip-button">Pick multiple ZIP files</button>
<input id="zip-input" type="file" accept=".zip,application/zip" multiple hidden>
<a id="second" href="session-second.html">Open second internal page</a>
<div id="result">READY</div><div id="file-result">FILE_READY</div>
<script>
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

    adb("shell", "mkdir", "-p", GAME_FOLDER)
    adb("push", str(fixture_dir / "Wayward_WML_ko.html"), GAME_FOLDER + "/Wayward_WML_ko.html")
    adb("push", str(fixture_dir / "session-second.html"), GAME_FOLDER + "/session-second.html")
    adb("push", str(fixture_dir / "picker-frame.html"), GAME_FOLDER + "/picker-frame.html")
    adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    adb("logcat", "-c")

    picker_local = pathlib.Path("/tmp/wayward-main-activity-game.xml")
    picker_device = "/sdcard/wayward-main-activity-game.xml"
    home_xml = choose_download_as_game_folder(picker_local, picker_device)
    launch_button = node_bounds(home_xml, ("실행", "Launch"))
    if launch_button is None:
        raise RuntimeError("The registered Download game profile did not expose its launch button.")
    tap_bounds(launch_button)

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

    second_link = node_bounds(zip_xml, ("Open second internal page",))
    if second_link is None:
        raise RuntimeError("The game fixture did not expose its second internal page link.")
    tap_bounds(second_link)
    second_xml = wait_for_ui_contains(
        picker_local, picker_device, "SECOND_SESSION_PAGE", timeout_seconds=25
    )
    adb("shell", "input", "keyevent", "4")
    first_xml = wait_for_ui_contains(
        picker_local, picker_device, "PASS_GAME_SESSION_DIRECTORY_HANDLE", timeout_seconds=25
    )
    if "WAYWARD MAIN ACTIVITY GAME SESSION" not in first_xml:
        raise RuntimeError("The first BACK did not navigate within the game WebView history.")

    adb("shell", "input", "keyevent", "4")
    home_xml = wait_for_ui_contains(picker_local, picker_device, "내 게임", timeout_seconds=25)
    if "WAYWARD MAIN ACTIVITY GAME SESSION" in home_xml:
        raise RuntimeError("The root BACK did not finish only the game-session Activity.")

    recent = adb("shell", "dumpsys", "activity", "recents", check=False, capture=True).stdout or ""
    sections = re.split(r"(?=Recent #\d+:)", recent)
    launcher_tasks = [section for section in sections if PACKAGE in section]
    if not launcher_tasks:
        # Some Android builds use a different Recent-entry formatter. Fall back to Task sections.
        sections = re.split(r"(?=Task\{)", recent)
        launcher_tasks = [section for section in sections if PACKAGE in section]
    if len(launcher_tasks) != 1:
        (ARTIFACTS / "android15-recents-after-game-back.txt").write_text(recent, encoding="utf-8")
        raise RuntimeError(
            "Expected exactly one Wayward recent-app task after returning from the game; "
            + str(len(launcher_tasks)) + " task records matched."
        )
    (ARTIFACTS / "android15-recents-after-game-back.txt").write_text(recent, encoding="utf-8")
    print("PASS: first BACK navigated the WebView; root BACK returned to the launcher; Recents contains one launcher task.")

    diagnostics_button = node_bounds(home_xml, ("진단",))
    if diagnostics_button is None:
        raise RuntimeError("The launcher home screen did not expose the diagnostics share button.")
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
    with zipfile.ZipFile(exported_zip, "r") as archive:
        names = set(archive.namelist())
        if "metadata.json" not in names or "imagepack.ndjson" not in names or "launcher.ndjson" not in names:
            raise RuntimeError("The shared ZIP is missing metadata, launcher logs, or request-correlated image-pack logs.")
        metadata = json.loads(archive.read("metadata.json").decode("utf-8"))
        if not metadata.get("appVersion") or not metadata.get("androidSdk"):
            raise RuntimeError("The shared ZIP metadata is missing the app or Android build information.")
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
                "The ZIP is missing the actual game pack.json / multiple-file chooser request "
                "or its WebView callback result."
            )
    print("PASS: diagnostic ZIP created, metadata and correlated events verified, Android sharesheet opened.")

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
