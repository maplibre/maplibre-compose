"""Compare the layer API with MapLibre style-spec metadata.

The published spec is shared. MapLibre GL JS and MapLibre Native implement it at
their own pace. This catalog lists the layer types and paint/layout properties
the spec defines, which engines implement them, and whether this repository
exposes them.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC_URL = (
    "https://raw.githubusercontent.com/maplibre/maplibre-style-spec/"
    "main/src/reference/v8.json"
)

LAYERS = pathlib.Path(
    "lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/layers"
)
SOURCES = pathlib.Path(
    "lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/sources"
)
NATIVE_BINDING = pathlib.Path(
    "lib/maplibre-compose/src/maplibreNativeMain/kotlin/"
    "org/maplibre/compose/style/MlnFfiStyleBinding.kt"
)

LAYER_TYPE = re.compile(r'override val type: String = "([^"]+)"')
PROPERTY_WRITE = re.compile(r'set(?:Layout|Paint|Root)Property\(\s*"([^"]+)"')
UNSUPPORTED_PAIR = re.compile(r'\("([^"]+)"\s+to\s+"([^"]+)"\)')

SHARED_PROPERTIES = frozenset(
    {"visibility", "minzoom", "maxzoom", "filter", "source-layer"}
)
LAYER_OBJECT_KEYS = frozenset(
    {
        "id",
        "type",
        "metadata",
        "source",
        "source-layer",
        "minzoom",
        "maxzoom",
        "filter",
        "layout",
        "paint",
    }
)

# Spec property this API writes under another name.
ALIASES = {("raster", "resampling"): "raster-resampling"}

# Types this API exposes that the published spec does not list.
EXTRA_LAYER_TYPES = frozenset({"location-indicator"})
EXTRA_SOURCE_TYPES = frozenset({"computed"})

# Spec source types this API does not construct.
OMITTED_SOURCE_TYPES = frozenset({"video"})

SOURCE_CLASSES = {
    "VectorSource": "vector",
    "RasterSource": "raster",
    "RasterDemSource": "raster-dem",
    "GeoJsonSource": "geojson",
    "ImageSource": "image",
    "ComputedSource": "computed",
}


def is_version(value: object) -> bool:
    """True when sdk-support records a release rather than an issue URL."""
    return isinstance(value, str) and value[:1].isdigit()


def platforms(support: dict[str, Any] | None) -> set[str]:
    """Engines that implement basic functionality: js, native, or both."""
    basic = (support or {}).get("basic functionality") or {}
    found: set[str] = set()
    if is_version(basic.get("js")):
        found.add("js")
    if is_version(basic.get("android")) or is_version(basic.get("ios")):
        found.add("native")
    return found


def load_spec(path: pathlib.Path | None) -> tuple[dict[str, Any], str]:
    """Load v8.json from [path], or fetch the published latest copy."""
    if path is not None:
        return json.loads(path.read_text()), str(path)
    request = urllib.request.Request(
        SPEC_URL, headers={"User-Agent": "maplibre-compose-style-spec-parity"}
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read()
    except urllib.error.URLError as error:
        raise SystemExit(f"error: could not fetch {SPEC_URL}: {error}") from error
    return json.loads(payload), SPEC_URL


def spec_layers(spec: dict[str, Any]) -> dict[str, dict[str, dict[str, Any]]]:
    """Map each spec layer type to its paint and layout properties."""
    layers: dict[str, dict[str, dict[str, Any]]] = {}
    for layer_type in spec["layer"]["type"]["values"]:
        properties: dict[str, dict[str, Any]] = {}
        for prefix in ("layout", "paint"):
            for name, entry in (spec.get(f"{prefix}_{layer_type}") or {}).items():
                if name in LAYER_OBJECT_KEYS or not isinstance(entry, dict):
                    continue
                properties[name] = entry
        layers[layer_type] = properties
    return layers


def spec_sources(spec: dict[str, Any]) -> set[str]:
    """Style-spec source type names."""
    names: set[str] = set()
    for table in spec["source"]:
        if table.startswith("source_"):
            names.add(table.removeprefix("source_").replace("_", "-"))
    return names


def written_layers(root: pathlib.Path) -> dict[str, set[str]]:
    """Map each API layer type to the style-spec names it writes."""
    by_type: dict[str, set[str]] = {}
    shared = set(SHARED_PROPERTIES)
    for path in sorted((root / LAYERS).glob("*.kt")):
        text = path.read_text()
        types = LAYER_TYPE.findall(text)
        names = set(PROPERTY_WRITE.findall(text))
        if not types:
            shared.update(names)
            continue
        for layer_type in types:
            by_type.setdefault(layer_type, set()).update(names)
    for names in by_type.values():
        names.update(shared)
    return by_type


def native_unsupported(root: pathlib.Path) -> set[tuple[str, str]]:
    """Layer properties the native binding refuses to write."""
    return set(UNSUPPORTED_PAIR.findall((root / NATIVE_BINDING).read_text()))


def written_sources(root: pathlib.Path) -> set[str]:
    """Source types this API constructs."""
    found: set[str] = set()
    for path in (root / SOURCES).glob("*.kt"):
        text = path.read_text()
        for class_name, source_type in SOURCE_CLASSES.items():
            if f"class {class_name}" in text:
                found.add(source_type)
    return found


class Audit:
    """A catalog of notes, plus the subset that fail `--check`."""

    def __init__(self) -> None:
        self.lines: list[str] = []
        self.errors: list[str] = []

    def note(self, line: str) -> None:
        self.lines.append(line)

    def error(self, line: str) -> None:
        text = f"error: {line}"
        self.lines.append(text)
        self.errors.append(text)


def audit(spec: dict[str, Any], root: pathlib.Path = ROOT) -> Audit:
    report = Audit()
    spec_props = spec_layers(spec)
    api_layers = written_layers(root)
    unsupported = native_unsupported(root)

    report.note("Layer types")
    extra_layers = sorted(set(api_layers) - set(spec_props) - EXTRA_LAYER_TYPES)
    missing_layers = sorted(set(spec_props) - set(api_layers))
    known_extra_layers = sorted(set(api_layers) & EXTRA_LAYER_TYPES)
    if known_extra_layers:
        report.note("  extra (native extension): " + ", ".join(known_extra_layers))
    if extra_layers:
        report.error("unexpected extra layer types: " + ", ".join(extra_layers))
    if missing_layers:
        report.error("missing layer types: " + ", ".join(missing_layers))
    if not extra_layers and not missing_layers:
        report.note("  spec types: all present")

    report.note("Layer properties")
    for layer_type, properties in sorted(spec_props.items()):
        written = api_layers.get(layer_type, set())
        missing: list[str] = []
        aliased: list[str] = []
        js_only: list[str] = []
        for name in sorted(properties):
            alias = ALIASES.get((layer_type, name))
            if alias and alias in written:
                aliased.append(f"{name} -> {alias}")
                continue
            if name not in written:
                missing.append(name)
                continue
            if platforms(properties[name].get("sdk-support")) == {"js"}:
                js_only.append(name)
        unexpected = sorted(
            name
            for name in written
            if name not in properties
            and name not in SHARED_PROPERTIES
            and name not in LAYER_OBJECT_KEYS
        )
        if missing:
            report.error(f"{layer_type} missing properties: " + ", ".join(missing))
        if unexpected:
            report.error(f"{layer_type} extra properties: " + ", ".join(unexpected))
        if aliased:
            report.note(f"  {layer_type} aliases: " + ", ".join(aliased))
        if js_only:
            report.note(f"  {layer_type} js-only (exposed): " + ", ".join(js_only))
        if not missing and not unexpected and not aliased and not js_only:
            report.note(f"  {layer_type}: complete")

    report.note("Native unsupported table")
    stale: list[str] = []
    missing_rows: list[str] = []
    listed: list[str] = []
    for layer_type, properties in sorted(spec_props.items()):
        written = api_layers.get(layer_type, set())
        for name, entry in sorted(properties.items()):
            if name not in written:
                continue
            engine = platforms(entry.get("sdk-support"))
            in_table = (layer_type, name) in unsupported
            if engine == {"js"} and not in_table:
                missing_rows.append(f"{layer_type}.{name}")
            elif "native" in engine and in_table:
                stale.append(f"{layer_type}.{name}")
            elif in_table:
                listed.append(f"{layer_type}.{name}")
    extra_rows = sorted(
        f"{layer_type}.{name}"
        for layer_type, name in unsupported
        if name not in spec_props.get(layer_type, {})
    )
    if listed:
        report.note("  filtered on native: " + ", ".join(listed))
    if missing_rows:
        report.error(
            "js-only properties missing from the native table: "
            + ", ".join(missing_rows)
        )
    if stale:
        report.error(
            "native table still lists properties the spec says native implements: "
            + ", ".join(stale)
        )
    if extra_rows:
        report.error(
            "native table lists properties the spec does not define: "
            + ", ".join(extra_rows)
        )
    if not missing_rows and not stale and not extra_rows:
        report.note("  table matches the spec")

    report.note("Source types")
    spec_source_types = spec_sources(spec)
    api_source_types = written_sources(root)
    extra_sources = sorted(api_source_types - spec_source_types - EXTRA_SOURCE_TYPES)
    missing_sources = sorted(
        spec_source_types - api_source_types - OMITTED_SOURCE_TYPES
    )
    known_extra_sources = sorted(api_source_types & EXTRA_SOURCE_TYPES)
    omitted_sources = sorted(spec_source_types & OMITTED_SOURCE_TYPES)
    if known_extra_sources:
        report.note("  extra (native extension): " + ", ".join(known_extra_sources))
    if omitted_sources:
        report.note("  omitted: " + ", ".join(omitted_sources))
    if extra_sources:
        report.error("unexpected extra source types: " + ", ".join(extra_sources))
    if missing_sources:
        report.error("missing source types: " + ", ".join(missing_sources))
    if not extra_sources and not missing_sources:
        report.note("  spec types: all present")

    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--spec",
        type=pathlib.Path,
        help="Path to a v8.json. Fetches the published latest copy when omitted.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit 1 when a layer type, property, source type, or native table "
        "row is out of date.",
    )
    args = parser.parse_args(argv)

    spec, origin = load_spec(args.spec)
    report = audit(spec)
    print(f"Style spec: {origin}")
    print()
    print("\n".join(report.lines))
    if args.check and report.errors:
        print(file=sys.stderr)
        print(
            "error: style API is behind the spec. See "
            ".agents/skills/style-spec-parity/SKILL.md.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
