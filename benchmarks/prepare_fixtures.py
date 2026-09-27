"""Generate benchmark geometry and fetch the pinned basemap assets for packaging.

The basemap tiles and glyphs originate from VersaTiles, whose published data changes
over time. Every measurement must render the same scene, so one snapshot of that data
lives in the project's bucket under a version name, and fixtures/manifest.json pins the
version and each asset's content hash. Prepared runs download only from the bucket.
--refresh takes a new snapshot from VersaTiles, uploads it under a new version, and
rewrites the manifest; that changes the scene the series measures, which each run's
build information records as its fixture identity.
"""

import argparse
import datetime
import hashlib
import json
import math
import shutil
import subprocess
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "benchmarks/build/fixtures/benchmarks"
MANIFEST = Path(__file__).with_name("fixtures") / "manifest.json"
ORIGIN = [-122.4194, 37.7749]


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, separators=(",", ":"), ensure_ascii=False) + "\n")


def mixed(seed):
    """Avalanche each coordinate independently; linear modular sequences make stripes."""
    seed = (seed ^ (seed >> 16)) * 0x7FEB352D & 0xFFFFFFFF
    seed = (seed ^ (seed >> 15)) * 0x846CA68B & 0xFFFFFFFF
    return seed ^ (seed >> 16)


def points(count, variant):
    features = []
    for i in range(count):
        x = mixed(i * 2 + 1234567) / 2**32 - 0.5
        y = mixed(i * 2 + 1234568) / 2**32 - 0.5
        xy = (
            [
                round(ORIGIN[0] + x * 0.025 + variant * 0.0001, 6),
                round(ORIGIN[1] + y * 0.02, 6),
            ]
            if i
            else ORIGIN
        )
        features.append(
            {
                "type": "Feature",
                "id": i,
                "properties": {
                    "revision": variant,
                    "category": i % 4,
                    "value": i % 100,
                },
                "geometry": {"type": "Point", "coordinates": xy},
            }
        )
    return {"type": "FeatureCollection", "features": features}


def route(variant):
    coordinates = [
        [
            round(ORIGIN[0] + (i / 1999 - 0.5) * 0.02, 6),
            round(ORIGIN[1] + math.sin(i / 100) * 0.003 + variant * 0.0001, 6),
        ]
        for i in range(2000)
    ]
    return {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "id": 0,
                "properties": {"revision": variant},
                "geometry": {"type": "LineString", "coordinates": coordinates},
            }
        ],
    }


def source_paths():
    """Each packaged asset's path on the source server."""
    paths = {}
    for x in range(2618, 2623):
        for y in range(6330, 6335):
            paths[f"basemap/tiles/14/{x}/{y}.pbf"] = f"/tiles/osm/14/{x}/{y}"
    for span in ("0-255", "256-511"):
        paths[f"basemap/glyphs/noto_sans_regular/{span}.pbf"] = (
            f"/assets/glyphs/noto_sans_regular/{span}.pbf"
        )
    return paths


def read_manifest(path=MANIFEST):
    return json.loads(path.read_text())


def fetch(url):
    request = urllib.request.Request(
        url, headers={"User-Agent": "maplibre-compose-benchmarks"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def geometry(root):
    for count in (100, 1000, 10000):
        name = f"points-{count}"
        for variant in (0, 1):
            write(root / f"{name}-{variant}.geojson", points(count, variant))
    for variant in (0, 1):
        write(root / f"route-2000-{variant}.geojson", route(variant))
    for name in ("LICENSE.md", "OFL.txt"):
        shutil.copyfile(Path(__file__).with_name("fixtures") / name, root / name)


def prepare(root=ROOT, manifest_path=MANIFEST):
    """Generate the geometry and download the pinned basemap snapshot."""
    root.mkdir(parents=True, exist_ok=True)
    ready = root / ".prepared"
    ready.unlink(missing_ok=True)
    geometry(root)
    manifest = read_manifest(manifest_path)
    for name, expected in manifest["assets"].items():
        data = fetch(f"{manifest['mirror']}/{manifest['version']}/{name}")
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit(
                f"{name} in the bucket does not match the hash pinned in {manifest_path}"
            )
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        print(name, len(data))
    ready.touch()


def upload(bucket, prefix, root, names):
    def put(name):
        subprocess.run(
            [
                "wrangler",
                "r2",
                "object",
                "put",
                f"{bucket}/{prefix}/{name}",
                "--remote",
                f"--file={root / name}",
                "--content-type=application/x-protobuf",
                "--cache-control=public, max-age=31536000, immutable",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        print("uploaded", name)

    with ThreadPoolExecutor(8) as pool:
        list(pool.map(put, names))


def bucket_prefix(manifest, version):
    return f"{manifest['mirror'].rstrip('/').rsplit('/', 1)[-1]}/{version}"


def publish(bucket, root=ROOT, manifest_path=MANIFEST):
    """Upload the prepared snapshot under the manifest's version, after verifying it."""
    manifest = read_manifest(manifest_path)
    for name, expected in manifest["assets"].items():
        path = root / name
        if (
            not path.exists()
            or hashlib.sha256(path.read_bytes()).hexdigest() != expected
        ):
            raise SystemExit(
                f"{path} does not match the manifest; prepare fixtures first"
            )
    upload(
        bucket, bucket_prefix(manifest, manifest["version"]), root, manifest["assets"]
    )


def refresh(bucket, version=None, root=ROOT, manifest_path=MANIFEST):
    """Snapshot the current VersaTiles data, upload it, and pin it in the manifest."""
    manifest = read_manifest(manifest_path)
    version = (
        version or datetime.datetime.now(tz=datetime.timezone.utc).date().isoformat()
    )
    if version == manifest["version"]:
        raise SystemExit(f"Version {version} is already pinned; pass --version")
    root.mkdir(parents=True, exist_ok=True)
    (root / ".prepared").unlink(missing_ok=True)
    geometry(root)
    hashes = {}
    for name, path in source_paths().items():
        data = fetch(manifest["source"] + path)
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        hashes[name] = hashlib.sha256(data).hexdigest()
        print(name, len(data))
    upload(bucket, bucket_prefix(manifest, version), root, hashes)
    manifest.update(version=version, assets=hashes)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    (root / ".prepared").touch()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--publish",
        metavar="BUCKET",
        help="Upload the prepared snapshot to this R2 bucket instead of preparing",
    )
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="With --publish: snapshot the current source data under a new version",
    )
    parser.add_argument("--version", help="The new version's name; defaults to today")
    args = parser.parse_args()
    if args.refresh and not args.publish:
        parser.error("--refresh needs --publish BUCKET")
    if args.refresh:
        refresh(args.publish, args.version)
    elif args.publish:
        publish(args.publish)
    else:
        prepare()


if __name__ == "__main__":
    main()
