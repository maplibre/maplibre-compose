import json
import unittest

from performance import process_cpu_metrics, window_metrics


class WindowMetricsTest(unittest.TestCase):
    def log(self, frames=3, lost=0):
        report = {"frames": frames, "lost_reports": lost, "missed_deadlines": 1}
        return (
            "MAP_BENCHMARK WINDOW "
            + json.dumps(report)
            + "\nMAP_BENCHMARK FRAMES 1.0,1.5E-4;2.0,-1.0\nMAP_BENCHMARK FRAMES 3.0,1.0\n"
        )

    def test_batches_preserve_small_gpu_durations_and_unavailable_samples(self):
        result = window_metrics(self.log())
        self.assertEqual(result["frames"], 3)
        self.assertEqual(result["total_ms"]["p50"], 2)
        self.assertAlmostEqual(result["gpu_ms"]["p50"], (1.0 + 0.00015) / 2)

    def test_incomplete_or_dropped_reports_are_rejected(self):
        for log in (self.log(frames=4), self.log(lost=1)):
            with self.assertRaises(ValueError):
                window_metrics(log)


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
