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
