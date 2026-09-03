"""Compare this repository's style API with the pinned style spec release.

The spec is one document. Each engine implements a versioned slice of it.
This catalog asks: for the engines this repository pins, is every in-scope
layer type and paint or layout property written in the right source set,
with the matching paint or layout setter?
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request
from typing import Any, Literal

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC_URL_TEMPLATE = (
    "https://raw.githubusercontent.com/maplibre/maplibre-style-spec/"
    "v{version}/src/reference/v8.json"
)
VERSIONS_TOML = pathlib.Path("gradle/libs.versions.toml")
MODULE = pathlib.Path("lib/maplibre-compose/src")
NATIVE_BINDING = pathlib.Path(
    "lib/maplibre-compose/src/maplibreNativeMain/kotlin/"
    "org/maplibre/compose/style/MlnFfiStyleBinding.kt"
)

LAYER_TYPE = re.compile(r'override val type: String = "([^"]+)"')
PROPERTY_WRITE = re.compile(
    r'set(?P<kind>Layout|Paint|Root)Property\(\s*"(?P<name>[^"]+)"'
)
TRANSITION_WRITE = re.compile(r'setPaintTransition\(\s*"(?P<name>[^"]+)"')
ANY_WRITE = re.compile(
    r'set(?:Layout|Paint|Root)Property\(\s*"[^"]+"|setPaintTransition\(\s*"[^"]+"'
)
UNSUPPORTED_PAIR = re.compile(r'\("([^"]+)"\s+to\s+"([^"]+)"\)')
TRANSITION_SUFFIX = "-transition"
SOURCE_TYPE_WRITE = re.compile(r'put\("type",\s*"([^"]+)"\)')
FUN_DECLARATION = re.compile(r"\bfun\s+(\w+)\s*\(")
CLASS_DECLARATION = re.compile(r"\bclass\s+(\w+)")
TOML_STRING = re.compile(r'^([A-Za-z0-9_-]+)\s*=\s*"([^"]+)"')
VERSION_STRING = re.compile(r"^(\d+)\.(\d+)(?:\.(\d+))?(?:[-+][0-9A-Za-z.-]+)?$")

Kind = Literal["layout", "paint", "root"]
PaintOrLayout = Literal["layout", "paint"]
Engine = Literal["js", "native"]
PropKey = tuple[str, PaintOrLayout, str]
LayerWrites = tuple[list[tuple[Kind, str]], list[str]]

ROOT_KEYS = frozenset(
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

# Spec paint/layout key this API satisfies by writing a different key.
ALIASES: dict[PropKey, PropKey] = {
    ("raster", "paint", "resampling"): ("raster", "paint", "raster-resampling"),
}

# Types this API exposes that the published spec does not list.
EXTRA_LAYER_TYPES = frozenset({"location-indicator"})
EXTRA_SOURCE_TYPES = frozenset({"custom-geometry"})

# Spec source types this API does not construct.
OMITTED_SOURCE_TYPES = frozenset({"video"})

# Style-root objects the imperative style API writes as typed Kotlin classes in
# `style/<Name>.kt`. `transition` is typed by `TransitionOptions` and audited by
# hand; `terrain` is not yet exposed.
ROOT_OBJECTS = ("light", "sky", "projection", "terrain")
OMITTED_ROOT_OBJECTS = frozenset({"terrain"})
STYLE_DIR = MODULE / "commonMain/kotlin/org/maplibre/compose/style"
ROOT_OBJECT_WRITE = re.compile(r'putExpression\(\s*"(?P<name>[^"]+)"')


class Version:
    """A dotted release number from sdk-support or a pin."""

    def __init__(self, major: int, minor: int, patch: int) -> None:
        self.major = major
        self.minor = minor
        self.patch = patch

    @classmethod
    def parse(cls, value: object) -> Version | None:
        """Parse a release. Issue URLs and other non-versions return None."""
        if not isinstance(value, str):
            return None
        match = VERSION_STRING.fullmatch(value)
        if match is None:
            return None
        return cls(int(match[1]), int(match[2]), int(match[3] or 0))

    def __le__(self, other: Version) -> bool:
        return (self.major, self.minor, self.patch) <= (
            other.major,
            other.minor,
            other.patch,
        )

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Version):
            return NotImplemented
        return (self.major, self.minor, self.patch) == (
            other.major,
            other.minor,
            other.patch,
        )

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


class Pins:
    """Engine and spec releases this repository ships."""

    def __init__(
        self,
        js: Version,
        native_ffi: str | None = None,
        spec: Version | None = None,
    ) -> None:
        self.js = js
        self.native_ffi = native_ffi
        self.spec = spec

    @classmethod
    def from_toml(cls, text: str) -> Pins:
        """Read the MapLibre pins from a `[versions]` catalog."""
        values = _toml_versions(text)
        js = Version.parse(values.get("maplibre-js"))
        if js is None:
            raise SystemExit(
                "error: gradle/libs.versions.toml has no maplibre-js version"
            )
        spec = Version.parse(values.get("maplibre-styleSpec"))
        if spec is None:
            raise SystemExit(
                "error: gradle/libs.versions.toml has no maplibre-styleSpec version"
            )
        return cls(js=js, native_ffi=values.get("maplibre-nativeFfi"), spec=spec)


class SpecProperty:
    """One paint or layout property and the engines that implement it."""

    def __init__(
        self, layer: str, kind: PaintOrLayout, name: str, entry: dict[str, Any]
    ) -> None:
        self.layer = layer
        self.kind = kind
        self.name = name
        self.entry = entry

    @property
    def key(self) -> PropKey:
        return (self.layer, self.kind, self.name)

    def engines(self, pins: Pins) -> set[Engine]:
        """Engines whose pinned release implements this property."""
        basic = (self.entry.get("sdk-support") or {}).get("basic functionality") or {}
        found: set[Engine] = set()
        js = Version.parse(basic.get("js"))
        if js is not None and js <= pins.js:
            found.add("js")
        if (
            Version.parse(basic.get("android")) is not None
            or Version.parse(basic.get("ios")) is not None
        ):
            # The FFI pin is a date stamp, not an Android or iOS SDK version, so
            # a recorded native release counts as implemented. The pinned spec
            # release keeps this from running ahead of the shipped engines.
            found.add("native")
        return found


class KotlinApi:
    """Layer types and property writes collected from every Main source set."""

    def __init__(self) -> None:
        self.types: dict[str, set[Engine]] = {}
        self.writes: dict[tuple[str, Kind, str], set[Engine]] = {}
        self.transitions: dict[tuple[str, str], set[Engine]] = {}
        self.source_types: dict[str, set[Engine]] = {}

    def add_type(self, name: str, engines: set[Engine]) -> None:
        self.types.setdefault(name, set()).update(engines)

    def add_write(
        self, layer: str, kind: Kind, name: str, engines: set[Engine]
    ) -> None:
        self.writes.setdefault((layer, kind, name), set()).update(engines)

    def add_transition(self, layer: str, name: str, engines: set[Engine]) -> None:
        self.transitions.setdefault((layer, name), set()).update(engines)

    def writers(self, layer: str, kind: Kind, name: str) -> set[Engine]:
        return set(self.writes.get((layer, kind, name), ()))

    def transition_writers(self, layer: str, name: str) -> set[Engine]:
        return set(self.transitions.get((layer, name), ()))

    def kinds_written(self, layer: str, name: str) -> dict[Kind, set[Engine]]:
        return {
            kind: set(engines)
            for (found_layer, kind, found_name), engines in self.writes.items()
            if found_layer == layer and found_name == name
        }


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


def _toml_versions(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    in_versions = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            in_versions = stripped == "[versions]"
            continue
        if not in_versions:
            continue
        match = TOML_STRING.match(stripped)
        if match:
            values[match[1]] = match[2]
    return values


def load_pins(root: pathlib.Path) -> Pins:
    """Pins from this repository's version catalog."""
    return Pins.from_toml((root / VERSIONS_TOML).read_text())


