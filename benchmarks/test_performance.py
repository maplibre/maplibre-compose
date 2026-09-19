import json
import unittest

from performance import frames_metrics, process_cpu_metrics, window_metrics


class FramesMetricsTest(unittest.TestCase):
    def log(self, records, config="animation,surface,default,0,{}", frames=None):
        summary = {
            "frames": len(records) if frames is None else frames,
            "duration_ms": 12000.0,
        }
        return (
            f"MAP_BENCHMARK START {config} 1.0\n"
            "MAP_BENCHMARK FRAMESTATS "
            + json.dumps(summary)
            + "\nMAP_BENCHMARK FRAMETIMES "
            + json.dumps(records)
            + "\n"
        )

    def test_interval_distribution_and_jank_counts(self):
        records = [
            {
                "interval_ms": 16.0,
                "encoding_ms": 1.0,
                "rendering_ms": 4.0,
                "draw_calls": 10,
                "mode": "full",
            },
            {
                "interval_ms": 30.0,
                "encoding_ms": 2.0,
                "rendering_ms": 5.0,
                "draw_calls": 20,
                "mode": "full",
            },
            {
                "interval_ms": 40.0,
                "encoding_ms": 3.0,
                "rendering_ms": 6.0,
                "draw_calls": 30,
                "mode": "partial",
            },
        ]
        result = frames_metrics(self.log(records))
        self.assertTrue(result["available"])
        self.assertEqual(result["frames"], 3)
        self.assertEqual(result["interval_ms"]["p50"], 30.0)
        self.assertAlmostEqual(result["assumed_vsync_ms"], 1000 / 60)
        self.assertEqual(result["jank_over_1_5x"], 2)
        self.assertEqual(result["jank_over_2x"], 1)
        self.assertEqual(result["encoding_ms"]["p50"], 2.0)
        self.assertEqual(result["rendering_ms"]["p50"], 5.0)
        self.assertEqual(result["draw_calls"]["p50"], 20.0)
        self.assertEqual(result["modes"], {"full": 2, "partial": 1})
        self.assertTrue(result["native_render_stats"])

    def test_log_batches_concatenate_in_order(self):
        summary = {"frames": 2, "duration_ms": 33.0}
        logs = (
            "MAP_BENCHMARK START animation,surface,default,0,{} 1.0\n"
            "MAP_BENCHMARK FRAMESTATS "
            + json.dumps(summary)
            + "\nMAP_BENCHMARK FRAMETIMES "
            + json.dumps([{"interval_ms": 16.0}])
            + "\nMAP_BENCHMARK FRAMETIMES "
            + json.dumps([{"interval_ms": 17.0}])
            + "\n"
        )
        result = frames_metrics(logs)
        self.assertEqual(result["frames"], 2)
        self.assertEqual(result["interval_ms"]["max"], 17.0)

    def test_budget_follows_the_logged_fps_cap(self):
        result = frames_metrics(
            self.log([{"interval_ms": 10.0}], config="animation,surface,30,0,{}")
        )
        self.assertAlmostEqual(result["assumed_vsync_ms"], 1000 / 30)
        self.assertEqual(result["jank_over_1_5x"], 0)
        self.assertIsNone(result["encoding_ms"])
        # A START line stays parseable when params contain spaces.
        spaced = self.log(
            [{"interval_ms": 10.0}],
            config='style-complex,surface,60,0,{"label":"a b"}',
        )
        self.assertAlmostEqual(frames_metrics(spaced)["assumed_vsync_ms"], 1000 / 60)

    def test_truncated_and_invalid_reports_are_rejected(self):
        logs = self.log([{"interval_ms": 16.0}])
        with self.assertRaisesRegex(ValueError, "incomplete"):
            frames_metrics(self.log([{"interval_ms": 16.0}], frames=2))
        with self.assertRaisesRegex(ValueError, "interval"):
            frames_metrics(self.log([{"interval_ms": -1.0}]))
        with self.assertRaisesRegex(ValueError, "interval"):
            frames_metrics(self.log([{"interval_ms": "16"}]))
        with self.assertRaisesRegex(ValueError, "interval"):
            frames_metrics(self.log([{"encoding_ms": 1.0}]))
        with self.assertRaisesRegex(ValueError, "Malformed"):
            frames_metrics(
                logs.replace('[{"interval_ms": 16.0}]', '[{"interval_ms": 16.0},]')
            )
        with self.assertRaisesRegex(ValueError, "summary"):
            frames_metrics(logs.replace("12000.0", "NaN", 1))
        with self.assertRaisesRegex(ValueError, "FPS cap"):
            frames_metrics(
                self.log([{"interval_ms": 10.0}], config="animation,surface,abc,0,{}")
            )
        with self.assertRaisesRegex(ValueError, "one in-app"):
            frames_metrics(logs + logs)
        self.assertFalse(frames_metrics("unsupported")["available"])


