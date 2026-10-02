import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from backfill import capture, host
from test_performance import write_run


class HostTest(unittest.TestCase):
    def test_archived_animation_uses_file_output_for_capture_and_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"
            log = write_run(
                root, workload="animation", implementation="classic-android"
            )
            log += 'MAP_BENCHMARK PRESENTATION_WINDOW {"start_ns":1000000000,"end_ns":1033333334}\n'
            (root / "app.log").write_text("")
            device = Mock(adb=["adb", "-s", "phone"])
            device.bytes.return_value = log.encode()
            collector = Mock()

            def save(output):
                (output / "presentation.json").write_text(
                    json.dumps(
                        {
                            "source": "surfaceflinger",
                            "layer": "map",
                            "gaps_ns": [],
                            "refresh_periods_ns": [[1_016_666_667, 16_666_667]],
                            "presented_ns": [1_000_000_000, 1_016_666_667],
                        }
                    )
                )

            collector.save.side_effect = save
            with (
                patch("backfill.runner.MapPresentation", return_value=collector),
                patch("backfill.subprocess.Popen"),
                patch("backfill.runner.stop"),
            ):
                report = capture(
                    device,
                    json.dumps(
                        {"workload": "animation", "implementation": "classic-android"}
                    ),
                    root,
                    "/app.log",
                )
            collector.poll_log.assert_called_once_with(root / "app.log")
            self.assertAlmostEqual(report["map_presentation"]["fps"], 60, places=5)

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
