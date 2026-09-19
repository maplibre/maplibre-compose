import json
import shlex
import struct
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import cv2
import numpy as np
from analyze import (
    SCENARIO_DEFAULT_PARAMS,
    SCENARIO_PROFILES,
    analyze,
    config_matches,
    configs_equal,
    input_response,
    measurement_gate,
    screenrecord_timestamps,
)
from run import (
    SCENARIOS,
    android,
    android_launch_args,
    canonical_config,
    desktop_artifact_hash,
    parse_config,
    validate_workload,
    web,
)


class RunnerConfigurationTest(unittest.TestCase):
    def test_four_and_five_field_config_forms(self):
        self.assertEqual(
            parse_config("animation,surface,default,0"),
            ("animation", "surface", "default", "0", None),
        )
        scenario, surface, fps, load, params = parse_config(
            'style-complex,texture,60,5000,{"layers": 12, "features": 3000}'
        )
        self.assertEqual(scenario, "style-complex")
        self.assertEqual(surface, "texture")
        self.assertEqual(fps, "60")
        self.assertEqual(load, "5000")
        self.assertEqual(params, {"layers": 12, "features": 3000})
        self.assertEqual(set(SCENARIO_PROFILES), set(SCENARIOS))
        self.assertEqual(set(SCENARIO_DEFAULT_PARAMS), set(SCENARIOS))
        self.assertEqual(
            set(SCENARIO_DEFAULT_PARAMS["style-swap"]),
            {"intervalMs", "count", "layers", "features"},
        )

    def test_rejects_unknown_scenarios_and_bad_params(self):
        for value in (
            "unknown,surface,default,0",
            "animation,surface,default,0,not-json",
            "animation,surface,default,0,[1,2]",
            "animation,surface,300,0",
            "animation,surface,default,10001",
            "animation,surface,default,0,{}extra",
            'geojson-update,surface,default,0,{"rateHz":NaN}',
            'geojson-update,surface,default,0,{"features":Infinity}',
            'geojson-update,surface,default,0,{"rateHz":1e999}',
        ):
            with self.assertRaises(ValueError, msg=value):
                parse_config(value)

    def test_canonical_config_is_compact_and_sorted(self):
        self.assertEqual(
            canonical_config("animation,surface,default,0"),
            "animation,surface,default,0",
        )
        self.assertEqual(
            canonical_config('style-swap,surface,60,0,{"count": 8, "intervalMs": 900}'),
            'style-swap,surface,60,0,{"count":8,"intervalMs":900}',
        )

    def test_start_log_params_match_semantically(self):
        self.assertTrue(
            config_matches(
                "animation,surface,default,0", "animation,surface,default,0,{}"
            )
        )
        self.assertTrue(
            config_matches(
                'style-swap,surface,60,0,{"intervalMs":1500}',
                'style-swap,surface,60,0,{"intervalMs":1500}',
            )
        )
        self.assertTrue(
            config_matches(
                'style-complex,surface,default,0,{"sources":2}',
                'style-complex,surface,default,0,{"features":2000,"layers":8,"sources":2}',
            )
        )
        self.assertFalse(
            config_matches(
                'style-complex,surface,default,0,{"sources":3}',
                'style-complex,surface,default,0,{"features":2000,"layers":8,"sources":2}',
            )
        )
        self.assertFalse(
            config_matches(
                'style-swap,surface,60,0,{"intervalMs":900}',
                'style-swap,surface,60,0,{"intervalMs":1500}',
            )
        )
        self.assertFalse(
            config_matches("setters,surface,60,0", "animation,surface,60,0,{}")
        )
        # A default request must still verify the logged effective parameters.
        self.assertFalse(
            config_matches(
                "style-swap,surface,60,0",
                'style-swap,surface,60,0,{"count":8,"features":1500,"intervalMs":900,"layers":6}',
            )
        )
        self.assertTrue(
            config_matches(
                'style-swap,surface,60,0,{"intervalMs":1500}',
                "style-swap,surface,60,0,{}",
            )
        )

    def test_requested_configs_compare_semantically(self):
        self.assertTrue(
            configs_equal(
                "animation,surface,default,0", "animation,surface,default,0,{}"
            )
        )
        self.assertTrue(
            configs_equal(
                'style-swap,surface,60,0,{"intervalMs":1500}',
                'style-swap,surface,60,0,{"intervalMs":1500.0}',
            )
        )
        self.assertFalse(
            configs_equal("animation,surface,default,0", "animation,surface,60,0,{}")
        )
        self.assertFalse(
            configs_equal(
                'style-swap,surface,60,0,{"intervalMs":900}',
                "style-swap,surface,60,0,{}",
            )
        )

    def test_external_url_does_not_need_local_assets(self):
        args = SimpleNamespace(
            mode="visual",
            config="animation,surface,60,0",
            playwright="playwright",
            url="https://example.test/demo",
        )
        metadata = {}
        with (
            patch("run.shutil.copytree", side_effect=FileNotFoundError),
            patch("run.call") as call,
        ):
            web(args, Path("capture"), metadata)
        self.assertIn(args.url, call.call_args.args)
        self.assertIsNone(metadata["assets_sha256"])

    def test_android_rejects_nonstandard_animation_scale_before_install(self):
        args = SimpleNamespace(device="test-device", mode="visual")
        for scale in ("0", "0.5", "2"):
            with patch("run.call", side_effect=["/sdk", scale]) as call:
                with self.assertRaisesRegex(ValueError, "animator duration scale"):
                    android(args, Path("capture"), {})
                self.assertEqual(call.call_count, 2)

    def test_android_launch_quotes_params_for_the_device_shell(self):
        config = 'style-complex,surface,default,0,{"label":"a b"}'
        args = android_launch_args(["adb"], config)
        self.assertEqual(args[args.index("--es") + 2], shlex.quote(config))
        # An unquoted space would split into two remote-shell words.
        self.assertEqual(shlex.split(args[-1]), [config])


