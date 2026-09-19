import json
import tempfile
import unittest
from pathlib import Path

from config import canonical_config
from performance import read_run


def write_run(
    root,
    cpu=100,
    implementation="compose-imperative",
    workload="paint",
    artifact="build",
):
    root.mkdir(parents=True)
    config = canonical_config({"workload": workload, "implementation": implementation})
    (root / "metadata.json").write_text(
        json.dumps(
            {
                "platform": "android",
                "device": "phone",
                "artifact": artifact,
                "config": config,
            }
        )
    )
    operations = 0 if workload == "idle" else 2
    work = {
        "operations": operations,
        "duration_ms": 12001,
        "submission_count": operations,
        "completion_count": 0,
        "completion_signal": None,
    }
    logs = (
        f"MAP_BENCHMARK START {config}\n"
        "MAP_BENCHMARK VIEWPORT [400,800,2]\n"
        f"MAP_BENCHMARK CPU {cpu}\n"
        'MAP_BENCHMARK FRAMESTATS {"frames":0,"duration_ms":12002}\n'
        + ("MAP_BENCHMARK SUBMISSIONS [0.1,0.2]\n" if operations else "")
        + "MAP_BENCHMARK WORKLOAD "
        + json.dumps(work)
        + "\nMAP_BENCHMARK DONE\n"
    )
    (root / "app.log").write_text(logs)
    return logs


class PerformanceTest(unittest.TestCase):
    def test_idle_and_native_statistics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root, workload="idle")
            _, report = read_run(root)
            self.assertEqual(report["cpu_ms"], 100)
            self.assertEqual(report["frames"]["frames"], 0)
            self.assertIsNone(report["frames"]["rendering_ms"])
            log = (
                log.replace('"frames":0', '"frames":2')
                + 'MAP_BENCHMARK FRAMETIMES [{"rendering_ms":1},{"rendering_ms":3}]\n'
            )
            (root / "app.log").write_text(log)
            self.assertEqual(read_run(root)[1]["frames"]["rendering_ms"]["p50"], 2)

    def test_failed_mismatched_and_truncated_runs_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root)
            for invalid in (
                log.replace("MAP_BENCHMARK DONE", "unfinished"),
                log + "MAP_BENCHMARK ERROR failed\n",
                log.replace("[0.1,0.2]", "[0.1]"),
                log.replace("[0.1,0.2]", "[0.1,NaN]"),
                log.replace('"frames":0', '"frames":1'),
                log.replace('"duration_ms": 12001', '"duration_ms": 100'),
                log.replace('"workload":"paint"', '"workload":"source"'),
            ):
                (root / "app.log").write_text(invalid)
                with self.assertRaises(ValueError):
                    read_run(root)

    def test_completion_requires_the_expected_signal_and_all_operations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root, workload="source-latency")
            with self.assertRaisesRegex(ValueError, "completion"):
                read_run(root)
            log = (
                log.replace('"completion_count": 0', '"completion_count": 2').replace(
                    '"completion_signal": null',
                    '"completion_signal": "rendered-feature-revision"',
                )
                + "MAP_BENCHMARK COMPLETIONS [16,32]\n"
            )
            (root / "app.log").write_text(log)
            self.assertEqual(read_run(root)[1]["workload"]["completion_ms"]["p50"], 24)
