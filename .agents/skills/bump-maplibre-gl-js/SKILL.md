---
name: bump-maplibre-gl-js
description: Update the hand-written MapLibre GL JS bindings after bumping the pinned maplibre-gl version. Use when changing `maplibre-js` in gradle/libs.versions.toml, or when the browser platform breaks against a new MapLibre GL JS release.
---

# Upgrade MapLibre GL JS

The bindings in
`lib/maplibre-compose/src/jsMain/kotlin/org/maplibre/compose/gljs/` are written
by hand, are `internal`, and cover the members this platform calls.

## 1. Keep the old declarations

```sh
upgrade_dir=$(mktemp -d)
cp build/js/node_modules/maplibre-gl/dist/maplibre-gl.d.ts "$upgrade_dir/maplibre-gl.d.ts"
cp -R build/js/node_modules/maplibre-gl/src "$upgrade_dir/src"
```

Keep the temporary path for later comparisons. If the old package is absent,
retrieve the version pinned before the upgrade.

## 2. Bump and reinstall

Edit `maplibre-js` and set `maplibre-styleSpec` to the spec version bundled by
the new release in `gradle/libs.versions.toml`, then:

```sh
./gradlew kotlinNpmInstall
./gradlew kotlinUpgradeYarnLock   # refreshes the committed kotlin-js-store/yarn.lock
```

## 3. Diff the declarations

```sh
diff -u "$upgrade_dir/maplibre-gl.d.ts" build/js/node_modules/maplibre-gl/dist/maplibre-gl.d.ts
```

Read the diff only for names that appear in `GlJsModule.kt` or `GlJsTypes.kt`:
renamed or removed `Map` methods, changed option fields, changed return shapes.
Update the declarations to match.

Kotlin compilation does not validate `external` declarations against upstream
TypeScript. Compare the declared members with the new `.d.ts`; runtime tests
cover only the members they exercise.

- **Is every declared member still there, spelled the same?** For each member in
  the two files, find it in the new `.d.ts`. A rename upstream compiles fine
  here and fails at runtime with `undefined is not a function`.
- **Did a type widen or narrow?** A field that became optional, a return that
  gained `| undefined`, an argument that stopped accepting the shape passed. The
  declarations deliberately state narrower types than MapLibre's `*Like` unions.
  For example, they use `LngLat` for `LngLatLike` and `Point` for `PointLike`.
  Check that these narrower types remain valid.
- **Is anything declared that upstream never had?** Left over from an earlier
  version, or mistyped. Search the `.d.ts` for it.

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
diff -u "$upgrade_dir/src/gl/value.ts" build/js/node_modules/maplibre-gl/src/gl/value.ts
diff -u "$upgrade_dir/src/ui/map.ts" build/js/node_modules/maplibre-gl/src/ui/map.ts
diff -u "$upgrade_dir/src/style/style.ts" build/js/node_modules/maplibre-gl/src/style/style.ts
```

## 6. Verify

```sh
mise run test:js
./gradlew :demo-app:common:compileKotlinJs
mise run check
```

Follow `AGENTS.md` for Chrome setup and browser test constraints. Browser test
reports are in `lib/maplibre-compose/build/reports/tests/jsBrowserTest/`. Verify
adopted style capabilities through `style-spec-parity`, and run tests on other
platforms when shared behavior changes.
