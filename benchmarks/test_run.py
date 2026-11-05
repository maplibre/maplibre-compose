import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from run import CLASSIC_PACKAGE, setup, wait_for


class CaptureTest(unittest.TestCase):
    def test_console_exit_reports_launch_failure_without_waiting_for_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "app.log"
            process = Mock()
            process.poll.return_value = 1
            log.write_text("Unable to launch because the device is locked")
            with self.assertRaisesRegex(RuntimeError, "App exited"):
                wait_for(log, process)
            # A normally completed app can exit before the runner polls its log.
            process.poll.return_value = 0
            log.write_text("MAP_BENCHMARK DONE")
            wait_for(log, process)


class SetupTest(unittest.TestCase):
    def test_android_install_compiles_the_app_before_it_runs(self):
        with patch("run.call", return_value="/sdk") as call:
            setup("android", '{"implementation":"classic-android"}', "phone")
        commands = [args[3:] for args, _ in call.call_args_list[1:]]
        self.assertEqual(commands[0][0], "install")
        self.assertEqual(
            commands[1],
            (
                "shell",
                "cmd",
                "package",
                "compile",
                "-m",
                "speed",
                "-f",
                CLASSIC_PACKAGE,
            ),
        )
