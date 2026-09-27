import hashlib
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

from prepare_fixtures import MANIFEST, points, prepare, refresh, route, source_paths


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

    def test_manifest_pins_every_packaged_asset(self):
        manifest = json.loads(MANIFEST.read_text())
        self.assertEqual(set(manifest["assets"]), set(source_paths()))
        self.assertTrue(manifest["mirror"].startswith("https://"))
        self.assertTrue(manifest["version"])

    def test_prepare_downloads_only_the_pinned_bucket_snapshot(self):
        pinned = {name: b"pinned " + name.encode() for name in source_paths()}
        manifest = {
            "source": "https://source.test",
            "mirror": "https://mirror.test/fixtures",
            "version": "v1",
            "assets": {
                name: hashlib.sha256(data).hexdigest() for name, data in pinned.items()
            },
        }
        served = {f"https://mirror.test/fixtures/v1/{n}": d for n, d in pinned.items()}

        def urlopen(request, timeout):
            url = request.full_url
            if url not in served:
                raise urllib.error.HTTPError(url, 404, "missing", {}, io.BytesIO())
            return io.BytesIO(served[url])

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "fixtures"
            manifest_path = Path(directory) / "manifest.json"
            manifest_path.write_text(json.dumps(manifest))
            with (
                patch("prepare_fixtures.urllib.request.urlopen", urlopen),
                patch("builtins.print"),
            ):
                prepare(root, manifest_path=manifest_path)
                self.assertEqual(
                    (root / "basemap/tiles/14/2618/6330.pbf").read_bytes(),
                    pinned["basemap/tiles/14/2618/6330.pbf"],
                )
                self.assertTrue((root / "points-1000-0.geojson").exists())
                self.assertTrue((root / ".prepared").exists())
                # The bucket is the only source, and its data must match the pin.
                served[
                    "https://mirror.test/fixtures/v1/basemap/tiles/14/2618/6330.pbf"
                ] = b"other"
                with self.assertRaisesRegex(SystemExit, "does not match"):
                    prepare(root, manifest_path=manifest_path)
                self.assertFalse((root / ".prepared").exists())
                del served[
                    "https://mirror.test/fixtures/v1/basemap/tiles/14/2618/6330.pbf"
                ]
                with self.assertRaises(urllib.error.HTTPError):
                    prepare(root, manifest_path=manifest_path)

    def test_refresh_snapshots_the_source_under_a_new_version(self):
        manifest = {
            "source": "https://source.test",
            "mirror": "https://mirror.test/fixtures",
            "version": "v1",
            "assets": {},
        }
        served = {
            f"https://source.test{p}": b"new " + n.encode()
            for n, p in source_paths().items()
        }
        uploads = []

        def urlopen(request, timeout):
            return io.BytesIO(served[request.full_url])

        def run(command, check, stdout):
            uploads.append(command[4])

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "fixtures"
            manifest_path = Path(directory) / "manifest.json"
            manifest_path.write_text(json.dumps(manifest))
            with (
                patch("prepare_fixtures.urllib.request.urlopen", urlopen),
                patch("prepare_fixtures.subprocess.run", run),
                patch("builtins.print"),
            ):
                with self.assertRaisesRegex(SystemExit, "already pinned"):
                    refresh("bucket", "v1", root, manifest_path)
                refresh("bucket", "v2", root, manifest_path)
            refreshed = json.loads(manifest_path.read_text())
            self.assertEqual(refreshed["version"], "v2")
            self.assertEqual(refreshed["assets"].keys(), source_paths().keys())
            self.assertEqual(
                refreshed["assets"]["basemap/glyphs/noto_sans_regular/0-255.pbf"],
                hashlib.sha256(
                    b"new basemap/glyphs/noto_sans_regular/0-255.pbf"
                ).hexdigest(),
            )
            self.assertEqual(len(uploads), len(source_paths()))
            self.assertTrue(all(u.startswith("bucket/fixtures/v2/") for u in uploads))
            self.assertTrue((root / ".prepared").exists())
