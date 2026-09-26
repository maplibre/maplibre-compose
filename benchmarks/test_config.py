import shlex
import unittest

from config import CASES, WORKLOADS, canonical_config, parse_config
from run import android_launch_args


class ConfigurationTest(unittest.TestCase):
    def test_presets_and_supported_implementations(self):
        for case in CASES.values():
            self.assertEqual(parse_config(case), parse_config(canonical_config(case)))
        for workload, implementations in WORKLOADS.items():
            for implementation in (
                "compose-imperative",
                "compose-declarative",
                "classic-android",
                "classic-ios",
            ):
                config = {"workload": workload, "implementation": implementation}
                if implementation in implementations:
                    parse_config(config)
                else:
                    with self.assertRaises(ValueError):
                        parse_config(config)

    def test_invalid_workloads_fail_before_launch(self):
        for config in (
            {"rateHz": float("nan")},
            {"durationMs": 0},
            {"layers": 0},
            {"workload": "paint", "scene": "minimal"},
            {"workload": "source-latency", "scene": "route-2000"},
            {"unknown": 1},
        ):
            with self.assertRaises(ValueError):
                parse_config(config)

    def test_android_json_survives_the_remote_shell(self):
        config = canonical_config({})
        self.assertEqual(
            shlex.split(android_launch_args(["adb"], config)[-1]), [config]
        )


class AppRoutingTest(unittest.TestCase):
    def test_each_sdk_launches_its_own_app(self):
        from run import CLASSIC_PACKAGE, PACKAGE, android_apk, ios_app

        for implementation, expected in (
            ("compose-imperative", PACKAGE),
            ("compose-declarative", PACKAGE),
            ("classic-android", CLASSIC_PACKAGE),
        ):
            config = canonical_config(
                {"workload": "paint", "implementation": implementation}
            )
            command = android_launch_args(["adb", "-s", "device"], config)
            self.assertEqual(
                command[command.index("-n") + 1], expected + "/.MainActivity"
            )
            self.assertEqual(
                android_apk(config).startswith("benchmarks/"),
                implementation == "classic-android",
            )
        for implementation in ("compose-imperative", "classic-ios"):
            config = canonical_config({"implementation": implementation})
            self.assertIn("Release-iphoneos", ios_app(config, False))
            self.assertIn("Release-iphonesimulator", ios_app(config, True))
            self.assertEqual(
                ios_app(config, True).startswith("benchmarks/"),
                implementation == "classic-ios",
            )

    def test_classic_implementations_require_their_platform(self):
        from run import validate_platform

        for implementation, platform in (
            ("classic-android", "android"),
            ("classic-ios", "ios"),
        ):
            config = canonical_config({"implementation": implementation})
            validate_platform(platform, config)
            for other in {"android", "ios", "desktop", "web"} - {platform}:
                with self.assertRaises(ValueError):
                    validate_platform(other, config)

    def test_iphone_launch_passes_configuration_and_captures_console(self):
        import json

        from run import CLASSIC_PACKAGE, ios_launch_args

        config = canonical_config({"implementation": "classic-ios"})
        command = ios_launch_args("phone", config, False)
        self.assertIn("--terminate-existing", command)
        self.assertIn("--console", command)
        self.assertEqual(command[-1], CLASSIC_PACKAGE)
        environment = json.loads(command[command.index("--environment-variables") + 1])
        self.assertEqual(environment, {"MAP_BENCHMARK": config})
