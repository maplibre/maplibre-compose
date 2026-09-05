"""Model tests for the style-spec catalog."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from ci.style_spec_parity import (
    Pins,
    SpecProperty,
    Version,
    audit,
    dead_setters,
    scan_layers,
    scan_root_objects,
    scan_sources,
)


def _spec(
    *,
    layer: str = "fill",
    kind: str = "paint",
    name: str = "fill-opacity",
    js: str | None = "1.0.0",
    android: str | None = "1.0.0",
    ios: str | None = "1.0.0",
    transition: bool = False,
) -> dict:
    basic = {}
    if js is not None:
        basic["js"] = js
    if android is not None:
        basic["android"] = android
    if ios is not None:
        basic["ios"] = ios
    entry: dict = {"sdk-support": {"basic functionality": dict(basic)}}
    if transition:
        entry["transition"] = True
    return {
        "layer": {
            "type": {"values": {layer: {"sdk-support": {"basic functionality": basic}}}}
        },
        "source": [],
        f"{kind}_{layer}": {name: entry},
    }


_LAYERS_DIR = "lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/layers/"


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


class RootObjectTest(unittest.TestCase):
    def test_a_missing_root_property_is_an_error(self) -> None:
        spec = _spec(js="1.0.0", android=None, ios=None)
        spec["sky"] = {
            "sky-color": {"sdk-support": {"basic functionality": {"js": "1.0.0"}}},
            "fog-color": {"sdk-support": {"basic functionality": {"js": "1.0.0"}}},
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(root, "commonMain", "FillLayer.kt", "fill", "")
            _write(
                root,
                "lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/style/Sky.kt",
                'putExpression("sky-color", skyColor)\nputExpression("haze", haze)\n',
            )
            self.assertEqual(scan_root_objects(root), {"sky": {"sky-color", "haze"}})
            report = audit(spec, root, Pins(js=Version.parse("6.2.0")))
        self.assertTrue(
            any("sky: missing fog-color (js)" in line for line in report.errors)
        )
        self.assertTrue(
            any("sky: unexpected extra haze" in line for line in report.errors)
        )

    def test_a_root_property_beyond_every_pin_is_not_required(self) -> None:
        spec = _spec(js="1.0.0", android=None, ios=None)
        spec["sky"] = {
            "sky-color": {"sdk-support": {"basic functionality": {"js": "9.0.0"}}},
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(root, "commonMain", "FillLayer.kt", "fill", "")
            _write(
                root,
                "lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/style/Sky.kt",
                "",
            )
            report = audit(spec, root, Pins(js=Version.parse("6.2.0")))
        self.assertFalse(any(line.startswith("error: sky") for line in report.errors))


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

    def test_a_native_only_property_in_common_main_is_a_note(self) -> None:
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
                _spec(js=None, android="1.0.0", ios="1.0.0"),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertEqual(report.errors, [])
        self.assertTrue(
            any("stored but not rendered on js" in line for line in report.lines)
        )

    def test_a_js_only_source_is_missing_on_native(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(root, "commonMain", "FillLayer.kt", "fill", "")
            _write(
                root,
                "lib/maplibre-compose/src/jsMain/kotlin/org/maplibre/compose/"
                "sources/GeoJsonSource.kt",
                'internal class GeoJsonSource { init { put("type", "geojson") } }\n',
            )
            spec = _spec(js="1.0.0", android="1.0.0")
            spec["source"] = ["source_geojson"]
            report = audit(spec, root, Pins(js=Version.parse("6.2.0")))
        self.assertTrue(
            any(
                "missing source types: geojson (native)" in line
                for line in report.errors
            )
        )

    def test_a_source_in_common_main_covers_both_engines(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _write(
                root,
                "lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/"
                "sources/GeoJsonSource.kt",
                'internal class GeoJsonSource { init { put("type", "geojson") } }\n',
            )
            found = scan_sources(root)
        self.assertEqual(found, {"geojson": {"js", "native"}})

    def test_an_uncalled_setter_is_dead(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "commonMain",
                "FillLayer.kt",
                "fill",
                'fun setFillOpacity(value: Any) { setPaintProperty("fill-opacity", value) }',
            )
            self.assertEqual(dead_setters(root), ["FillLayer.kt:setFillOpacity"])

    def test_a_called_setter_is_reachable(self) -> None:
        writes = (
            'fun setFillOpacity(value: Any) { setPaintProperty("fill-opacity", value) }\n'
            "  fun update(value: Any) { setFillOpacity(value) }"
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(root, "commonMain", "FillLayer.kt", "fill", writes)
            self.assertEqual(dead_setters(root), [])

    def test_a_call_to_another_class_is_not_reachability(self) -> None:
        setter = (
            'fun setResampling(value: Any) { setPaintProperty("resampling", value) }'
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _write(
                root,
                _LAYERS_DIR + "HillshadeLayer.kt",
                f"internal class HillshadeLayer : Layer() {{\n  {setter}\n}}\n",
            )
            _write(
                root,
                _LAYERS_DIR + "ColorReliefLayer.kt",
                f"internal class ColorReliefLayer : Layer() {{\n  {setter}\n}}\n"
                "fun update(layer: ColorReliefLayer) { layer.setResampling(1) }\n",
            )
            self.assertEqual(dead_setters(root), ["HillshadeLayer.kt:setResampling"])

    def test_a_subclass_call_reaches_an_inherited_setter(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _write(
                root,
                _LAYERS_DIR + "FeatureLayer.kt",
                "internal abstract class FeatureLayer : Layer() {\n"
                "  fun setSourceLayerProperty(value: Any) "
                '{ setRootProperty("source-layer", value) }\n'
                "}\n",
            )
            _write(
                root,
                _LAYERS_DIR + "FillLayer.kt",
                "internal class FillLayer : FeatureLayer() {\n"
                "  fun update(value: Any) { setSourceLayerProperty(value) }\n"
                "}\n",
            )
            self.assertEqual(dead_setters(root), [])

    def test_a_composable_in_another_file_reaches_the_setter(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _write(
                root,
                _LAYERS_DIR + "LocationLayer.kt",
                "internal class LocationLayer : Layer() {\n"
                '  fun setBearing(value: Any) { setPaintProperty("bearing", value) }\n'
                "}\n",
            )
            _write(
                root,
                "lib/maplibre-compose/src/maplibreNativeMain/kotlin/org/maplibre/"
                "compose/layers/LocationLayerComposable.kt",
                "fun Composable() { val layer = LocationLayer()\n"
                "  layer.setBearing(1) }\n",
            )
            self.assertEqual(dead_setters(root), [])

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


class TransitionTest(unittest.TestCase):
    def test_a_transitionable_property_needs_a_transition_write(self) -> None:
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
                _spec(transition=True),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertTrue(
            any(
                "fill-opacity-transition missing on js+native" in line
                for line in report.errors
            )
        )

    def test_a_transition_write_satisfies_the_audit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "commonMain",
                "FillLayer.kt",
                "fill",
                'setPaintProperty("fill-opacity", value)\n'
                '  setPaintTransition("fill-opacity", options)',
            )
            report = audit(
                _spec(transition=True),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertEqual(report.errors, [])

    def test_a_transition_the_spec_does_not_allow_is_extra(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "commonMain",
                "FillLayer.kt",
                "fill",
                'setPaintProperty("fill-opacity", value)\n'
                '  setPaintTransition("fill-opacity", options)',
            )
            report = audit(
                _spec(),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertTrue(
            any("unexpected extra transitions" in line for line in report.errors)
        )

    def test_a_js_only_transition_is_required_on_js_alone(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "jsMain",
                "FillLayer.kt",
                "fill",
                'setPaintProperty("fill-opacity", value)\n'
                '  setPaintTransition("fill-opacity", options)',
            )
            report = audit(
                _spec(js="1.0.0", android=None, ios=None, transition=True),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertEqual(report.errors, [])

    def test_an_aliased_transition_is_audited_under_the_written_name(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "commonMain",
                "RasterLayer.kt",
                "raster",
                'setPaintProperty("raster-resampling", value)\n'
                '  setPaintTransition("raster-resampling", options)',
            )
            report = audit(
                _spec(layer="raster", name="resampling", transition=True),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertEqual(report.errors, [])

    def test_a_transition_write_is_not_the_property_write(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _layer_file(
                root,
                "commonMain",
                "FillLayer.kt",
                "fill",
                'setPaintTransition("fill-opacity", options)',
            )
            report = audit(
                _spec(transition=True),
                root,
                Pins(js=Version.parse("6.2.0")),
            )
        self.assertTrue(
            any(
                "fill paint fill-opacity missing on js+native" in line
                for line in report.errors
            )
        )


if __name__ == "__main__":
    unittest.main()
