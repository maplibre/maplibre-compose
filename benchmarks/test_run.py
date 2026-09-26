import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

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
