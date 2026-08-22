"""Compare the public style API with MapLibre style-spec metadata.

The published spec is shared. MapLibre GL JS and MapLibre Native implement it at
their own pace. This catalog lists layers, sources, and properties the spec
defines, which engines implement them, and whether this repository exposes them.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request
from collections.abc import Iterable
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]

SPEC_URL = (
    "https://raw.githubusercontent.com/maplibre/maplibre-style-spec/"
    "main/src/reference/v8.json"
)

LAYERS_DIR = ROOT / (
    "lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/layers"
)
SOURCES_DIR = ROOT / (
    "lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/sources"
)
NATIVE_BINDING = ROOT / (
    "lib/maplibre-compose/src/maplibreNativeMain/kotlin/"
    "org/maplibre/compose/style/MlnFfiStyleBinding.kt"
)

LAYER_TYPE_RE = re.compile(r'override val type: String = "([^"]+)"')
PROPERTY_WRITE_RE = re.compile(r'set(?:Layout|Paint|Root)Property\(\s*"([^"]+)"')
UNSUPPORTED_PAIR_RE = re.compile(r'\("([^"]+)"\s+to\s+"([^"]+)"\)')
SOURCE_CLASS_RE = re.compile(
    r"class (Vector|Raster|RasterDem|GeoJson|Image|Computed)Source"
)
SOURCE_PUT_RE = re.compile(r'(?:put|putJsonObject|putJsonArray)\("([^"]+)"')

# Shared by every layer type; implemented on Layer / FeatureLayer.
SHARED_LAYER_PROPERTIES = frozenset(
    {"visibility", "minzoom", "maxzoom", "filter", "source-layer"}
)

# Spec layer keys that are not paint or layout properties.
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

# Spec properties this API writes under another name.
PROPERTY_ALIASES: dict[tuple[str, str], str] = {
    ("raster", "resampling"): "raster-resampling",
}

# Layer types this API exposes that the published spec does not list.
EXTRA_LAYER_TYPES = frozenset({"location-indicator"})

# Source types this API does not construct. UnknownSource can still read them.
OMITTED_SOURCE_TYPES = frozenset({"video"})

# Spec source keys that are not part of the public options surface we track.
SOURCE_META_KEYS = frozenset({"type", "*"})

SOURCE_CLASS_TO_TYPE = {
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


def platforms_for(support: dict[str, Any] | None) -> set[str]:
    """Engines that implement basic functionality: js, native, or both."""
    basic = (support or {}).get("basic functionality") or {}
    found: set[str] = set()
    if is_version(basic.get("js")):
        found.add("js")
    if is_version(basic.get("android")) or is_version(basic.get("ios")):
        found.add("native")
    return found


def native_issue_url(support: dict[str, Any] | None) -> str | None:
    """Issue URL recorded for Android or iOS when native has no version yet."""
    basic = (support or {}).get("basic functionality") or {}
    for platform in ("android", "ios"):
        value = basic.get(platform)
        if isinstance(value, str) and value.startswith("http"):
            return value
    return None


def load_spec(path: pathlib.Path | None) -> tuple[dict[str, Any], str]:
    """Load v8.json from [path] or fetch the published latest copy."""
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


def spec_layer_types(spec: dict[str, Any]) -> dict[str, set[str]]:
    """Map each spec layer type to the engines that implement it."""
    values = spec["layer"]["type"]["values"]
    return {
        name: platforms_for(entry.get("sdk-support")) for name, entry in values.items()
    }


def spec_layer_properties(spec: dict[str, Any]) -> dict[str, dict[str, dict[str, Any]]]:
    """Map layer type -> property name -> spec entry (layout and paint)."""
    properties: dict[str, dict[str, dict[str, Any]]] = {}
    for layer_type in spec_layer_types(spec):
        combined: dict[str, dict[str, Any]] = {}
        for prefix in ("layout", "paint"):
            table = spec.get(f"{prefix}_{layer_type}") or {}
            for name, entry in table.items():
                if name in LAYER_OBJECT_KEYS or not isinstance(entry, dict):
                    continue
                combined[name] = entry
        properties[layer_type] = combined
    return properties


def spec_source_types(spec: dict[str, Any]) -> list[str]:
    """Style-spec source type names, in spec order."""
    names: list[str] = []
    for table in spec["source"]:
        if not table.startswith("source_"):
            continue
        names.append(table.removeprefix("source_").replace("_", "-"))
    return names


def spec_source_properties(spec: dict[str, Any]) -> dict[str, set[str]]:
    """Map source type -> property names the spec lists."""
    properties: dict[str, set[str]] = {}
    for table in spec["source"]:
        if not table.startswith("source_"):
            continue
        source_type = table.removeprefix("source_").replace("_", "-")
        properties[source_type] = {
            name for name in spec[table] if name not in SOURCE_META_KEYS
        }
    return properties


def scan_layer_api(root: pathlib.Path = ROOT) -> tuple[dict[str, set[str]], set[str]]:
    """Return (layer type -> written property names, extra types not in files with a type)."""
    layers_dir = root / LAYERS_DIR.relative_to(ROOT)
    by_type: dict[str, set[str]] = {}
    shared: set[str] = set(SHARED_LAYER_PROPERTIES)
    for path in sorted(layers_dir.glob("*.kt")):
        text = path.read_text()
        types = LAYER_TYPE_RE.findall(text)
        written = set(PROPERTY_WRITE_RE.findall(text))
        if not types:
            shared.update(written)
            continue
        for layer_type in types:
            by_type.setdefault(layer_type, set()).update(written)
    for written in by_type.values():
        written.update(shared)
    return by_type, set(by_type)


def scan_native_unsupported(root: pathlib.Path = ROOT) -> dict[tuple[str, str], None]:
    """Layer properties the native binding refuses to write."""
    path = root / NATIVE_BINDING.relative_to(ROOT)
    return {
        (layer_type, name): None
        for layer_type, name in UNSUPPORTED_PAIR_RE.findall(path.read_text())
    }


def scan_source_api(root: pathlib.Path = ROOT) -> tuple[set[str], set[str]]:
    """Return (source types we construct, JSON keys we write)."""
    sources_dir = root / SOURCES_DIR.relative_to(ROOT)
    types: set[str] = set()
    keys: set[str] = set()
    for path in sorted(sources_dir.glob("*.kt")):
        text = path.read_text()
        for class_name in SOURCE_CLASS_RE.findall(text):
            types.add(SOURCE_CLASS_TO_TYPE[f"{class_name}Source"])
        keys.update(SOURCE_PUT_RE.findall(text))
        if '"video"' in text:
            types.add("video")
    return types, keys


def compare(
    spec: dict[str, Any],
    root: pathlib.Path = ROOT,
) -> list[str]:
    """Human-readable catalog lines. Lines starting with `error:` are check failures."""
    layer_types = spec_layer_types(spec)
    spec_properties = spec_layer_properties(spec)
    api_properties, api_types = scan_layer_api(root)
    unsupported = scan_native_unsupported(root)
    source_types_spec = spec_source_types(spec)
    source_properties_spec = spec_source_properties(spec)
    api_source_types, api_source_keys = scan_source_api(root)

    lines: list[str] = []

    extra_types = sorted(api_types - set(layer_types) - EXTRA_LAYER_TYPES)
    missing_types = sorted(set(layer_types) - api_types)
    known_extra_types = sorted(api_types & EXTRA_LAYER_TYPES)
    lines.append("Layer types")
    if known_extra_types:
        lines.append("  extra (native extension): " + ", ".join(known_extra_types))
    if extra_types:
        lines.append("error: unexpected extra layer types: " + ", ".join(extra_types))
    if missing_types:
        lines.append("error: missing layer types: " + ", ".join(missing_types))
    if not extra_types and not missing_types:
        lines.append("  spec types: all present")

    lines.append("Layer properties")
    for layer_type in sorted(spec_properties):
        spec_names = set(spec_properties[layer_type])
        written = api_properties.get(layer_type, set())
        missing: list[str] = []
        aliased: list[str] = []
        js_only: list[str] = []
        for name in sorted(spec_names):
            alias = PROPERTY_ALIASES.get((layer_type, name))
            if alias and alias in written:
                aliased.append(f"{name} -> {alias}")
                continue
            if name not in written:
                missing.append(name)
                continue
            platforms = platforms_for(
                spec_properties[layer_type][name].get("sdk-support")
            )
            if "js" in platforms and "native" not in platforms:
                js_only.append(name)
        unexpected = sorted(
            name
            for name in written
            if name not in spec_names
            and name not in SHARED_LAYER_PROPERTIES
            and name not in LAYER_OBJECT_KEYS
        )
        if missing:
            lines.append(
                f"error: {layer_type} missing properties: " + ", ".join(missing)
            )
        if unexpected:
            lines.append(
                f"error: {layer_type} extra properties: " + ", ".join(unexpected)
            )
        if aliased:
            lines.append(f"  {layer_type} aliases: " + ", ".join(aliased))
        if js_only:
            lines.append(f"  {layer_type} js-only (exposed): " + ", ".join(js_only))
        if not missing and not unexpected and not aliased and not js_only:
            lines.append(f"  {layer_type}: complete")

    lines.append("Native unsupported table")
    stale: list[str] = []
    missing_rows: list[str] = []
    ok_rows: list[str] = []
    for layer_type, properties in sorted(spec_properties.items()):
        written = api_properties.get(layer_type, set())
        for name, entry in sorted(properties.items()):
            # Only properties this API writes under the spec name belong in the
            # native table. An alias such as raster-resampling is a different key.
            if name not in written:
                continue
            platforms = platforms_for(entry.get("sdk-support"))
            listed = (layer_type, name) in unsupported
            if "native" not in platforms and "js" in platforms and not listed:
                missing_rows.append(f"{layer_type}.{name}")
            elif "native" in platforms and listed:
                stale.append(f"{layer_type}.{name}")
            elif listed:
                issue = native_issue_url(entry.get("sdk-support"))
                ok_rows.append(
                    f"{layer_type}.{name}" + (f" ({issue})" if issue else "")
                )
    extra_rows = sorted(
        f"{layer_type}.{name}"
        for layer_type, name in unsupported
        if name not in spec_properties.get(layer_type, {})
    )
    if ok_rows:
        lines.append("  filtered on native: " + ", ".join(ok_rows))
    if missing_rows:
        lines.append(
            "error: js-only properties missing from the native table: "
            + ", ".join(missing_rows)
        )
    if stale:
        lines.append(
            "error: native table still lists properties the spec says native implements: "
            + ", ".join(stale)
        )
    if extra_rows:
        lines.append(
            "error: native table lists properties the spec does not define: "
            + ", ".join(extra_rows)
        )
    if not missing_rows and not stale and not extra_rows:
        lines.append("  table matches the spec")

    lines.append("Sources")
    extra_sources = sorted(api_source_types - set(source_types_spec))
    missing_sources = sorted(
        set(source_types_spec) - api_source_types - OMITTED_SOURCE_TYPES
    )
    omitted_sources = sorted(set(source_types_spec) & OMITTED_SOURCE_TYPES)
    if extra_sources:
        lines.append("  extra types: " + ", ".join(extra_sources))
    if omitted_sources:
        lines.append("  omitted types: " + ", ".join(omitted_sources))
    if missing_sources:
        lines.append("  missing types: " + ", ".join(missing_sources))
    for source_type, names in sorted(source_properties_spec.items()):
        if source_type in OMITTED_SOURCE_TYPES:
            continue
        present = sorted(name for name in names if name in api_source_keys)
        absent = sorted(name for name in names if name not in api_source_keys)
        if present:
            lines.append(f"  {source_type} written: " + ", ".join(present))
        if absent:
            lines.append(f"  {source_type} not written: " + ", ".join(absent))

    return lines


def problems(lines: Iterable[str]) -> list[str]:
    return [line for line in lines if line.startswith("error:")]


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
        help="Exit 1 when a layer type, layer property, or native table row is out of date.",
    )
    args = parser.parse_args(argv)

    spec, origin = load_spec(args.spec)
    lines = compare(spec)
    print(f"Style spec: {origin}")
    print()
    for line in lines:
        print(line)

    failures = problems(lines)
    if args.check and failures:
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
