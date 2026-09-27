import json
import tempfile
import unittest
from pathlib import Path

from config import canonical_config
from performance import late_frames, read_run

# What the app prints for a run: START carries the full configuration with defaults.
APP_DEFAULTS = {
    "workload": "camera",
    "scene": "points-1000",
    "implementation": "compose-imperative",
    "surface": "surface",
    "maximumFps": None,
    "layers": 1,
    "rateHz": 4.0,
    "durationMs": 12000,
}


def write_run(
    root,
    cpu=100,
    implementation="compose-imperative",
    workload="paint",
    intervals=(),
    build=None,
):
    root.mkdir(parents=True)
    config = json.loads(
        canonical_config({"workload": workload, "implementation": implementation})
    )
    config = json.dumps(APP_DEFAULTS | config, separators=(",", ":"), sort_keys=True)
    operations = 0 if workload == "idle" else max(2, len(intervals))
    work = {
        "operations": operations,
        "duration_ms": 12001,
        "submission_count": operations if not intervals else 0,
        "completion_count": 0,
        "frame_count": len(intervals),
        "close_count": 0,
        "completion_signal": None,
    }
    frames = 0 if workload in {"idle", "recompose"} else 1
    logs = (
        f"MAP_BENCHMARK START {config}\n"
        "MAP_BENCHMARK VIEWPORT [400,800,2]\n"
        'MAP_BENCHMARK STARTUP {"style_ready_ms":120.5,"first_frame_ms":340}\n'
        + (f"MAP_BENCHMARK BUILD {json.dumps(build)}\n" if build else "")
        + f"MAP_BENCHMARK CPU {cpu}\n"
        f'MAP_BENCHMARK FRAMESTATS {{"frames":{frames},"duration_ms":12002}}\n'
        + ('MAP_BENCHMARK FRAMETIMES [{"rendering_ms":1}]\n' if frames else "")
        + (
            "MAP_BENCHMARK SUBMISSIONS [0.1,0.2]\n"
            if operations and not intervals
            else ""
        )
        + (
            f"MAP_BENCHMARK INTERVALS {json.dumps(list(intervals))}\n"
            if intervals
            else ""
        )
        + "MAP_BENCHMARK WORKLOAD "
        + json.dumps(work)
        + "\nMAP_BENCHMARK DONE\n"
    )
    (root / "app.log").write_text(logs)
    return logs


