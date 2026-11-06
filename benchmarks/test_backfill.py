import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from backfill import capture, host, measure_one, record_output
from test_performance import write_run


class MeasureTest(unittest.TestCase):
    def test_a_run_that_never_finishes_is_measured_again(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "001"
            device = Mock(serial="phone")
            device.thermal.return_value = 0
            report = {"cpu_ms": 1}
            with (
                patch("backfill.WORK", Path(directory)),
                patch(
                    "backfill.capture",
                    side_effect=[TimeoutError("Benchmark did not finish"), report],
                ),
            ):
                self.assertEqual(measure_one(device, {}, output, "/app.log"), report)
            self.assertTrue((output / "accepted.json").exists())
            self.assertEqual(len(list(output.parent.glob("001-rejected-*"))), 1)


class RecordOutputTest(unittest.TestCase):
    def test_record_output_calls_the_harness_log_once(self):
        activities = (
            "demo-app/android/src/main/kotlin/org/maplibre/compose/demoapp/MainActivity.kt",
            "benchmarks/android/src/main/kotlin/org/maplibre/compose/benchmark/classic/MainActivity.kt",
        )
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            for name in activities:
                path = checkout / name
                path.parent.mkdir(parents=True)
                path.write_text(
                    "class MainActivity {\n"
                    "  override fun onCreate(savedInstanceState: Bundle?) {\n"
                    "    super.onCreate(savedInstanceState)\n"
                    "  }\n"
                    "}\n"
                )
            record_output(checkout)
            record_output(checkout)
            for name in activities:
                text = (checkout / name).read_text()
                self.assertEqual(text.count("recordBenchmarkOutput(this)"), 1, name)


class HostTest(unittest.TestCase):
    def test_archived_animation_reads_the_durable_app_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(
                root, workload="animation", implementation="classic-android"
            )
            (root / "app.log").write_text("")
            device = Mock(adb=["adb", "-s", "phone"])
            with (
                patch("backfill.runner.call", return_value=""),
                patch("backfill.runner.subprocess.Popen"),
                patch("backfill.runner.stop"),
                patch("backfill.runner.android_read", return_value=log.encode()),
            ):
                report = capture(
                    device,
                    json.dumps(
                        {"workload": "animation", "implementation": "classic-android"}
                    ),
                    root,
                    "/app.log",
                )
            self.assertIn("MAP_BENCHMARK DONE", (root / "app.log").read_text())
            self.assertAlmostEqual(report["map_drawing"]["fps"], 1 / 12.002)

    def test_harness_compiles_without_the_demo_app(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            (checkout / "demo-app/android").mkdir(parents=True)
            host(checkout)
            sources = (
                checkout
                / "demo-app/android/src/main/kotlin/org/maplibre/compose/demoapp"
            )
            harness = list((sources / "benchmark").glob("*.kt"))
            self.assertTrue(harness)
            for path in harness:
                text = path.read_text()
                self.assertNotRegex(text, r"\b(expect|actual) ", path.name)
                # The host provides only the harness and the generated resource accessor.
                for name in re.findall(
                    r"^import org\.maplibre\.compose\.demoapp\.(\S+)",
                    text,
                    re.MULTILINE,
                ):
                    self.assertRegex(name, r"^(benchmark\.|generated\.Res$)", path.name)
