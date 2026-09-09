import json
import struct
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np
from analyze import analyze, input_response, screenrecord_timestamps
from run import validate_workload


class CaptureClockTest(unittest.TestCase):
    def test_screenrecord_v2_and_unknown_version(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.mp4"
            data = b"#VV1NSC0PET1ME2#" + struct.pack("<IqI3Q", 2, 900, 3, 10, 20, 30)
            path.write_bytes(b"prefix" + data + b"suffix")
            self.assertEqual(screenrecord_timestamps(path).tolist(), [10, 20, 30])
            path.write_bytes(data[:-1])
            with self.assertRaisesRegex(ValueError, "Truncated"):
                screenrecord_timestamps(path)
            path.write_bytes(
                data.replace(struct.pack("<I", 2), struct.pack("<I", 1), 1)
            )
            self.assertIsNone(screenrecord_timestamps(path))

    def test_android_clock_must_be_strictly_increasing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.mp4"
            path.write_bytes(
                b"#VV1NSC0PET1ME2#" + struct.pack("<IqI3Q", 2, 0, 3, 10, 10, 30)
            )
            with self.assertRaisesRegex(ValueError, "Non-monotonic"):
                screenrecord_timestamps(path)

    def test_missing_clock_is_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.mp4"
            path.write_bytes(b"old recording")
            self.assertIsNone(screenrecord_timestamps(path))


class VisibleResponseTest(unittest.TestCase):
    def steps(self, map_delay, overlay_delay):
        times = np.arange(0, 8000, 1000 / 60) * 1e6
        events = [(i, (i * 1000 + 123) * 1000000) for i in range(1, 7)]
        data = np.zeros((len(times), 5))
        data[:, 0] = times
        for column, delay in ((1, map_delay), (3, overlay_delay)):
            data[:, column] = 200
            for sequence, timestamp in events:
                data[times >= timestamp + delay * 1e6, column] = (
                    100 if sequence % 2 else 200
                )
        return data, events

    def test_known_map_and_overlay_latencies_are_bounded(self):
        for map_delay, overlay_delay in ((0, 0), (50, 20), (20, 70), (150, 10)):
            data, events = self.steps(map_delay, overlay_delay)
            result = input_response(data, events)
            json.dumps(result)
            if map_delay == overlay_delay:
                self.assertEqual(result["responses_in_different_capture_frames"], 0)
            elif abs(map_delay - overlay_delay) > 20:
                self.assertEqual(result["responses_in_different_capture_frames"], 6)
            for name, delay in (("map", map_delay), ("overlay", overlay_delay)):
                self.assertEqual(result[name]["samples"], 6)
                for lower, upper in result[name]["bounds_ms"]:
                    self.assertLessEqual(lower, delay)
                    self.assertGreaterEqual(upper, delay)
                    self.assertLessEqual(upper - lower, 19)

    def test_sparse_capture_ends_on_last_response(self):
        data, events = self.steps(50, 20)
        changes = np.flatnonzero(np.any(np.diff(data[:, (1, 3)], axis=0), axis=1)) + 1
        sparse = data[np.r_[0, changes]]
        result = input_response(sparse, events)
        for name, delay in (("map", 50), ("overlay", 20)):
            for lower, upper in result[name]["bounds_ms"]:
                self.assertLessEqual(lower, delay)
                self.assertGreaterEqual(upper, delay)

    def test_rejects_clock_error_and_missing_response(self):
        data, events = self.steps(30, 10)
        with self.assertRaisesRegex(ValueError, "preceded"):
            input_response(data, [(i, t + 100000000) for i, t in events])
        data[:, 1] = 200
        with self.assertRaises(ValueError):
            input_response(data, events)


class PixelMeasurementTest(unittest.TestCase):
    def recording(self, path, offset=0, frames=730, moving=True):
        (path / "metadata.json").write_text(
            json.dumps(
                {
                    "config": "setters,surface,default,0",
                    "platform": "desktop",
                    "video": "screen.avi",
                    "mode": "visual",
                    "device": "test-device",
                    "host": "test-host",
                    "app_sha256": "test-artifact",
                }
            )
        )
        (path / "app.log").write_text(
            "MAP_BENCHMARK START setters,surface,default,0 1.0\nMAP_BENCHMARK CLOSED\nMAP_BENCHMARK DONE 0\n"
        )
        writer = cv2.VideoWriter(
            str(path / "screen.avi"), cv2.VideoWriter_fourcc(*"MJPG"), 60, (240, 160)
        )
        self.assertTrue(writer.isOpened())
        for frame in range(frames):
            image = np.full((160, 240, 3), 32, dtype=np.uint8)
            x = int(120 + 45 * np.sin(frame / 30)) if moving else 120
            cv2.rectangle(image, (12, 12), (28, 28), (0, 255, 0), -1)
            # Startup can contain green UI. Only the two-color measurement pattern qualifies.
            if frame >= 20:
                cv2.rectangle(image, (32, 12), (48, 28), (255, 0, 255), -1)
            cv2.circle(image, (x, 80), 6, (0, 0, 255), -1)
            cv2.circle(image, (x + offset, 80), 20, (255, 255, 0), 3)
            writer.write(image)
        writer.release()

    def test_pixels_detect_zero_and_ten_pixel_separation(self):
        for offset in (0, 10):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                self.recording(path, offset=offset)
                result = analyze(path)
                self.assertEqual(result["samples"], 710)
                self.assertAlmostEqual(
                    result["separation_px"]["p95"], offset, delta=0.5
                )

    def test_rejects_truncation_and_stationary_map(self):
        for options in ({"frames": 400}, {"moving": False}):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                self.recording(path, **options)
                with self.assertRaises(ValueError):
                    analyze(path)

    def test_performance_requires_matching_raw_visual_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            reference = Path(directory) / "reference"
            output = Path(directory) / "performance"
            reference.mkdir()
            output.mkdir()
            self.recording(reference)
            metadata = json.loads((reference / "metadata.json").read_text())
            metadata.update(mode="performance")
            metadata.pop("video")
            (output / "metadata.json").write_text(json.dumps(metadata))
            (output / "app.log").write_text((reference / "app.log").read_text())
            with self.assertRaisesRegex(ValueError, "--visual-reference"):
                validate_workload(output)
            self.assertEqual(
                validate_workload(output, reference), str(reference.resolve())
            )
            for key in ("app_sha256", "device", "host", "config"):
                changed = dict(metadata, **{key: "different"})
                (output / "metadata.json").write_text(json.dumps(changed))
                with self.assertRaises(ValueError):
                    validate_workload(output, reference)
            (output / "metadata.json").write_text(json.dumps(metadata))
            # A passing cached report cannot conceal a broken workload in the raw capture.
            self.recording(reference, moving=False)
            with self.assertRaises(ValueError):
                validate_workload(output, reference)


if __name__ == "__main__":
    unittest.main()
