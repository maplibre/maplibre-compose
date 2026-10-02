import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from presentation import MapPresentation, timestamps
from run import wait_for


class CaptureTest(unittest.TestCase):
    def test_console_exit_reports_launch_failure_without_waiting_for_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "app.log"
            process = Mock()
            process.poll.return_value = 1
            log.write_text("Unable to launch because the device is locked")
            with self.assertRaisesRegex(RuntimeError, "App exited"):
                wait_for(log, process)
            # A normally completed app can exit before the runner polls its log.
            process.poll.return_value = 0
            log.write_text("MAP_BENCHMARK DONE")
            wait_for(log, process)


class PresentationTest(unittest.TestCase):
    def test_surface_fences_ignore_empty_pending_and_duplicate_frames(self):
        self.assertEqual(
            timestamps(
                "8333333\n0 0 0\n1 9223372036854775807 2\n1 100 2\n2 100 3\n3 200 4\n4 300 -1\n"
            ),
            [100, 200, 300],
        )

    def test_history_overrun_rejects_capture_instead_of_inventing_a_slow_map(self):
        collector = MapPresentation([], "app")
        collector.layer = "map"
        collector.shell = Mock(
            side_effect=["8333333\n1 100 1\n2 200 2", "8333333\n3 300 3\n4 400 4"]
        )
        collector.poll()
        collector.next_poll = 0
        collector.poll()
        self.assertEqual(collector.gaps, [[200, 300]])
