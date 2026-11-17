#!/usr/bin/env python3
"""Regression tests for parsing Android dumpsys activity recents output."""

import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "android15_emulator_smoke", ROOT / "scripts" / "android15-emulator-smoke.py"
)
SMOKE = importlib.util.module_from_spec(SPEC)
assert SPEC is not None and SPEC.loader is not None
SPEC.loader.exec_module(SMOKE)


def task(index: int, task_id: int, component: str, calling_package: str = "com.android.shell") -> str:
    return (
        f"  * Recent #{index}: Task{{abc{task_id} #{task_id} type=standard}}\n"
        f"    mCallingPackage={calling_package}\n"
        f"    intent={{flg=0x10000000 cmp={component}}}\n"
        f"    mActivityComponent={component}\n"
        f"    Activities=[ActivityRecord{{abc u0 {component} t{task_id}}}]\n"
    )


class RecentsParserTest(unittest.TestCase):
    def test_visible_summary_does_not_duplicate_the_canonical_task(self) -> None:
        component = f"{SMOKE.PACKAGE}/com.wayward.launcher.MainActivity"
        output = (
            "ACTIVITY MANAGER RECENT TASKS\n"
            "Recent tasks:\n"
            + task(0, 8, component)
            + "  * Recent #1: Task{home #7 type=home}\n"
            "    mActivityComponent=com.google.android.apps.nexuslauncher/.NexusLauncherActivity\n"
            "\nVisible recent tasks (most recent first):\n"
            "  * RecentTaskInfo #0:\n"
            "    id=8 userId=0 hasTask=true\n"
            f"    baseActivity={{{component}}}\n"
            f"    topActivity={{{component}}}\n"
        )
        self.assertEqual(SMOKE.parse_wayward_recent_task_records(output), [(8, component)])

    def test_two_real_wayward_task_entries_are_not_hidden(self) -> None:
        component = f"{SMOKE.PACKAGE}/com.wayward.launcher.MainActivity"
        output = "Recent tasks:\n" + task(0, 8, component) + task(1, 9, component)
        self.assertEqual(
            SMOKE.parse_wayward_recent_task_records(output),
            [(8, component), (9, component)],
        )

    def test_package_name_in_calling_package_is_not_a_wayward_task(self) -> None:
        component = "com.android.documentsui/.FilesActivity"
        output = "Recent tasks:\n" + task(0, 8, component, SMOKE.PACKAGE)
        self.assertEqual(SMOKE.parse_wayward_recent_task_records(output), [])

    def test_visible_summary_without_canonical_list_is_not_counted(self) -> None:
        component = f"{SMOKE.PACKAGE}/com.wayward.launcher.MainActivity"
        output = "Visible recent tasks:\n" + f"baseActivity={{{component}}}\n"
        self.assertEqual(SMOKE.parse_wayward_recent_task_records(output), [])


if __name__ == "__main__":
    unittest.main()
