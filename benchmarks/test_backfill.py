import re
import tempfile
import unittest
from pathlib import Path

from backfill import host


class HostTest(unittest.TestCase):
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
