---
name: style-spec-parity
description: Align the MapLibre Compose style API with the published style spec. Use when adding layer or source properties, when a platform implements a property the other does not, after bumping MapLibre GL JS or maplibre-native-ffi, or when working on style-spec parity.
---

# Style spec parity

The style spec is one document. MapLibre GL JS and MapLibre Native implement it
at their own pace. The public API stays common: a property one engine lacks is
still declared, and that engine's `StyleBinding` keeps it out of every write.

`mise run style-spec:parity` reads the published
[`v8.json`](https://github.com/maplibre/maplibre-style-spec/blob/main/src/reference/v8.json)
and compares it with the layer and source types this repository writes.

```sh
mise run style-spec:parity
mise run style-spec:parity -- --check
mise run style-spec:parity -- --spec /path/to/v8.json
```

`--check` fails when a spec layer type, source type, or paint/layout property is
missing from the API, or when the native unsupported table disagrees with
`sdk-support`.

Types the spec does not list, spec types this API does not construct, and spec
properties this API writes under another name belong in the extra, omitted, and
alias sets in `ci/style_spec_parity.py`. Read those sets and the catalog for the
current list. Do not copy them here.

## Read the catalog

Each spec property's `sdk-support.basic functionality` field is a version string
on engines that implement it, and an issue URL on engines that do not.

- **js and native both have a version.** Write the property in `commonMain`.
- **js has a version, native has an issue URL.** Write it in `commonMain` and
  list it on the native binding so a write cannot refuse the whole layer.
- **native has a version, js does not.** Write the layer type or property only
  in `maplibreNativeMain`, the way `LocationIndicatorLayer` does.

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

## Add a property one engine lacks

Keep the setter in `commonMain`. The binding decides what reaches the engine.

1. Default the composable parameter to `nil()`. A spec default that is always
   written would log an unsupported warning on every layer of that type.
2. Document the gap on the parameter, with the upstream issue link from
   `sdk-support`.
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

When a later native or GL JS release implements the property, delete the table
row and move the test from the `glJsOnly*` list into the shared cases.

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

Never pass `--tests` to the Gradle browser suite: it silently runs nothing and
reports success.
