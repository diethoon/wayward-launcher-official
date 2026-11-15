#!/usr/bin/env python3
"""Android 15 emulator smoke test for the official launcher home screen."""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import time

PACKAGE = "com.wayward.launcher.official.debug"
ACTIVITY = f"{PACKAGE}/com.wayward.launcher.MainActivity"
WORKSPACE = pathlib.Path.cwd()
DEBUG_APK = WORKSPACE / "app/build/outputs/apk/debug/app-debug.apk"
ARTIFACTS = WORKSPACE / "artifacts"
DEVICE_XML = "/sdcard/wayward-launcher-home.xml"
LOCAL_XML = pathlib.Path("/tmp/wayward-launcher-home.xml")
LOCAL_PID = pathlib.Path("/tmp/wayward-launcher-app-pid.txt")
LOCAL_CRASH = pathlib.Path("/tmp/wayward-launcher-crash.log")


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


def dump_ui() -> None:
    adb("shell", "uiautomator", "dump", DEVICE_XML)
    adb("pull", DEVICE_XML, str(LOCAL_XML))


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

    # The stock Pixel Launcher can occasionally surface an ANR dialog in headless CI.
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

    dump_ui()
    xml = LOCAL_XML.read_text(encoding="utf-8", errors="replace")

    # Recover once if Android left the Pixel Launcher ANR dialog on top.
    if "Pixel Launcher isn't responding" in xml:
        adb("shell", "input", "tap", "540", "1363", check=False)
        time.sleep(3)
        for package in ("com.google.android.apps.nexuslauncher", "com.android.launcher3", PACKAGE):
            adb("shell", "am", "force-stop", package, check=False)
        adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        time.sleep(5)
        dump_ui()
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
    with (ARTIFACTS / "android15-launcher-home.png").open("wb") as screenshot:
        subprocess.run(
            ["adb", "exec-out", "screencap", "-p"],
            check=True,
            stdout=screenshot,
        )
    shutil.copy2(LOCAL_XML, ARTIFACTS / "android15-launcher-home.xml")
    run(["python3", "scripts/check-glass-ui-layout.py", str(LOCAL_XML)])
    if (ARTIFACTS / "android15-launcher-home.png").stat().st_size == 0:
        raise RuntimeError("Android 15 home screenshot is empty.")

    # Run real Android-runtime crypto tests for Android Keystore, ECDSA P1363
    # conversion, QR signature expiry, Host identity binding and signed Pairing
    # approval receipt field/permission binding.
    run(["gradle", "--no-daemon", "connectedDebugAndroidTest"])

    print("PASS: Android 15 emulator launched the official launcher and confirmed:")
    for label in expected_labels:
        print(f"  - {label}")
    print("PASS: launcher process is alive and no AndroidRuntime crash was recorded.")
    print("PASS: home screenshot and accessibility XML were captured.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(f"Android 15 emulator command failed ({error.returncode}): {error.cmd}", file=sys.stderr)
        raise SystemExit(error.returncode or 1)
    except Exception as error:
        print(f"Android 15 emulator smoke test failed: {error}", file=sys.stderr)
        raise SystemExit(1)
