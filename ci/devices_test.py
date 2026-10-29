"""Device adapters preserve worktree ownership and explicit selection."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class DevicesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / ".mise/bin"
        self.bin.mkdir(parents=True)
        for name in (
            "boot-android-emulator",
            "android-sdk-root",
            "boot-ios-simulator",
            "run-android-demo",
        ):
            shutil.copy(ROOT / ".mise/bin" / name, self.bin / name)
        self.sdk = self.root / "sdk"
        (self.sdk / "emulator").mkdir(parents=True)
        (self.sdk / "emulator/source.properties").write_text("Pkg.Revision=37.3.6\n")
        self.calls = self.root / "calls"
        cli = self.root / "devices-cli"
        cli.write_text('#!/bin/sh\nprintf "%s\\n" "$@" >"$CALLS"\necho emulator-5562\n')
        cli.chmod(0o755)
        self.env = dict(
            os.environ,
            MISE_PROJECT_ROOT=str(self.root),
            ANDROID_HOME=str(self.sdk),
            ANDROID_SERIAL="",
            PATH=f"{self.root}:{os.environ['PATH']}",
            CALLS=str(self.calls),
        )

    def run_script(self, name, *args):
        return subprocess.run(
            [str(self.bin / name), *args],
            env=self.env,
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()

    def test_phone_headless_and_recorded_serial(self):
        self.assertEqual(
            self.run_script("boot-android-emulator", "26"), "emulator-5562"
        )
        args = self.calls.read_text().splitlines()
        self.assertEqual(
            args[:6], ["get", "android", "--owner", str(self.root), "--api", "26"]
        )
        self.assertNotIn("--headful", args)
        self.assertIn("swiftshader", args)
        self.assertEqual(
            (self.root / "build/android-emulator/serial").read_text(), "emulator-5562\n"
        )

    def test_wear_and_tv_profiles_with_windows(self):
        for flag, api, variant, profile in (
            ("--wear", "36.1", "android-wear-signed", "wearos_small_round"),
            ("--tv", "36", "android-tv", "tv_1080p"),
        ):
            with self.subTest(flag=flag):
                self.run_script("boot-android-emulator", api, flag, "--headful")
                args = self.calls.read_text().splitlines()
                self.assertEqual(args[args.index("--variant") + 1], variant)
                self.assertEqual(args[args.index("--device-profile") + 1], profile)
                self.assertIn("--headful", args)
                self.assertIn("host", args)

    def test_local_properties_precedes_environment(self):
        local_sdk = self.root / "local-sdk"
        (local_sdk / "emulator").mkdir(parents=True)
        (local_sdk / "emulator/source.properties").write_text("Pkg.Revision=37.3.6\n")
        (self.root / "local.properties").write_text(f"sdk.dir={local_sdk}\n")
        self.assertEqual(self.run_script("android-sdk-root"), str(local_sdk))

    def test_explicit_serial_does_not_acquire_or_replace_device(self):
        picker = self.bin / "pick-android-device"
        picker.write_text('#!/bin/sh\nprintf "%s\\n" "$1"\n')
        picker.chmod(0o755)
        self.env["ANDROID_SERIAL"] = "physical-phone"
        self.assertEqual(
            self.run_script("boot-android-emulator", "36"), "physical-phone"
        )
        self.assertFalse(self.calls.exists())

    def test_ios_floor_runtime_and_device_type(self):
        self.env["usage_device_type"] = "iPhone SE (3rd generation)"
        self.run_script("boot-ios-simulator", "15.5")
        self.assertEqual(
            self.calls.read_text().splitlines(),
            [
                "get",
                "ios",
                "--owner",
                str(self.root),
                "--runtime",
                "15.5",
                "--device-type",
                "iPhone SE (3rd generation)",
            ],
        )

    def test_demo_launches_selected_serial_with_and_without_extras(self):
        adb = self.sdk / "platform-tools/adb"
        adb.parent.mkdir()
        adb.write_text('#!/bin/sh\nprintf "%s\\n" "$@" >"$CALLS.launch"\n')
        adb.chmod(0o755)
        gradle = self.root / "gradlew"
        gradle.write_text("#!/bin/sh\nexit 0\n")
        gradle.chmod(0o755)
        for route in ("", "demo/live-tracking"):
            self.env["usage_route"] = route
            self.run_script("run-android-demo")
            args = Path(f"{self.calls}.launch").read_text().splitlines()
            self.assertEqual(args[:2], ["-s", "emulator-5562"])
            self.assertEqual(
                args[-3:] if route else args[-1:],
                ["--es", "route", route]
                if route
                else ["org.maplibre.compose.demoapp/.MainActivity"],
            )

        picker = self.bin / "pick-android-device"
        picker.write_text('#!/bin/sh\nprintf "%s\\n" "${1:-$ANDROID_SERIAL}"\n')
        picker.chmod(0o755)
        self.run_script("run-android-demo", "physical-phone")
        self.assertEqual(
            (self.root / "build/android-emulator/serial").read_text(),
            "physical-phone\n",
        )
        self.env["ANDROID_SERIAL"] = "env-phone"
        self.run_script("run-android-demo")
        self.assertEqual(
            (self.root / "build/android-emulator/serial").read_text(), "env-phone\n"
        )