def load_spec(path: pathlib.Path | None, pins: Pins) -> tuple[dict[str, Any], str]:
    """Load v8.json from [path], or fetch the pinned spec release."""
    if path is not None:
        return json.loads(path.read_text()), str(path)
    url = SPEC_URL_TEMPLATE.format(version=pins.spec)
    request = urllib.request.Request(
        url, headers={"User-Agent": "maplibre-compose-style-spec-parity"}
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read()
    except urllib.error.URLError as error:
        raise SystemExit(f"error: could not fetch {url}: {error}") from error
    return json.loads(payload), url


def spec_layer_types(spec: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Map each spec layer type to its type-level metadata."""
    return dict(spec["layer"]["type"]["values"])


def spec_properties(spec: dict[str, Any]) -> dict[PropKey, SpecProperty]:
    """Map (layer, kind, name) to each paint and layout property."""
    found: dict[PropKey, SpecProperty] = {}
    for layer_type in spec_layer_types(spec):
        for kind in ("layout", "paint"):
            table = spec.get(f"{kind}_{layer_type}") or {}
            for name, entry in table.items():
                if name in ROOT_KEYS or not isinstance(entry, dict):
                    continue
                prop = SpecProperty(layer_type, kind, name, entry)
                found[prop.key] = prop
    return found


def spec_sources(spec: dict[str, Any]) -> set[str]:
    """Style-spec source type names."""
    names: set[str] = set()
    for table in spec["source"]:
        if table.startswith("source_"):
            names.add(table.removeprefix("source_").replace("_", "-"))
    return names


def spec_type_engines(entry: dict[str, Any], pins: Pins) -> set[Engine]:
    """Engines that implement a layer type at the pinned releases."""
    return SpecProperty("layer", "layout", "type", entry).engines(pins)


def source_set_engines(source_set: str) -> set[Engine] | None:
    """Engines a Kotlin Main source set compiles for. Test sets are ignored."""
    if not source_set.endswith("Main"):
        return None
    if source_set == "commonMain":
        return {"js", "native"}
    if source_set == "jsMain":
        return {"js"}
    return {"native"}


def _kotlin_files(
    root: pathlib.Path, package: str
) -> list[tuple[set[Engine], pathlib.Path]]:
    """Main-source-set Kotlin files under a compose package."""
    found: list[tuple[set[Engine], pathlib.Path]] = []
    base = root / MODULE
    if not base.is_dir():
        return found
    for source_set_dir in sorted(base.iterdir()):
        engines = source_set_engines(source_set_dir.name)
        if engines is None:
            continue
        package_dir = (
            source_set_dir / "kotlin" / "org" / "maplibre" / "compose" / package
        )
        if not package_dir.is_dir():
            continue
        for path in sorted(package_dir.rglob("*.kt")):
            if path.name.startswith("Unknown"):
                continue
            found.append((engines, path))
    return found


def _writes_in(text: str) -> list[tuple[Kind, str]]:
    return [
        (match["kind"].lower(), match["name"])  # type: ignore[return-value]
        for match in PROPERTY_WRITE.finditer(text)
    ]


def _transitions_in(text: str) -> list[str]:
    return [match["name"] for match in TRANSITION_WRITE.finditer(text)]


def scan_layers(root: pathlib.Path) -> KotlinApi:
    """Collect layer types, property writes, and transition writes."""
    api = KotlinApi()
    shared: list[tuple[set[Engine], LayerWrites]] = []
    typed: list[tuple[set[Engine], list[str], LayerWrites]] = []

    for engines, path in _kotlin_files(root, "layers"):
        text = path.read_text()
        types = LAYER_TYPE.findall(text)
        writes = (_writes_in(text), _transitions_in(text))
        if types:
            typed.append((engines, types, writes))
        else:
            shared.append((engines, writes))

    for engines, types, _ in typed:
        for layer_type in types:
            api.add_type(layer_type, engines)

    def record(layer_type: str, engines: set[Engine], writes: LayerWrites) -> None:
        properties, transitions = writes
        for kind, name in properties:
            api.add_write(layer_type, kind, name, engines)
        for name in transitions:
            api.add_transition(layer_type, name, engines)

    for engines, writes in shared:
        for layer_type, type_engines in api.types.items():
            if engines <= type_engines:
                record(layer_type, engines, writes)

    for engines, types, writes in typed:
        for layer_type in types:
            record(layer_type, engines, writes)
    return api


def scan_sources(root: pathlib.Path) -> dict[str, set[Engine]]:
    """Source types this API constructs, per engine of the Main source set."""
    found: dict[str, set[Engine]] = {}
    for engines, path in _kotlin_files(root, "sources"):
        text = path.read_text()
        names = set(SOURCE_TYPE_WRITE.findall(text))
        for name in names:
            found.setdefault(name, set()).update(engines)
    return found


def scan_root_objects(root: pathlib.Path) -> dict[str, set[str]]:
    """Map each style-root object to the spec property names its class writes."""
    found: dict[str, set[str]] = {}
    for name in ROOT_OBJECTS:
        path = root / STYLE_DIR / f"{name.capitalize()}.kt"
        if not path.is_file():
            continue
        found[name] = {
            match.group("name")
            for match in ROOT_OBJECT_WRITE.finditer(path.read_text())
        }
    return found


def dead_setters(root: pathlib.Path) -> list[str]:
    """Property-writing functions that no other layers code calls.

    A property write proves nothing when the function around it is never
    called. Setter names repeat across layer classes, so a call in another
    file counts only when that file also names the declaring class: a
    subclass names the superclass whose setter it calls, and a
    platform-specific composable names the internal class it constructs.
    Writes outside a named function, such as the root-property accessors on
    the base `Layer` class, are out of scope.
    """
    files = [(path, path.read_text()) for _, path in _kotlin_files(root, "layers")]
    dead: set[str] = set()
    for path, text in files:
        declarations = [
            (match.start(), match[1]) for match in FUN_DECLARATION.finditer(text)
        ]
        for write in ANY_WRITE.finditer(text):
            enclosing = None
            for start, name in declarations:
                if start >= write.start():
                    break
                enclosing = name
            if enclosing is None:
                continue
            owner = None
            for declaration in CLASS_DECLARATION.finditer(text):
                if declaration.start() >= write.start():
                    break
                owner = declaration[1]
            call = re.compile(rf"(?<!fun )\b{enclosing}\s*\(")
            reachable = any(
                call.search(other_text)
                and (
                    other_path == path
                    or (owner and re.search(rf"\b{owner}\b", other_text))
                )
                for other_path, other_text in files
            )
            if not reachable:
                dead.add(f"{path.name}:{enclosing}")
    return sorted(dead)


def native_unsupported(root: pathlib.Path) -> set[tuple[str, str]]:
    """Layer properties the native binding refuses to write."""
    path = root / NATIVE_BINDING
    if not path.is_file():
        return set()
    return set(UNSUPPORTED_PAIR.findall(path.read_text()))


def _format_engines(engines: set[Engine]) -> str:
    return (
        "+".join(engine for engine in ("js", "native") if engine in engines) or "none"
    )


def _label(layer: str, kind: str, name: str) -> str:
    return f"{layer} {kind} {name}"


def _resolved_key(prop: SpecProperty) -> PropKey:
    return ALIASES.get(prop.key, prop.key)


def audit(
    spec: dict[str, Any],
    root: pathlib.Path = ROOT,
    pins: Pins | None = None,
) -> Audit:
    pins = pins or load_pins(root)
    report = Audit()
    types = spec_layer_types(spec)
    properties = spec_properties(spec)
    api = scan_layers(root)
    api.source_types = scan_sources(root)
    unsupported = native_unsupported(root)

    report.note(f"Pinned MapLibre GL JS: {pins.js}")
    if pins.native_ffi:
        report.note(
            f"Pinned maplibre-native-ffi: {pins.native_ffi} "
            "(native sdk-support is a recorded release, not a version comparison)"
        )

    _audit_layer_types(report, types, api, pins)
    _audit_properties(report, properties, api, pins)
    _audit_transitions(report, properties, api, unsupported, pins)
    _audit_native_table(report, properties, api, unsupported, pins)
    _audit_sources(report, spec, api)
    _audit_root_objects(report, spec, scan_root_objects(root), pins)
    _audit_setters(report, root)
    return report


def _audit_layer_types(
    report: Audit,
    types: dict[str, dict[str, Any]],
    api: KotlinApi,
    pins: Pins,
) -> None:
    report.note("Layer types")
    extra_layers = sorted(set(api.types) - set(types) - EXTRA_LAYER_TYPES)
    known_extra = sorted(set(api.types) & EXTRA_LAYER_TYPES)
    missing = [
        f"{layer_type} ({_format_engines(spec_type_engines(entry, pins) - api.types.get(layer_type, set()))})"
        for layer_type, entry in sorted(types.items())
        if spec_type_engines(entry, pins) - api.types.get(layer_type, set())
    ]
    if known_extra:
        report.note("  extra (native extension): " + ", ".join(known_extra))
    if extra_layers:
        report.error("unexpected extra layer types: " + ", ".join(extra_layers))
    if missing:
        report.error("missing layer types: " + ", ".join(missing))
    if not extra_layers and not missing:
        report.note("  spec types: all present at the pinned engines")


def _audit_properties(
    report: Audit,
    properties: dict[PropKey, SpecProperty],
    api: KotlinApi,
    pins: Pins,
) -> None:
    report.note("Layer properties")
    beyond_pin: list[str] = []
    js_only: list[str] = []
    inert_on_js: list[str] = []
    aliases: list[str] = []
    property_errors = 0

    for prop in sorted(properties.values(), key=lambda item: item.key):
        required = prop.engines(pins)
        target = _resolved_key(prop)
        written = api.writers(*target)
        if prop.key != target and written:
            aliases.append(f"{prop.layer} {prop.name} -> {target[2]}")
        if "js" in written and "js" not in required:
            # The pinned spec is the one this GL JS release bundles, so GL JS
            # parses and stores the property; it renders nothing until a later
            # release implements it.
            inert_on_js.append(_label(*prop.key))
        if not required:
            beyond_pin.append(_label(*prop.key))
            continue
        if required == {"js"} and "js" in written and prop.key == target:
            js_only.append(_label(*prop.key))

        other_kinds = [
            kind
            for kind, engines in api.kinds_written(prop.layer, prop.name).items()
            if kind not in {target[1], "root"} and engines
        ]
        if other_kinds and "js" not in written and "native" not in written:
            report.error(
                f"{_label(*prop.key)} written as {other_kinds[0]}, "
                f"spec says {prop.kind}"
            )
            property_errors += 1
            continue

        missing = required - written
        if missing:
            report.error(f"{_label(*prop.key)} missing on {_format_engines(missing)}")
            property_errors += 1

    extras = _extra_properties(properties, api)
    if extras:
        report.error("unexpected extra properties: " + ", ".join(extras))
        property_errors += 1
    for line in aliases:
        report.note(f"  alias: {line}")
    if js_only:
        report.note("  js-only at the pins (exposed): " + ", ".join(js_only))
    if inert_on_js:
        report.note(
            "  native-only at the pins (stored but not rendered on js): "
            + ", ".join(inert_on_js)
        )
    if beyond_pin:
        report.note("  beyond the JS pin (not required): " + ", ".join(beyond_pin))
    if property_errors == 0:
        report.note("  spec properties: complete at the pinned engines")


def _audit_transitions(
    report: Audit,
    properties: dict[PropKey, SpecProperty],
    api: KotlinApi,
    unsupported: set[tuple[str, str]],
    pins: Pins,
) -> None:
    report.note("Layer transitions")
    beyond_pin: list[str] = []
    filtered: list[str] = []
    transition_errors = 0

    for prop in sorted(properties.values(), key=lambda item: item.key):
        if prop.kind != "paint" or not prop.entry.get("transition"):
            continue
        required = prop.engines(pins)
        if not required:
            beyond_pin.append(f"{_label(*prop.key)}{TRANSITION_SUFFIX}")
            continue
        layer, _, name = _resolved_key(prop)
        if (layer, name) in unsupported:
            required = required - {"native"}
            filtered.append(f"{layer}.{name}{TRANSITION_SUFFIX}")
        missing = required - api.transition_writers(layer, name)
        if missing:
            report.error(
                f"{layer} paint {name}{TRANSITION_SUFFIX} "
                f"missing on {_format_engines(missing)}"
            )
            transition_errors += 1

    extras = _extra_transitions(properties, api)
    if extras:
        report.error("unexpected extra transitions: " + ", ".join(extras))
        transition_errors += 1
    if filtered:
        report.note("  filtered on native: " + ", ".join(filtered))
    if beyond_pin:
        report.note("  beyond the JS pin (not required): " + ", ".join(beyond_pin))
    if transition_errors == 0:
        report.note("  spec transitions: complete at the pinned engines")


def _extra_transitions(
    properties: dict[PropKey, SpecProperty], api: KotlinApi
) -> list[str]:
    spec_layers = {layer for layer, _, _ in properties}
    # Resolved the same way as the required pass, so a transition this API writes
    # under an alias is not required under one name and flagged under the other.
    expected = {
        (resolved[0], resolved[2])
        for prop in properties.values()
        if prop.kind == "paint" and prop.entry.get("transition")
        for resolved in [_resolved_key(prop)]
    }
    extras: list[str] = []
    for (layer, name), engines in sorted(api.transitions.items()):
        if layer not in spec_layers:
            continue
        if (layer, name) in expected:
            continue
        extras.append(
            f"{layer} paint {name}{TRANSITION_SUFFIX} ({_format_engines(engines)})"
        )
    return extras


def _extra_properties(
    properties: dict[PropKey, SpecProperty], api: KotlinApi
) -> list[str]:
    spec_layers = {layer for layer, _, _ in properties}
    spec_keys = set(properties) | set(ALIASES.values())
    extras: list[str] = []
    for (layer, kind, name), engines in sorted(api.writes.items()):
        if layer not in spec_layers:
            continue
        if (layer, kind, name) in spec_keys:
            continue
        if name in ROOT_KEYS or kind == "root":
            continue
        extras.append(f"{_label(layer, kind, name)} ({_format_engines(engines)})")
    return extras


def _audit_native_table(
    report: Audit,
    properties: dict[PropKey, SpecProperty],
    api: KotlinApi,
    unsupported: set[tuple[str, str]],
    pins: Pins,
) -> None:
    report.note("Native unsupported table")
    by_name = {(prop.layer, prop.name): prop for prop in properties.values()}
    listed: list[str] = []
    missing_rows: list[str] = []
    stale: list[str] = []

    for prop in sorted(properties.values(), key=lambda item: item.key):
        if ALIASES.get(prop.key):
            continue
        required = prop.engines(pins)
        written = api.writers(*prop.key)
        in_table = (prop.layer, prop.name) in unsupported
        if "native" in required and in_table:
            stale.append(f"{prop.layer}.{prop.name}")
        elif "native" not in required and "js" in required and "native" in written:
            if in_table:
                listed.append(f"{prop.layer}.{prop.name}")
            else:
                missing_rows.append(f"{prop.layer}.{prop.name}")

    extra_rows = sorted(
        f"{layer}.{name}" for layer, name in unsupported if (layer, name) not in by_name
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
        report.note("  table matches pinned support")


def _audit_root_objects(
    report: Audit,
    spec: dict[str, Any],
    written: dict[str, set[str]],
    pins: Pins,
) -> None:
    report.note("Style-root objects")
    for name in ROOT_OBJECTS:
        table = spec.get(name)
        if not isinstance(table, dict):
            continue
        if name in OMITTED_ROOT_OBJECTS:
            report.note(f"  {name}: omitted")
            continue
        properties = {
            prop: SpecProperty(name, "paint", prop, entry)
            for prop, entry in table.items()
            if isinstance(entry, dict)
        }
        writes = written.get(name)
        if writes is None:
            report.error(f"{name}: no {name.capitalize()}.kt in {STYLE_DIR}")
            continue
        missing = sorted(
            f"{prop} ({_format_engines(spec_prop.engines(pins))})"
            for prop, spec_prop in properties.items()
            if prop not in writes and spec_prop.engines(pins)
        )
        extra = sorted(writes - properties.keys())
        if missing:
            report.error(f"{name}: missing " + ", ".join(missing))
        if extra:
            report.error(f"{name}: unexpected extra " + ", ".join(extra))
        if not missing and not extra:
            report.note(f"  {name}: all present")


def _audit_setters(report: Audit, root: pathlib.Path) -> None:
    report.note("Setter reachability")
    dead = dead_setters(root)
    if dead:
        report.error("property setters nothing calls: " + ", ".join(dead))
    else:
        report.note("  every property setter is called")


def _audit_sources(report: Audit, spec: dict[str, Any], api: KotlinApi) -> None:
    report.note("Source types")
    spec_source_types = spec_sources(spec)
    constructed = set(api.source_types)
    extra_sources = sorted(constructed - spec_source_types - EXTRA_SOURCE_TYPES)
    missing_sources = [
        f"{name} ({_format_engines({'js', 'native'} - api.source_types.get(name, set()))})"
        for name in sorted(spec_source_types - OMITTED_SOURCE_TYPES)
        if {"js", "native"} - api.source_types.get(name, set())
    ]
    known_extra = sorted(constructed & EXTRA_SOURCE_TYPES)
    omitted = sorted(spec_source_types & OMITTED_SOURCE_TYPES)
    if known_extra:
        report.note("  extra (native extension): " + ", ".join(known_extra))
    if omitted:
        report.note("  omitted: " + ", ".join(omitted))
    if extra_sources:
        report.error("unexpected extra source types: " + ", ".join(extra_sources))
    if missing_sources:
        report.error("missing source types: " + ", ".join(missing_sources))
    if not extra_sources and not missing_sources:
        report.note("  spec types: all present")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--spec",
        type=pathlib.Path,
        help="Path to a v8.json. Fetches the pinned maplibre-styleSpec release "
        "when omitted.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit 1 when an in-scope layer type, property, source type, or "
        "native table row is out of date.",
    )
    args = parser.parse_args(argv)

    pins = load_pins(ROOT)
    spec, origin = load_spec(args.spec, pins)
    report = audit(spec, pins=pins)
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
