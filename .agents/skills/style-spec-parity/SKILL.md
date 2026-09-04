---
name: style-spec-parity
description: Align the MapLibre Compose style API with the published style spec. Use when adding layer or source properties, when a platform implements a property the other does not, after bumping MapLibre GL JS or maplibre-native-ffi, or when working on style-spec parity.
---

# Style spec parity

MapLibre GL JS and MapLibre Native implement the style spec at different paces.
Properties on shared layer types stay in the common API, with unsupported writes
filtered by `StyleBinding`. A whole layer type that only one engine accepts has
its composable in that engine's source set.

`mise run style-spec:parity` reads `v8.json` at the pinned
[maplibre-style-spec](https://github.com/maplibre/maplibre-style-spec) release
and compares it with every Main source set that writes a layer or source.

```sh
mise run style-spec:parity
mise run style-spec:parity -- --check
mise run style-spec:parity -- --spec /path/to/v8.json
```

`--check` fails when a layer type, source type, or paint or layout property that
the pinned engines implement is missing on an engine that implements it, written
with the other kind, or wrapped in a setter that nothing calls, or when the
native unsupported table disagrees with pinned support.

A `sdk-support` version counts only when it is at most the pin. `maplibre-js` in
`gradle/libs.versions.toml` is the GL JS pin, and `maplibre-styleSpec` pins the
spec release the catalog reads: the one the pinned maplibre-gl release depends
on, so bump both together. The FFI pin is a date stamp, so a recorded Android or
iOS release counts as native support, and the pinned spec release keeps that
from running ahead of the shipped engines. Properties that exist only in a newer
GL JS than the pin stay out of scope until that pin moves.

GL JS parses every property in the pinned spec, because that release is the one
it bundles. A property only native renders is therefore still written in
`commonMain`; the catalog lists it as stored but not rendered on js.

Types the spec does not list, spec types this API does not construct, and spec
properties this API writes under another name belong in the extra, omitted, and
alias sets in `ci/style_spec_parity.py`. Read those sets and the catalog for the
current list.

## Read the catalog

Each spec property's `sdk-support.basic functionality` field is a version string
on engines that implement it, and an issue URL on engines that do not.

- **js and native both implement it at the pins.** Write the property in
  `commonMain` with the matching `setPaintProperty` or `setLayoutProperty`.
- **js implements it, native does not.** Write it in `commonMain` and list it on
  the native binding so a write cannot refuse the whole layer.
- **Native renders a property that JS only stores.** Keep the property in
  `commonMain` and document the rendering difference. A whole layer type that JS
  cannot accept follows the separate layer-type guidance below.

## Style-root objects

The `light`, `sky`, and `projection` objects at the style root are typed classes
in `lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/style/`, one
file per object, each writing its properties with `putExpression`. The catalog
checks every spec property of those objects against the writes in that file. An
engine that lacks a whole object reports it through the `supportsSky`-style flag
on `StyleBinding` rather than the native table. `terrain` is in the omitted set
until the API exposes it.

## Add a property both engines implement

1. Add the composable parameter and a setter that calls `setLayoutProperty` or
   `setPaintProperty` with the spec name. Follow the surrounding layer. A new
   enum belongs next to the others in
   `lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/expressions/value/`.
2. Default to the spec default when writing it on native is safe. Use `nil()`
   when the property is optional and an unset value should stay absent.
3. Add a `liveMapTest` round-trip case in `LayerPropertyRoundTripTest`. Native
   and GL JS sometimes report the same value in different JSON shapes; the
   `Case` helper takes a GL JS form for that.

## Add a property native lacks

Keep the setter in `commonMain`. The binding decides what reaches the engine.

1. Default the composable parameter to `nil()`. A spec default that is always
   written would log an unsupported warning on every layer of that type.
2. Note on the parameter that it is not yet supported on native, with the issue
   link from `sdk-support`.
3. Add a row to `MlnFfiStyleBinding.UNSUPPORTED_LAYER_PROPERTIES`. The reason
   string is what the layer logs once.
4. Add the round-trip case to the `glJsOnly*` list in
   `LayerPropertyRoundTripTest`, so desktop does not assert a write native will
   skip.

`UnsupportedLayerPropertyTest` covers the drop-versus-refuse mechanism. Do not
add a case per table row.

A value one engine rejects, on a property it otherwise implements, stays out of
that table. `skipUnsupportedProperty` or the live `StyleMutationException`
handler covers a rejected value.

When a later native release implements the property, delete the table row and
move the test from the `glJsOnly*` list into the shared cases.

## Add a layer type one engine lacks

A whole type has no binding filter like a property does. Put the public
composable in the source set that has the engine:

- Native only: `maplibreNativeMain`, as `LocationIndicatorLayer` does. The
  internal `Layer` class can stay in `commonMain` so style reconstruction and
  native tests share it.
- GL JS only: `jsMain`.

The other platform's demo or helper uses `expect`/`actual` when it needs a
stand-in, the way `NativeLocationIndicator` falls back to nothing on the
browser.

## Verify

```sh
mise run style-spec:parity -- --check
mise run test:desktop
mise run test:js
mise run check
```

Run `mise run ci:test-scripts` when changing the catalog checker. For catalog or
documentation-only changes, select the relevant checks without running map
suites whose behavior is unchanged. Browser setup and test constraints are in
`AGENTS.md`.
