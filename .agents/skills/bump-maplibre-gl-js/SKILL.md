---
name: bump-maplibre-gl-js
description: Upgrade the pinned MapLibre GL JS release and its hand-written bindings. Use when bumping `maplibre-js` or when the browser breaks against a new release.
---

# Upgrade MapLibre GL JS

The bindings in
`lib/maplibre-compose/src/jsMain/kotlin/org/maplibre/compose/gljs/` are written
by hand, are `internal`, and cover the members this platform calls.

## 1. Keep the old declarations

```sh
upgrade_dir=$(mktemp -d)
cp lib/maplibre-compose/build/maplibre-gl-js/maplibre-gl.d.ts "$upgrade_dir/maplibre-gl.d.ts"
cp -R third_party/maplibre-gl-js/src "$upgrade_dir/src"
```

Keep the temporary path for later comparisons. If the old package is absent,
retrieve the version pinned before the upgrade.

## 2. Bump and rebuild

Update the upstream submodule pin and reconcile the carried patches as described
in [the patch workflow](../../../patches/maplibre-gl-js/README.md). Edit
`maplibre-js` and set `maplibre-styleSpec` to the spec version bundled by the
new release in `gradle/libs.versions.toml`.

Also review `maplibre-geojsonVt` and `maplibre-vtPbf` against the new release's
`@maplibre/geojson-vt` and `@maplibre/vt-pbf` dependency ranges in
`package.json`. These independent libraries encode custom geometry as standard
MVT; they do not require an exact GL JS version match. Keep our pins within GL
JS's ranges as an upgrade convention, retaining them when the ranges have not
changed. Check the resolved versions in `kotlin-js-store/yarn.lock` after
installation.

Then:

```sh
mise run build:maplibre-gl-js
./gradlew kotlinNpmInstall
./gradlew kotlinUpgradeYarnLock   # refreshes the committed kotlin-js-store/yarn.lock
```

## 3. Diff the declarations

```sh
diff -u "$upgrade_dir/maplibre-gl.d.ts" lib/maplibre-compose/build/maplibre-gl-js/maplibre-gl.d.ts
```

Read the diff only for names that appear in `GlJsModule.kt` or `GlJsTypes.kt`:
renamed or removed `Map` methods, changed option fields, changed return shapes.
Update the declarations to match.

When either tile library changes, compare `geoJSONToTile` and `fromGeojsonVt`
with `GlJsVectorTiles.kt` and `GlJsVectorTilePbf.kt`. Check their option fields
and the tile shape passed between them by `GlJsCustomGeometryAttachment`.

Kotlin compilation does not check `external` declarations against upstream
TypeScript, and runtime tests cover only the members they exercise, so an
upstream rename compiles here and fails at runtime with
`undefined is not a function`. Check every declared member against the new
`.d.ts`: it still exists under the same name, and its type has not widened or
narrowed (a field became optional, a return gained `| undefined`). The
declarations deliberately use narrower types than MapLibre's `*Like` unions,
such as `LngLat` for `LngLatLike` and `Point` for `PointLike`; confirm those
still hold.

## 4. Look for new capability worth binding

Read the
[changelog](https://github.com/maplibre/maplibre-gl-js/blob/main/CHANGELOG.md)
between the two versions for:

- **Gaps against the other platforms.** Anything `commonMain` declares that the
  browser answers with `NotImplementedError` or `UnsupportedOperationException`.
  A release that closes one is the reason to bind new members.
- **TODOs waiting on upstream.**
  `git grep -n TODO lib/maplibre-compose/src/jsMain` finds the ones parked
  against a MapLibre GL JS limitation.
- **New APIs.** New `Map` methods, style-spec properties, and source or layer
  types that the common API could expose. Style-spec gaps go through the
  `style-spec-parity` skill.

An upgrade includes adopting useful new capabilities from these categories,
unless the user requested only a version or compatibility update. Bind members
that serve the library and leave unused upstream APIs undeclared. Ask about a
new capability when it requires a product or public API decision that the
request and existing conventions do not settle.

Shared APIs belong in `commonMain`; engine-specific layer types follow the
`style-spec-parity` skill. Shared behavior tests belong in `liveMapTest`.
Browser-only implementation tests belong in `jsTest`.

## 5. Re-check the runtime shims

`GlJsRuntime.kt` and `GlJsStyleBinding.setTransition` depend on MapLibre
internals. Compare the upstream sources to verify these assumptions:

| Shim                           | Upstream anchor                                                                                                                                                                                       |
| ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lendingContext`               | `src/ui/map.ts`. `Map` must call `canvas.getContext` exactly once, synchronously, in its constructor                                                                                                  |
| `redirectDefaultFramebuffer`   | `src/gl/value.ts`. `class BindFramebuffer`'s `set(v)`, whose body this replaces (`current`/`dirty`/`gl` fields)                                                                                       |
| `interceptRepaintRequests`     | `src/ui/map.ts`. `triggerRepaint()` must stay MapLibre's only caller of `browser.frame`                                                                                                               |
| `removingWithoutLosingContext` | `src/ui/map.ts`. `remove()` must still reach the context only through `getExtension('WEBGL_lose_context')`                                                                                            |
| `setTransition`                | `src/style/style.ts`. `getTransition()` must still read `this.stylesheet.transition`; if `setTransition` in `_getOperationsToPerform` stops being a no-op, MapLibre has a real setter to call instead |

```sh
diff -u "$upgrade_dir/src/gl/value.ts" third_party/maplibre-gl-js/src/gl/value.ts
diff -u "$upgrade_dir/src/ui/map.ts" third_party/maplibre-gl-js/src/ui/map.ts
diff -u "$upgrade_dir/src/style/style.ts" third_party/maplibre-gl-js/src/style/style.ts
```

Custom geometry also relies on public `addProtocol` and
`VectorTileSource.setTiles` behavior. Check request cancellation and source
reload semantics when these APIs change; the browser geometry tests cover
deferred invalidation and provider failure recovery.

## 6. Verify

```sh
mise run test:js
./gradlew :demo-app:common:compileKotlinJs
mise run check
```

Browser test reports are in
`lib/maplibre-compose/build/reports/tests/jsBrowserTest/`. Verify adopted style
capabilities through `style-spec-parity`, and run tests on other platforms when
shared behavior changes.
