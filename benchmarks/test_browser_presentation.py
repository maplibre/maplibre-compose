import json
import tempfile
import unittest
from pathlib import Path

from browser_presentation import capture
from performance import presentation_metrics


class BrowserPresentationTest(unittest.TestCase):
    def test_canvas_presentations_exclude_raf_other_pages_and_late_end_callback(self):
        events = [
            {"name": "MAP_BENCHMARK_APP_PRESENTATION_START", "ts": 1_000_000, "pid": 1},
            {"name": "MAP_BENCHMARK_APP_PRESENTATION_END", "ts": 7_000_000, "pid": 1},
            {
                "name": "FrameSequenceTrackerV3",
                "ts": 900_000,
                "pid": 1,
                "ph": "b",
                "id2": {"local": "0x1"},
                "args": {"name": "CanvasAnimation"},
            },
            {
                "name": "FrameSequenceTrackerV3",
                "ts": 900_000,
                "pid": 1,
                "ph": "b",
                "id2": {"local": "0x2"},
                "args": {"name": "RAF"},
            },
            {
                "name": "Frame ",
                "ts": 1_000_000,
                "pid": 1,
                "ph": "e",
                "id2": {"local": "0x1"},
            },
            {
                "name": "Frame",
                "ts": 1_500_000,
                "pid": 1,
                "ph": "e",
                "id2": {"local": "0x1"},
            },
            {
                "name": "Frame   ",
                "ts": 3_500_000,
                "pid": 1,
                "ph": "e",
                "id2": {"local": "0x1"},
            },
            {
                "name": "Frame",
                "ts": 4_000_000,
                "pid": 1,
                "ph": "e",
                "id2": {"local": "0x1"},
            },
            {
                "name": "Frame",
                "ts": 1_100_000,
                "pid": 1,
                "ph": "e",
                "id2": {"local": "0x2"},
            },
            {
                "name": "Frame",
                "ts": 1_250_000,
                "pid": 2,
                "ph": "e",
                "id2": {"local": "0x1"},
            },
            {
                "name": "Scheduler::BeginFrame",
                "ts": 1_000_001,
                "pid": 1,
                "args": {"args": {"interval_us": 8333}},
            },
        ]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "browser-trace.json"
            path.write_text(
                json.dumps({"traceEvents": events, "dataLossOccurred": False})
            )
            frames = capture(folder, {"durationMs": 3000})
            result = presentation_metrics(frames, frames["window"], {})
            self.assertEqual(result["frames"], 3)
            self.assertEqual(result["duration_ms"], 3000)
            self.assertEqual(result["fps"], 1)
            self.assertEqual(result["interval_ms"]["max"], 2000)
            self.assertAlmostEqual(result["refresh_hz"], 120, places=2)
            path.write_text(
                json.dumps({"traceEvents": events, "dataLossOccurred": True})
            )
            with self.assertRaisesRegex(ValueError, "lost events"):
                capture(folder, {"durationMs": 3000})

    def test_unrecorded_runs_have_no_canvas_metrics(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertIsNone(capture(folder, {}))
