#!/usr/bin/env python3
"""Android 15 launcher smoke test with a real DocumentsUI SAF tree acceptance test."""

from __future__ import annotations

import pathlib
import re
import shutil
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib

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
FIXTURE_FOLDER = "/sdcard/Documents/WaywardImagePackFixture"
FIXTURE_INITIAL_URI = (
    "content://com.android.externalstorage.documents/document/"
    "primary%3ADocuments%2FWaywardImagePackFixture"
)


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
    adb("shell", "uiautomator", "dump", device_path)
    adb("pull", device_path, str(local_path))
    return local_path.read_text(encoding="utf-8", errors="replace")


def node_bounds(xml: str, labels: tuple[str, ...]) -> tuple[int, int, int, int] | None:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        text = node.attrib.get("text", "")
        description = node.attrib.get("content-desc", "")
        if any(label.casefold() in (text + " " + description).casefold() for label in labels):
            match = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
            if match:
                return tuple(int(value) for value in match.groups())
    return None


def tap_bounds(bounds: tuple[int, int, int, int]) -> None:
    left, top, right, bottom = bounds
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def screenshot(path: pathlib.Path) -> None:
    with path.open("wb") as output:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], check=True, stdout=output)


def wait_for_log(marker: str, *, timeout_seconds: int = 45) -> str:
    latest = ""
    for _ in range(timeout_seconds):
        result = adb("logcat", "-d", "-s", "WaywardImagePackTest:V",
                     check=False, capture=True)
        latest = result.stdout or ""
        if marker in latest:
            return latest
        if "FAIL " in latest:
            raise RuntimeError("Image-pack DocumentsUI acceptance failed: " + latest[-4000:])
        time.sleep(1)
    full_log = adb("logcat", "-d", "-v", "time", check=False, capture=True).stdout or ""
    (ARTIFACTS / "imagepack-acceptance-logcat.txt").write_text(full_log[-120000:], encoding="utf-8")
    raise RuntimeError("Timed out waiting for " + marker + ". Last acceptance log: " + latest[-4000:])


def run_documentsui_acceptance() -> None:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    fixture = ARTIFACTS / "imagepack-acceptance-pixel.png"
    fixture.write_bytes(make_png())
    adb("shell", "mkdir", "-p", FIXTURE_FOLDER)
    adb("push", str(fixture), FIXTURE_FOLDER + "/pixel.png")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PICKER_TEST_ACTIVITY,
        "--es", "initial_uri", FIXTURE_INITIAL_URI)

    picker_local = pathlib.Path("/tmp/wayward-documentsui-picker.xml")
    picker_device = "/sdcard/wayward-documentsui-picker.xml"
    picker_xml = ""
    use_folder = None
    for _ in range(40):
        try:
            picker_xml = dump_ui(picker_local, picker_device)
            use_folder = node_bounds(picker_xml, ("Use this folder", "이 폴더 사용"))
            if use_folder is not None:
                break
            folder_row = node_bounds(picker_xml, ("WaywardImagePackFixture",))
            if folder_row is not None:
                tap_bounds(folder_row)
                time.sleep(2)
                picker_xml = dump_ui(picker_local, picker_device)
                use_folder = node_bounds(picker_xml, ("Use this folder", "이 폴더 사용"))
                if use_folder is not None:
                    break
        except Exception:
            pass
        time.sleep(1)

    (ARTIFACTS / "android15-documentsui-folder-picker.xml").write_text(
        picker_xml, encoding="utf-8"
    )
    screenshot(ARTIFACTS / "android15-documentsui-folder-picker.png")
    if use_folder is None:
        raise RuntimeError("Android 15 DocumentsUI did not expose the 'Use this folder' button.")

    tap_bounds(use_folder)

    # On some Android 15 DocumentsUI builds a second confirmation dialog is shown.
    confirmation = ""
    confirmation_path = ARTIFACTS / "android15-documentsui-permission-confirmation.xml"
    for _ in range(12):
        try:
            confirmation = dump_ui(picker_local, picker_device)
            confirmation_path.write_text(confirmation, encoding="utf-8")
            allow = node_bounds(confirmation, ("Allow", "허용"))
            if allow is not None:
                screenshot(ARTIFACTS / "android15-documentsui-permission-confirmation.png")
                tap_bounds(allow)
                break
        except Exception:
            pass
        time.sleep(1)

    first_log = wait_for_log("PASS picked directory=")
    if "bytes=" not in first_log:
        raise RuntimeError("The selected image file was not read through the native SAF directory handle.")
    print("PASS: Android 15 DocumentsUI displayed 'Use this folder', accepted the folder, and the WebView bridge read pixel.png.")
    print(first_log[-1800:])

    # Terminate the process to prove both SharedPreferences and Android's persisted
    # UriPermission survive a real application restart.
    adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(2)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PICKER_TEST_ACTIVITY,
        "--ez", "verify_existing", "true")
    second_log = wait_for_log("PASS persisted-after-restart", timeout_seconds=30)
    print("PASS: the persisted tree grant and selected PNG survived a process restart.")
    print(second_log[-1400:])


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

    run_documentsui_acceptance()
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
