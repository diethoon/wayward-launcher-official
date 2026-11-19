#!/usr/bin/env python3
"""Install both signed product variants together and verify their Android 15 home UI gates."""

from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path.cwd()
ARTIFACTS = ROOT / "artifacts"
OFFICIAL_APK = ROOT / "app/build/outputs/apk/official/app-official.apk"
DEVELOPMENT_APK = ROOT / "app/build/outputs/apk/release/app-release.apk"
OFFICIAL_PACKAGE = "com.wayward.launcher.official.wal"
DEVELOPMENT_PACKAGE = "com.wayward.launcher.official"
ACTIVITY_CLASS = "com.wayward.launcher.MainActivity"
SDK = pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/usr/local/lib/android/sdk")
AAPT = SDK / "build-tools/35.0.0/aapt"


def run(command: list[str], *, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        command,
        check=check,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def adb(*args: str, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    return run(["adb", *args], check=check, capture=capture)


def wait_for_boot() -> None:
    adb("wait-for-device")
    for _ in range(60):
        result = adb("shell", "getprop", "sys.boot_completed", check=False, capture=True)
        if (result.stdout or "").strip() == "1":
            return
        time.sleep(2)
    raise RuntimeError("Android 15 emulator did not finish booting within 120 seconds.")


def wait_for_process(package: str) -> str:
    for _ in range(45):
        result = adb("shell", "pidof", "-s", package, check=False, capture=True)
        pid = (result.stdout or "").strip()
        if pid:
            return pid
        time.sleep(1)
    raise RuntimeError("Application process did not remain alive: " + package)


def dump_ui(prefix: str) -> tuple[str, pathlib.Path]:
    device_path = "/sdcard/wal-product-split-" + prefix + ".xml"
    local_path = ARTIFACTS / ("android15-product-split-" + prefix + ".xml")
    adb("shell", "rm", "-f", device_path, check=False, capture=True)
    result = adb("shell", "uiautomator", "dump", "--compressed", device_path,
                 check=False, capture=True)
    pulled = adb("pull", device_path, str(local_path), check=False, capture=True)
    if pulled.returncode != 0 or not local_path.is_file():
        raise RuntimeError(
            "UI hierarchy pull failed for " + prefix + ": " +
            (pulled.stderr or pulled.stdout or result.stderr or "no output")[-1000:]
        )
    payload = local_path.read_text(encoding="utf-8", errors="replace")
    root = ET.fromstring(payload)
    if root.tag != "hierarchy" or next(root.iter("node"), None) is None:
        raise RuntimeError("UI hierarchy was empty for " + prefix)
    return payload, local_path


def visible_text(xml: str) -> set[str]:
    root = ET.fromstring(xml)
    values: set[str] = set()
    for node in root.iter("node"):
        for key in ("text", "content-desc"):
            value = node.attrib.get(key, "").strip()
            if value:
                values.add(value)
    return values


def install_and_launch(package: str, apk: pathlib.Path, prefix: str) -> set[str]:
    if not apk.is_file() or apk.stat().st_size == 0:
        raise RuntimeError("Required APK is missing: " + str(apk))
    adb("install", "-r", str(apk))
    package_path = adb("shell", "pm", "path", package, check=False, capture=True)
    if package_path.returncode != 0 or "package:" not in (package_path.stdout or ""):
        raise RuntimeError("Package Manager cannot resolve " + package)

    adb("shell", "am", "force-stop", package, check=False, capture=True)
    adb("shell", "am", "start", "-W", "-n", package + "/" + ACTIVITY_CLASS)
    pid = wait_for_process(package)
    time.sleep(4)
    xml, _ = dump_ui(prefix)
    screenshot_device = "/sdcard/wal-product-split-" + prefix + ".png"
    screenshot_local = ARTIFACTS / ("android15-product-split-" + prefix + ".png")
    adb("shell", "screencap", "-p", screenshot_device)
    adb("pull", screenshot_device, str(screenshot_local))
    if not screenshot_local.is_file() or screenshot_local.stat().st_size < 1000:
        raise RuntimeError("Screenshot capture failed for " + prefix)

    log = adb("logcat", "-d", "-v", "brief", "-t", "2500", check=False, capture=True)
    fatal_lines = [
        line for line in (log.stdout or "").splitlines()
        if "FATAL EXCEPTION" in line or "Fatal signal" in line
    ]
    if any(package in line or "com.wayward.launcher" in line for line in fatal_lines):
        raise RuntimeError("Fatal Android exception after launching " + package + ": " +
                           "\n".join(fatal_lines[-12:]))
    print("PRODUCT_LAUNCH_PASS package=" + package + " pid=" + pid)
    return visible_text(xml)


def main() -> int:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    if not AAPT.is_file():
        raise RuntimeError("Android Build Tools aapt is missing: " + str(AAPT))

    official_badging = run([str(AAPT), "dump", "badging", str(OFFICIAL_APK)], capture=True).stdout or ""
    development_badging = run([str(AAPT), "dump", "badging", str(DEVELOPMENT_APK)], capture=True).stdout or ""
    expected_official = "package: name='" + OFFICIAL_PACKAGE + "' versionCode='1' versionName='1.0.0'"
    expected_development = "package: name='" + DEVELOPMENT_PACKAGE + "' versionCode='109' versionName='1.0.9'"
    if expected_official not in official_badging or "application-label:'WAL Android Launcher'" not in official_badging:
        raise RuntimeError("Official APK package/version/label contract mismatch.")
    if expected_development not in development_badging or "application-label:'WAL Development'" not in development_badging:
        raise RuntimeError("Development APK package/version/label contract mismatch.")

    wait_for_boot()
    adb("install", "-r", str(OFFICIAL_APK))
    adb("install", "-r", str(DEVELOPMENT_APK))
    for package in (OFFICIAL_PACKAGE, DEVELOPMENT_PACKAGE):
        result = adb("shell", "pm", "path", package, check=False, capture=True)
        if result.returncode != 0 or "package:" not in (result.stdout or ""):
            raise RuntimeError("Both package IDs must coexist after installation: " + package)

    official = install_and_launch(OFFICIAL_PACKAGE, OFFICIAL_APK, "official")
    official_required = (
        "WAL Android Launcher",
        "내 게임",
        "+ 현재 기기 폴더",
        "이미지팩만 사용 · 활성",
    )
    official_forbidden = (
        "+ PC 폴더",
        "페어링",
        "원격 PC 파일매니저",
        "ComfyUI 서버 실행",
        "PC 페어링 필요",
    )
    missing = [label for label in official_required if label not in official]
    leaked = [label for label in official_forbidden if label in official]
    if missing or leaked:
        raise RuntimeError(
            "Official UI gate mismatch; missing=" + repr(missing) + "; leaked PC UI=" + repr(leaked)
        )
    print("PASS: Official package exposes local game/image-pack controls and no PC-only controls.")

    development = install_and_launch(DEVELOPMENT_PACKAGE, DEVELOPMENT_APK, "development")
    development_required = (
        "Wayward Android Launcher",
        "내 게임",
        "+ PC 폴더",
        "페어링",
        "원격 PC 파일매니저",
        "ComfyUI 서버 실행",
    )
    missing = [label for label in development_required if label not in development]
    if missing:
        raise RuntimeError("Development UI lost expected PC features: " + repr(missing))
    print("PASS: Development package retains PC folder, pairing, Remote PC file manager, and ComfyUI controls.")

    for package in (OFFICIAL_PACKAGE, DEVELOPMENT_PACKAGE):
        if "package:" not in (adb("shell", "pm", "path", package, check=False, capture=True).stdout or ""):
            raise RuntimeError("One product package disappeared after launching both variants: " + package)
    print("PASS: Official 1.0.0 and Development 1.0.9 coexist as separate installed Android applications.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print("Android product split command failed (" + str(error.returncode) + "): " + repr(error.cmd),
              file=sys.stderr)
        raise SystemExit(error.returncode or 1)
    except Exception as error:
        try:
            ARTIFACTS.mkdir(parents=True, exist_ok=True)
            log = adb("logcat", "-d", "-v", "threadtime", check=False, capture=True)
            (ARTIFACTS / "android15-product-split-failure-logcat.txt").write_text(
                log.stdout or "", encoding="utf-8"
            )
            for prefix in ("official", "development"):
                try:
                    dump_ui(prefix + "-failure")
                except Exception:
                    pass
        except Exception as artifact_error:
            print("WARN: failure evidence capture failed: " + str(artifact_error), file=sys.stderr)
        print("Android 15 product split smoke test failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