class PerformanceTest(unittest.TestCase):
    def test_build_metadata_comes_from_the_app_log_and_old_logs_still_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root)
            self.assertIsNone(read_run(root)["build"])
            build = {
                "commit": "captured-app-commit",
                "dirty": True,
                "fixtures": "abc123",
                "dependency_versions": {"native_ffi": "test-version"},
            }
            log += "MAP_BENCHMARK BUILD " + json.dumps(build) + "\n"
            (root / "app.log").write_text(log)
            self.assertEqual(read_run(root)["build"], build)
            (root / "app.log").write_text(log + "MAP_BENCHMARK BUILD {}\n")
            with self.assertRaisesRegex(ValueError, "Expected one BUILD record"):
                read_run(root)

    def test_cpu_is_normalized_per_operation_and_per_second(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            write_run(root, cpu=300)
            report = read_run(root)
            self.assertEqual(report["cpu_ms_per_operation"], 150)
            self.assertAlmostEqual(report["cpu_ms_per_second"], 300 / 12.001)
            self.assertEqual(report["startup"]["first_frame_ms"], 340)

    def test_idle_and_native_statistics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root, workload="idle")
            report = read_run(root)
            self.assertEqual(report["cpu_ms"], 100)
            self.assertIsNone(report["cpu_ms_per_operation"])
            self.assertEqual(report["frames"]["frames"], 0)
            self.assertIsNone(report["frames"]["rendering_ms"])
            log = (
                log.replace('"frames":0', '"frames":2')
                + 'MAP_BENCHMARK FRAMETIMES [{"rendering_ms":1},{"rendering_ms":3}]\n'
            )
            (root / "app.log").write_text(log)
            self.assertEqual(read_run(root)["frames"]["rendering_ms"]["p50"], 2)

    def test_lifecycle_reports_require_one_close_and_completion_per_operation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root, workload="runtime-startup")
            log = (
                log.replace('"frames":1', '"frames":0')
                .replace('MAP_BENCHMARK FRAMETIMES [{"rendering_ms":1}]\n', "")
                .replace('"close_count": 0', '"close_count": 2')
            )
            log = log.replace(
                'MAP_BENCHMARK STARTUP {"style_ready_ms":120.5,"first_frame_ms":340}',
                "MAP_BENCHMARK STARTUP null",
            )
            log += (
                "MAP_BENCHMARK CLOSES [1,3]\nMAP_BENCHMARK CLOSE_COMPLETIONS [10,20]\n"
            )
            (root / "app.log").write_text(log)
            result = read_run(root)
            self.assertIsNone(result["startup"])
            self.assertEqual(result["workload"]["close_ms"]["p50"], 2)
            self.assertEqual(result["workload"]["close_completion_ms"]["p50"], 15)
            for invalid in (
                log.replace("[10,20]", "[10]"),
                log.replace('"close_count": 2', '"close_count": 1')
                .replace("[1,3]", "[1]")
                .replace("[10,20]", "[10]"),
            ):
                (root / "app.log").write_text(invalid)
                with self.assertRaises(ValueError):
                    read_run(root)

    def test_frame_intervals_measure_pacing(self):
        self.assertIsNone(late_frames([]))
        self.assertEqual(late_frames([16, 16, 17, 50, 16, 33]), 2)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root, workload="camera", intervals=[16, 16, 17, 50, 16, 33])
            report = read_run(root)["workload"]
            self.assertEqual(report["late_frames"], 2)
            self.assertEqual(report["frame_interval_ms"]["max"], 50)
            (root / "app.log").write_text(
                log.replace('"frame_count": 6', '"frame_count": 5')
            )
            with self.assertRaisesRegex(ValueError, "Incomplete frame intervals"):
                read_run(root)

    def test_failed_and_truncated_runs_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root)
            for invalid in (
                log.replace("MAP_BENCHMARK DONE", "unfinished"),
                log + "MAP_BENCHMARK ERROR failed\n",
                log.replace("[0.1,0.2]", "[0.1]"),
                log.replace("[0.1,0.2]", "[0.1,NaN]"),
                log.replace('"frames":1', '"frames":2'),
                log.replace("MAP_BENCHMARK STARTUP", "MAP_BENCHMARK STARTED"),
            ):
                (root / "app.log").write_text(invalid)
                with self.assertRaises(ValueError):
                    read_run(root)

    def test_ui_frames_are_separate_from_engine_timings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root)
            self.assertIsNone(read_run(root)["ui_frames"])
            log += 'MAP_BENCHMARK UISTATS {"frames":2,"dropped":3}\n'
            log += (
                'MAP_BENCHMARK UIFRAMES [{"total_ms":130,"delay_ms":110,"deadline_ms":16},'
                '{"total_ms":2,"delay_ms":0,"deadline_ms":16}]\n'
            )
            (root / "app.log").write_text(log)
            report = read_run(root)
            self.assertEqual(report["ui_frames"]["missed_deadlines"], 1)
            self.assertEqual(report["ui_frames"]["delay_ms"]["max"], 110)
            self.assertEqual(report["ui_frames"]["dropped"], 3)
            self.assertEqual(report["frames"]["rendering_ms"]["max"], 1)
            (root / "app.log").write_text(log.replace('"frames":2', '"frames":3'))
            with self.assertRaisesRegex(ValueError, "Incomplete UI"):
                read_run(root)

    def test_completion_timings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(root, workload="source-latency")
            log = (
                log.replace('"completion_count": 0', '"completion_count": 2').replace(
                    '"completion_signal": null',
                    '"completion_signal": "rendered-feature-revision"',
                )
                + "MAP_BENCHMARK COMPLETIONS [16,32]\n"
            )
            (root / "app.log").write_text(log)
            self.assertEqual(read_run(root)["workload"]["completion_ms"]["p50"], 24)

    def test_redraw_requires_events_but_recomposition_can_remain_idle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_run(
                root / "recompose",
                workload="recompose",
                implementation="compose-declarative",
            )
            read_run(root / "recompose")
            log = write_run(root / "paint")
            log = log.replace('"frames":1', '"frames":0').replace(
                'MAP_BENCHMARK FRAMETIMES [{"rendering_ms":1}]\n', ""
            )
            (root / "paint/app.log").write_text(log)
            with self.assertRaisesRegex(ValueError, "no render events"):
                read_run(root / "paint")
