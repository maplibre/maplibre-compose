import json
import tempfile
import unittest
from pathlib import Path

from analyze import workload_metrics
from compare import compare
from config import canonical_config


class ComparisonTest(unittest.TestCase):
    def group(self, root, name, values, **overrides):
        group = root / name
        for index, value in enumerate(values):
            run = group / str(index)
            run.mkdir(parents=True)
            metadata = dict(
                schema=3,
                platform="android",
                device="phone",
                host="host",
                mode="both",
                config=canonical_config({"workload": "paint"}),
                apk_sha256=name,
                **overrides,
            )
            (run / "metadata.json").write_text(json.dumps(metadata))
            (run / "app.log").write_text(
                f"MAP_BENCHMARK START {metadata['config']} 1.0\n"
                'MAP_BENCHMARK WORKLOAD {"version":2,"operations":96,"duration_ms":12001}\n'
                'MAP_BENCHMARK SCENE {"fixtureSha256":"abc","viewportWidthDp":400,"viewportHeightDp":800,"density":2}\n'
                "MAP_BENCHMARK CLOSED\nMAP_BENCHMARK DONE 0\n"
            )
            (run / "performance.json").write_text(json.dumps({"cpu_ms": value}))
        return group

    def test_run_medians_and_environment_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = self.group(root, "before", [100, 200, 900])
            after = self.group(root, "after", [90, 180, 800])
            result = compare(before, after)
            self.assertEqual(
                result["metrics"]["cpu_ms"]["baseline"],
                {"median": 200, "min": 100, "max": 900},
            )
            self.assertAlmostEqual(result["metrics"]["cpu_ms"]["change_percent"], -10)
            self.assertNotIn("rendering_p95_ms", result["metrics"])
            path = after / "0" / "metadata.json"
            metadata = json.loads(path.read_text())
            metadata["density"] = "different"
            path.write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, "density"):
                compare(before, after)

    def test_implementation_comparison_is_explicit_and_preserves_other_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = self.group(root, "before", [100, 100, 100])
            after = self.group(root, "after", [100, 100, 100])
            for path in after.glob("*/metadata.json"):
                metadata = json.loads(path.read_text())
                old = metadata["config"]
                metadata["config"] = canonical_config(
                    {"workload": "paint", "implementation": "compose-declarative"}
                )
                path.write_text(json.dumps(metadata))
                log = path.with_name("app.log")
                log.write_text(log.read_text().replace(old, metadata["config"]))
            with self.assertRaisesRegex(ValueError, "configurations"):
                compare(before, after)
            self.assertEqual(
                compare(before, after, True)["metrics"]["cpu_ms"]["change_percent"], 0
            )
            path = after / "0" / "app.log"
            path.write_text(
                path.read_text().replace(
                    '"fixtureSha256":"abc"', '"fixtureSha256":"different"'
                )
            )
            with self.assertRaisesRegex(ValueError, "fixture"):
                compare(before, after, True)

    def test_completion_signal_and_batched_timings_are_verified(self):
        prefix = f"MAP_BENCHMARK START {canonical_config({'workload': 'source-latency'})} 1.0\n"
        report = {
            "version": 2,
            "operations": 2,
            "duration_ms": 12001,
            "submission_count": 2,
            "completion_count": 2,
            "completion_signal": "rendered-feature-revision",
        }
        logs = (
            prefix
            + "MAP_BENCHMARK SUBMISSIONS [0.1,0.2]\n"
            + "MAP_BENCHMARK COMPLETIONS [16,33]\n"
            + "MAP_BENCHMARK WORKLOAD "
            + json.dumps(report)
        )
        self.assertEqual(workload_metrics(logs)["completion_ms"], [16, 33])
        for invalid in (
            logs.replace("[16,33]", "[16]"),
            logs.replace("rendered-feature-revision", "style-ready"),
            logs.replace("[0.1,0.2]", "[0.1,NaN]"),
        ):
            with self.assertRaises(ValueError):
                workload_metrics(invalid)

    def test_missing_work_and_overrun_are_rejected(self):
        prefix = f"MAP_BENCHMARK START {canonical_config({'workload': 'paint'})} 1.0\n"
        for report in (
            {"version": 2, "operations": 0, "duration_ms": 12000},
            {"version": 2, "operations": 100, "duration_ms": 23000},
        ):
            with self.assertRaises(ValueError):
                workload_metrics(
                    prefix + "MAP_BENCHMARK WORKLOAD " + json.dumps(report),
                    required=True,
                )
        with self.assertRaisesRegex(ValueError, "rebuild"):
            workload_metrics(prefix, required=True)