class ArtifactHashTest(unittest.TestCase):
    def test_custom_executables_have_independent_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline, candidate = root / "baseline/app", root / "candidate/app"
            for executable in (baseline, candidate):
                executable.parent.mkdir()
                executable.write_text(executable.parent.name)
            before = desktop_artifact_hash(baseline)
            self.assertNotEqual(before, desktop_artifact_hash(candidate))
            candidate.write_text("changed")
            self.assertEqual(before, desktop_artifact_hash(baseline))

    def test_app_bundle_hash_includes_runtime_files(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "Demo.app"
            executable = bundle / "Contents/MacOS/app"
            executable.parent.mkdir(parents=True)
            executable.write_text("launcher")
            runtime = bundle / "Contents/runtime.jar"
            runtime.write_text("baseline")
            before = desktop_artifact_hash(executable)
            runtime.write_text("candidate")
            self.assertNotEqual(before, desktop_artifact_hash(executable))


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


class MeasurementGateTest(unittest.TestCase):
    def test_stray_matched_pixels_do_not_break_the_gate(self):
        hsv = np.zeros((160, 240, 3), dtype=np.uint8)
        hsv[12:28, 12:28] = (60, 255, 255)
        hsv[12:28, 32:48] = (150, 255, 255)
        # Anti-aliased layers can put isolated same-hue pixels elsewhere in the corner.
        hsv[35, 110] = (150, 255, 255)
        hsv[35, 111] = (0, 255, 255)
        self.assertTrue(measurement_gate(hsv, 1.0))
        # Stray pixels alone must not fake a measurement gate.
        hsv[12:28, 12:28] = 0
        hsv[12:28, 32:48] = 0
        self.assertFalse(measurement_gate(hsv, 1.0))


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
    def recording(
        self,
        path,
        offset=0,
        frames=730,
        moving=True,
        fps=60,
        cap="default",
        end_gate=False,
        scenario="setters",
        marker_gap=0,
    ):
        config = f"{scenario},surface,{cap},0"
        (path / "metadata.json").write_text(
            json.dumps(
                {
                    "config": config,
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
            f"MAP_BENCHMARK START {config} 1.0\nMAP_BENCHMARK CLOSED\nMAP_BENCHMARK DONE 0\n"
        )
        writer = cv2.VideoWriter(
            str(path / "screen.avi"), cv2.VideoWriter_fourcc(*"MJPG"), fps, (240, 160)
        )
        self.assertTrue(writer.isOpened())
        for frame in range(frames):
            image = np.full((160, 240, 3), 32, dtype=np.uint8)
            x = int(120 + 45 * np.sin(frame / fps * 2)) if moving else 120
            cv2.rectangle(image, (12, 12), (28, 28), (0, 255, 0), -1)
            # Startup can contain green UI. Only the two-color measurement pattern qualifies.
            if frame / fps >= 1 / 3:
                cv2.rectangle(image, (32, 12), (48, 28), (255, 0, 255), -1)
            # Occasional frames with the gate but no markers simulate a workload blanking the map.
            if not (marker_gap and frame % marker_gap == 0):
                cv2.circle(image, (x, 80), 6, (0, 0, 255), -1)
                cv2.circle(image, (x + offset, 80), 20, (255, 255, 0), 3)
            if end_gate and frame == frames - 1:
                cv2.rectangle(image, (12, 12), (28, 28), (32, 32, 32), -1)
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

    def test_stationary_map_is_valid_for_non_camera_scenarios(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.recording(path, moving=False, scenario="style-swap")
            result = analyze(path)
            self.assertEqual(result["samples"], 710)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.recording(path, moving=False, scenario="style-mutate")
            self.assertEqual(analyze(path)["samples"], 710)

    def test_coverage_profiles_distinguish_scenarios(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.recording(path, scenario="style-swap", marker_gap=14)
            self.assertGreaterEqual(analyze(path)["coverage"], 0.90)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.recording(path, scenario="style-mutate", marker_gap=14)
            with self.assertRaisesRegex(ValueError, "marker coverage"):
                analyze(path)

    def test_low_fps_captures_require_motion_and_a_complete_interval(self):
        for fps in (1, 2, 5):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                options = {
                    "fps": fps,
                    "cap": str(fps),
                    "frames": 13 * fps + 1,
                    "end_gate": True,
                }
                self.recording(path, **options)
                self.assertGreaterEqual(analyze(path)["samples"], 10)
                self.recording(path, **dict(options, frames=7 * fps))
                with self.assertRaises(ValueError):
                    analyze(path)
                self.recording(path, **dict(options, moving=False))
                with self.assertRaisesRegex(ValueError, "did not move"):
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
            # Equivalent parameter spellings still describe the same configuration.
            equivalent = dict(metadata, config="setters,surface,default,0,{}")
            (output / "metadata.json").write_text(json.dumps(equivalent))
            self.assertEqual(
                validate_workload(output, reference), str(reference.resolve())
            )
            (output / "metadata.json").write_text(json.dumps(metadata))
            # The app's logged parameters are ground truth even when metadata is edited.
            original_log = (reference / "app.log").read_text()
            (output / "app.log").write_text(
                original_log.replace(
                    "MAP_BENCHMARK START setters,surface,default,0 ",
                    'MAP_BENCHMARK START setters,surface,default,0,{"unexpected":1} ',
                )
            )
            with self.assertRaises(ValueError):
                validate_workload(output, reference)
            (output / "app.log").write_text(original_log)
            # A passing cached report cannot conceal a broken workload in the raw capture.
            self.recording(reference, moving=False)
            with self.assertRaises(ValueError):
                validate_workload(output, reference)


if __name__ == "__main__":
    unittest.main()
