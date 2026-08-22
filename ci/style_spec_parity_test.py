"""Model tests for the style-spec catalog."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from ci.style_spec_parity import Pins, SpecProperty, Version, audit, scan_layers


def _spec(
    *,
    layer: str = "fill",
    kind: str = "paint",
    name: str = "fill-opacity",
    js: str | None = "1.0.0",
    android: str | None = "1.0.0",
    ios: str | None = "1.0.0",
) -> dict:
    basic = {}
    if js is not None:
        basic["js"] = js
    if android is not None:
        basic["android"] = android
    if ios is not None:
        basic["ios"] = ios
    return {
        "layer": {
            "type": {"values": {layer: {"sdk-support": {"basic functionality": basic}}}}
        },
        "source": [],
        f"{kind}_{layer}": {
            name: {"sdk-support": {"basic functionality": dict(basic)}},
        },
    }


def _write(root: pathlib.Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def _layer_file(
    root: pathlib.Path,
    source_set: str,
    filename: str,
    layer_type: str,
    writes: str,
) -> None:
    _write(
        root,
        f"lib/maplibre-compose/src/{source_set}/kotlin/org/maplibre/compose/layers/{filename}",
        f'internal class Demo : Layer("x") {{\n'
        f'  override val type: String = "{layer_type}"\n'
        f"  {writes}\n"
        f"}}\n",
    )


class VersionTest(unittest.TestCase):
    def test_parses_dotted_releases(self) -> None:
        self.assertEqual(str(Version.parse("6.2.0")), "6.2.0")
        self.assertEqual(str(Version.parse("0.10.0")), "0.10.0")
        self.assertEqual(str(Version.parse("5.20")), "5.20.0")

    def test_rejects_issue_urls(self) -> None:
        self.assertIsNone(
            Version.parse("https://github.com/maplibre/maplibre-native/issues/4298")
        )
        self.assertIsNone(Version.parse("not-a-version"))

    def test_orders_releases(self) -> None:
        self.assertTrue(Version.parse("6.2.0") <= Version.parse("6.2.0"))
        self.assertTrue(Version.parse("6.1.9") <= Version.parse("6.2.0"))
        self.assertFalse(Version.parse("6.3.0") <= Version.parse("6.2.0"))


class SupportTest(unittest.TestCase):
    def test_js_newer_than_pin_is_out_of_scope(self) -> None:
        prop = SpecProperty(
            "fill",
            "paint",
            "future",
            {
                "sdk-support": {
                    "basic functionality": {
                        "js": "6.3.0",
                        "android": "https://github.com/maplibre/maplibre-native/issues/1",
                    }
                }
            },
        )
        self.assertEqual(prop.engines(Pins(js=Version.parse("6.2.0"))), set())

    def test_js_at_the_pin_is_in_scope(self) -> None:
        prop = SpecProperty(
            "fill",
            "paint",
            "now",
            {"sdk-support": {"basic functionality": {"js": "6.2.0"}}},
        )
        self.assertEqual(prop.engines(Pins(js=Version.parse("6.2.0"))), {"js"})


class AuditTest(unittest.TestCase):
    def test_a_future_js_property_is_not_required(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(root, "commonMain", "FillLayer.kt", "fill", "")
            report = audit(
                _spec(js="6.3.0", android=None, ios=None),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertEqual(report.errors, [])
        self.assertTrue(any("beyond the JS pin" in line for line in report.lines))

    def test_a_pinned_js_property_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(root, "commonMain", "FillLayer.kt", "fill", "")
            report = audit(
                _spec(js="6.2.0", android=None, ios=None),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertTrue(
            any(
                "fill paint fill-opacity missing on js" in line
                for line in report.errors
            )
        )

    def test_paint_versus_layout_is_a_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "commonMain",
                "FillLayer.kt",
                "fill",
                'setLayoutProperty("fill-opacity", value)',
            )
            report = audit(
                _spec(js="1.0.0", android="1.0.0"),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertTrue(
            any("written as layout, spec says paint" in line for line in report.errors)
        )

    def test_a_js_main_write_does_not_need_the_native_table(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "jsMain",
                "FillLayer.kt",
                "fill",
                'setPaintProperty("fill-opacity", value)',
            )
            report = audit(
                _spec(js="1.0.0", android=None, ios=None),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertEqual(report.errors, [])

    def test_a_js_only_layer_in_js_main_counts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "jsMain",
                "FillLayer.kt",
                "fill",
                'setPaintProperty("fill-opacity", value)',
            )
            report = audit(
                _spec(js="1.0.0", android=None, ios=None),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertEqual(report.errors, [])

    def test_a_native_only_property_in_native_main_counts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "maplibreNativeMain",
                "FillLayer.kt",
                "fill",
                'setPaintProperty("fill-opacity", value)',
            )
            report = audit(
                _spec(js=None, android="1.0.0", ios="1.0.0"),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertEqual(report.errors, [])

    def test_a_common_js_only_write_needs_the_native_table(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "commonMain",
                "FillLayer.kt",
                "fill",
                'setPaintProperty("fill-opacity", value)',
            )
            report = audit(
                _spec(js="1.0.0", android=None, ios=None),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertTrue(
            any(
                "js-only properties missing from the native table" in line
                for line in report.errors
            )
        )

    def test_scan_reads_platform_source_sets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root, "jsMain", "JsLayer.kt", "fill", 'setPaintProperty("a", v)'
            )
            _layer_file(
                root,
                "maplibreNativeMain",
                "NativeLayer.kt",
                "location-indicator",
                'setPaintProperty("bearing", v)',
            )
            api = scan_layers(root)
        self.assertEqual(api.types["fill"], {"js"})
        self.assertEqual(api.types["location-indicator"], {"native"})
        self.assertEqual(api.writers("fill", "paint", "a"), {"js"})
        self.assertEqual(
            api.writers("location-indicator", "paint", "bearing"), {"native"}
        )


if __name__ == "__main__":
    unittest.main()
