"""Sanity checks for the style-spec catalog scanner. No network."""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from ci.style_spec_parity import scan_layer_api, scan_native_unsupported


def test_scan_finds_the_properties_this_api_writes() -> None:
    by_type, types = scan_layer_api()
    assert "fill" in types
    assert "location-indicator" in types
    assert "fill-layer-opacity" in by_type["fill"]
    assert "line-layer-opacity" in by_type["line"]
    assert "hillshade-method" in by_type["hillshade"]
    assert "hillshade-illumination-altitude" in by_type["hillshade"]
    assert "resampling" in by_type["hillshade"]
    assert "resampling" in by_type["color-relief"]
    assert "raster-resampling" in by_type["raster"]


def test_native_table_lists_js_only_properties() -> None:
    unsupported = scan_native_unsupported()
    assert ("fill", "fill-layer-opacity") in unsupported
    assert ("line", "line-layer-opacity") in unsupported
    assert ("hillshade", "resampling") in unsupported
    assert ("symbol", "icon-overlap") in unsupported


if __name__ == "__main__":
    test_scan_finds_the_properties_this_api_writes()
    test_native_table_lists_js_only_properties()
    print("ok")
