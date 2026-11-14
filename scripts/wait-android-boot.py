#!/usr/bin/env python3
"""Wait for the Android emulator to finish booting without shell loop syntax."""

from __future__ import annotations

import subprocess
import sys
import time


def main() -> int:
    for attempt in range(60):
        result = subprocess.run(
            ["adb", "shell", "getprop", "sys.boot_completed"],
            check=False,
            capture_output=True,
            text=True,
        )
        value = (result.stdout or "").replace("\r", "").strip()
        if result.returncode == 0 and value == "1":
            print(f"Android emulator boot completed after {attempt + 1} check(s).")
            return 0
        time.sleep(2)

    print("Android emulator did not report sys.boot_completed=1 within 120 seconds.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
