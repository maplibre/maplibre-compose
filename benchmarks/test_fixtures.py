import hashlib
import json
import unittest

from prepare_fixtures import ROOT, points, route


class FixtureTest(unittest.TestCase):
    def test_snapshot_bytes_and_manifest_identity(self):
        manifest = json.loads((ROOT / "manifest.json").read_text())
        for filename, asset in manifest["assets"].items():
            data = (ROOT / filename).read_bytes()
            self.assertEqual(len(data), asset["bytes"], filename)
            self.assertEqual(
                hashlib.sha256(data).hexdigest(), asset["sha256"], filename
            )
        digest = hashlib.sha256(
            json.dumps(
                manifest["assets"], sort_keys=True, separators=(",", ":")
            ).encode()
        ).hexdigest()
        self.assertEqual(digest, manifest["sha256"])

    def test_prepared_revisions_keep_geometry_counts_and_probe_identity(self):
        for count in (100, 1000, 10000):
            for revision in (0, 1):
                data = json.loads(
                    (ROOT / f"points-{count}-{revision}.geojson").read_text()
                )
                self.assertEqual(data, points(count, revision))
                features = data["features"]
                self.assertEqual(len(features), count)
                self.assertEqual(len({f["id"] for f in features}), count)
                self.assertEqual(features[0]["properties"]["revision"], revision)
                self.assertEqual(
                    features[0]["geometry"]["coordinates"], [-122.4194, 37.7749]
                )
        for revision in (0, 1):
            data = json.loads((ROOT / f"route-2000-{revision}.geojson").read_text())
            self.assertEqual(data, route(revision))
            self.assertEqual(len(data["features"]), 1)
            self.assertEqual(len(data["features"][0]["geometry"]["coordinates"]), 2000)
