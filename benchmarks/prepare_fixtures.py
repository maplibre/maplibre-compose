"""Generate benchmark geometry and download current basemap assets for packaging."""

import json
import math
import shutil
import urllib.request
from pathlib import Path

ROOT = (
    Path(__file__).resolve().parents[1]
    / "demo-app/common/build/generated/benchmarkResources/files/benchmarks"
)
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


def prepare(root=ROOT):
    root.mkdir(parents=True, exist_ok=True)
    ready = root / ".prepared"
    ready.unlink(missing_ok=True)
    for count in (100, 1000, 10000):
        name = f"points-{count}"
        for variant in (0, 1):
            write(root / f"{name}-{variant}.geojson", points(count, variant))
    for variant in (0, 1):
        write(root / f"route-2000-{variant}.geojson", route(variant))
    assets = {}
    for x in range(2618, 2623):
        for y in range(6330, 6335):
            assets[f"basemap/tiles/14/{x}/{y}.pbf"] = (
                f"https://tiles.versatiles.org/tiles/osm/14/{x}/{y}"
            )
    for span in ("0-255", "256-511"):
        assets[f"basemap/glyphs/noto_sans_regular/{span}.pbf"] = (
            f"https://tiles.versatiles.org/assets/glyphs/noto_sans_regular/{span}.pbf"
        )
    for filename, url in assets.items():
        path = root / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        path.write_bytes(data)
        print(filename, len(data))
    for name in ("LICENSE.md", "OFL.txt"):
        shutil.copyfile(Path(__file__).with_name("fixtures") / name, root / name)
    ready.touch()


if __name__ == "__main__":
    prepare()