class WindowMetricsTest(unittest.TestCase):
    def log(self, frames=3, lost=0):
        report = {"frames": frames, "lost_reports": lost}
        return (
            "MAP_BENCHMARK WINDOW "
            + json.dumps(report)
            + "\nMAP_BENCHMARK FRAMES 1000000,1000000,150,1000000;2000000,2000000,-1,1000000\nMAP_BENCHMARK FRAMES 4000000,3000000,1000000,4000000\n"
        )

    def test_batches_preserve_small_gpu_durations_and_unavailable_samples(self):
        result = window_metrics(self.log(), 1000000, 7000000)
        self.assertEqual(result["frames"], 3)
        self.assertEqual(result["total_ms"]["p50"], 2)
        self.assertAlmostEqual(result["gpu_ms"]["p50"], (1.0 + 0.00015) / 2)

    def test_unavailable_timestamps_do_not_contribute_to_interval_metrics(self):
        logs = self.log().replace("1000000,1000000,150", "-1,1000000,150")
        result = window_metrics(logs, 1000000, 7000000)
        self.assertEqual(result["reported_frames"], 3)
        self.assertEqual(result["frames"], 2)
        self.assertEqual(result["total_ms"]["p50"], 2.5)

        logs = logs.replace("2000000,2000000,-1", "-1,2000000,-1").replace(
            "4000000,3000000,1000000", "-1,3000000,1000000"
        )
        result = window_metrics(logs, 1000000, 7000000)
        self.assertFalse(result["available"])
        self.assertEqual(result["reason"], "Window frame timestamps are unavailable")
        self.assertEqual(result["reported_frames"], 3)
        self.assertEqual(result["frames"], 0)
        self.assertIsNone(result["total_ms"])
        self.assertIsNone(result["gpu_ms"])

    def test_invalid_timestamps_are_rejected(self):
        for timestamp in (0, -2):
            logs = self.log().replace("1000000,1000000,150", f"{timestamp},1000000,150")
            with self.assertRaisesRegex(ValueError, "Invalid Window FrameMetrics"):
                window_metrics(logs, 1000000, 7000000)

    def test_incomplete_or_dropped_reports_are_rejected(self):
        for log in (self.log(frames=4), self.log(lost=1)):
            with self.assertRaises(ValueError):
                window_metrics(log, 1000000, 7000000)

    def test_only_complete_frames_inside_trace_contribute(self):
        result = window_metrics(self.log(), 4000000, 7000000)
        self.assertEqual(result["reported_frames"], 3)
        self.assertEqual(result["frames"], 1)
        self.assertEqual(result["missed_deadlines"], 0)
        self.assertEqual(result["total_ms"]["p95"], 3)
        self.assertEqual(result["gpu_ms"]["p95"], 1)
        for start, end in ((1000001, 1999999), (4000000, 6999999)):
            result = window_metrics(self.log(), start, end)
            self.assertFalse(result["available"])
            self.assertIsNone(result["total_ms"])


class ProcessCpuTest(unittest.TestCase):
    def test_counter_delta_and_unavailable_measurements(self):
        self.assertEqual(
            process_cpu_metrics("MAP_BENCHMARK CPU 1.25e3")["cpu_ms"], 1250
        )
        self.assertIsNone(process_cpu_metrics("unsupported"))
        for value in ("-1", "nan", "inf", "1\nMAP_BENCHMARK CPU 2"):
            with self.assertRaises(ValueError):
                process_cpu_metrics("MAP_BENCHMARK CPU " + value)

    def test_frames_without_a_cpu_counter_still_report(self):
        logs = (
            "MAP_BENCHMARK START animation,surface,default,0,{} 1.0\n"
            'MAP_BENCHMARK FRAMESTATS {"frames": 1, "duration_ms": 16.0}\n'
            'MAP_BENCHMARK FRAMETIMES [{"interval_ms": 16.0}]\n'
        )
        result = process_cpu_metrics(logs)
        self.assertIsNone(result["cpu_ms"])
        self.assertTrue(result["frames"]["available"])
        self.assertEqual(result["frames"]["frames"], 1)


if __name__ == "__main__":
    unittest.main()
