"""Checks for the Android device-test runner and diagnostic capture."""

from __future__ import annotations

import pathlib
import subprocess
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[1]
CAPTURE = REPO / ".mise" / "bin" / "capture-android-emulator-diagnostics"
RUNNER = REPO / ".mise" / "bin" / "run-android-device-tests"


class CaptureAndroidEmulatorDiagnosticsTest(unittest.TestCase):
    def test_named_attempt_writes_under_that_subdirectory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env = {
                "MISE_PROJECT_ROOT": tmp,
                "PATH": "/usr/bin:/bin",
            }
            subprocess.run(
                [str(CAPTURE), "attempt-1"],
                check=True,
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            unavailable = (
                pathlib.Path(tmp)
                / "build"
                / "android-emulator"
                / "diagnostics"
                / "attempt-1"
                / "adb-unavailable.txt"
            )
            self.assertTrue(unavailable.is_file(), unavailable)


class RunAndroidDeviceTestsTest(unittest.TestCase):
    def test_session_hang_captures_before_reboot(self) -> None:
        hang = RUNNER.read_text().split("Session install hung", 1)[1]
        capture_at = hang.index("capture-android-emulator-diagnostics")
        self.assertIn("attempt-1", hang[capture_at : capture_at + 80])
        self.assertLess(capture_at, hang.index("stop-android-emulator"))
        self.assertLess(
            hang.index("stop-android-emulator"), hang.index("boot-android-emulator")
        )


if __name__ == "__main__":
    unittest.main()
