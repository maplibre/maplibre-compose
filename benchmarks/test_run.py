import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from run import CLASSIC_PACKAGE, PACKAGE, android_capture, setup, wait_for
from test_performance import write_run


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

    def test_a_crash_in_logcat_ends_the_wait(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "app.log"
            logcat = Path(directory) / "logcat.log"
            log.write_text("MAP_BENCHMARK START {}")
            logcat.write_text("FATAL EXCEPTION: main")
            with self.assertRaisesRegex(RuntimeError, "logcat.log"):
                wait_for(log, crash_log=logcat)


class AndroidCaptureTest(unittest.TestCase):
    def test_android_reads_the_app_log_instead_of_logcat(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(
                root, workload="animation", implementation="classic-android"
            )
            (root / "app.log").write_text("")
            device = ["adb", "-s", "phone"]
            with (
                patch("run.call", return_value=""),
                patch("run.subprocess.Popen"),
                patch("run.stop"),
                patch("run.android_read", return_value=log.encode()),
            ):
                android_capture(
                    device,
                    json.dumps(
                        {"workload": "animation", "implementation": "classic-android"}
                    ),
                    root,
                    "/sdcard/Android/data/pkg/files/benchmark.log",
                )
            self.assertIn("MAP_BENCHMARK DONE", (root / "app.log").read_text())
            self.assertTrue((root / "logcat.log").exists())


class SetupTest(unittest.TestCase):
    def test_android_install_compiles_the_app_before_it_runs(self):
        with (
            patch("run.call", return_value="/sdk"),
            patch("run.subprocess.run", return_value=Mock(returncode=0)) as run,
        ):
            setup("android", '{"implementation":"classic-android"}', "phone")
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands[0][3:5], ["install", "-r"])
        self.assertEqual(
            tuple(commands[1][3:]),
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

    def test_android_replaces_an_app_signed_with_another_key(self):
        with (
            patch("run.call", return_value="/sdk") as call,
            patch(
                "run.subprocess.run",
                side_effect=[
                    Mock(returncode=1),
                    Mock(returncode=0),
                    Mock(returncode=0),
                ],
            ) as run,
        ):
            setup("android", '{"implementation":"compose-imperative"}', "phone")
        self.assertEqual(run.call_args_list[0].args[0][3:5], ["install", "-r"])
        self.assertEqual(run.call_args_list[1].args[0][3:5], ["uninstall", PACKAGE])
        retried = call.call_args_list[1].args
        self.assertEqual(retried[3], "install")

    def test_android_reinstalls_a_package_it_cannot_compile(self):
        with (
            patch("run.call", return_value="/sdk") as call,
            patch("run.time.sleep"),
            patch(
                "run.subprocess.run",
                side_effect=[
                    Mock(returncode=0),
                    Mock(returncode=1),
                    Mock(returncode=1),
                    Mock(returncode=1),
                    Mock(returncode=0),
                ],
            ) as run,
        ):
            setup("android", '{"implementation":"compose-imperative"}', "phone")
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands[-1][3:5], ["uninstall", PACKAGE])
        self.assertEqual(
            [command[3] for command in commands].count("shell"),
            3,
            "three compile attempts before the clean install",
        )
        self.assertEqual(call.call_args_list[-2].args[3], "install")
        self.assertEqual(call.call_args_list[-1].args[3:5], ("shell", "cmd"))
