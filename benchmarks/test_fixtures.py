import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from prepare_fixtures import points, prepare, route


class FixtureTest(unittest.TestCase):
    def test_geometry_volume_and_revision_probe(self):
        for revision in (0, 1):
            features = points(1000, revision)["features"]
            self.assertEqual(len(features), 1000)
            self.assertEqual(len({feature["id"] for feature in features}), 1000)
            self.assertEqual(features[0]["properties"]["revision"], revision)
            self.assertEqual(
                features[0]["geometry"]["coordinates"], [-122.4194, 37.7749]
            )
            self.assertEqual(
                len(route(revision)["features"][0]["geometry"]["coordinates"]), 2000
            )

    def test_refresh_accepts_current_downloads_and_failure_stays_stale(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for data in (b"first", b"updated"):
                with (
                    patch(
                        "prepare_fixtures.urllib.request.urlopen",
                        side_effect=lambda *_args, data=data, **_kwargs: io.BytesIO(
                            data
                        ),
                    ),
                    patch("builtins.print"),
                ):
                    prepare(root)
                self.assertEqual(
                    (root / "basemap/tiles/14/2618/6330.pbf").read_bytes(), data
                )
                self.assertTrue((root / ".prepared").exists())
            with (
                patch(
                    "prepare_fixtures.urllib.request.urlopen",
                    side_effect=OSError("offline"),
                ),
                self.assertRaises(OSError),
            ):
                prepare(root)
            self.assertFalse((root / ".prepared").exists())
