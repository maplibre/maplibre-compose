import json
import unittest

from performance import process_cpu_metrics, window_metrics


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


if __name__ == "__main__":
    unittest.main()
