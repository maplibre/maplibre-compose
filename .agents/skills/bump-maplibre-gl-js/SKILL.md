---
name: bump-maplibre-gl-js
description: Update the hand-written MapLibre GL JS bindings after bumping the pinned maplibre-gl version. Use when changing `maplibre-js` in gradle/libs.versions.toml, or when the browser platform breaks against a new MapLibre GL JS release.
---

# Bumping MapLibre GL JS

The bindings in
`lib/maplibre-compose/src/jsMain/kotlin/org/maplibre/compose/gljs/` are written
by hand, are `internal`, and cover only the members this platform actually
calls. Most of an upstream diff is therefore irrelevant; the job is to find the
parts that touch that subset.

## 1. Keep the old declarations

```sh
cp build/js/node_modules/maplibre-gl/dist/maplibre-gl.d.ts /tmp/maplibre-gl.old.d.ts
cp -R build/js/node_modules/maplibre-gl/src /tmp/maplibre-gl-src.old
```

The npm package ships both its `.d.ts` and its TypeScript sources, and step 5
needs the old sources too.

## 2. Bump and reinstall

Edit `maplibre-js` in `gradle/libs.versions.toml`, then:

```sh
./gradlew kotlinNpmInstall
./gradlew kotlinUpgradeYarnLock   # refreshes the committed kotlin-js-store/yarn.lock
```

## 3. Diff the declarations

```sh
diff -u /tmp/maplibre-gl.old.d.ts build/js/node_modules/maplibre-gl/dist/maplibre-gl.d.ts
```

Read the diff only for names that appear in `GlJsModule.kt` or `GlJsTypes.kt`:
renamed or removed `Map` methods, changed option fields, changed return shapes.
Update the declarations to match.

Nothing checks a Kotlin `external` declaration against the library it describes.
No test can: an `external interface` erases at runtime, so there is nothing to
enumerate, and a hand-listed mirror of the declarations only rots. Deciding the
declared set is right is this step, done by reading, and these three questions
are the whole of it:

- **Is every declared member still there, spelled the same?** For each member in
  the two files, find it in the new `.d.ts`. A rename upstream compiles fine
  here and fails at runtime with `undefined is not a function`.
- **Did a type widen or narrow?** A field that became optional, a return that
  gained `| undefined`, an argument that stopped accepting the shape passed. The
  declarations deliberately state narrower types than MapLibre's `*Like` unions
  — `LngLat` for `LngLatLike`, `Point` for `PointLike` — so check the narrowing
  still holds rather than that the signature matches.
- **Is anything declared that upstream never had?** Left over from an earlier
  version, or mistyped. Grep the `.d.ts` for it.

The declarations cover only what the platform calls, so most of the diff is
irrelevant. That is the point: the list is short enough to read.

## 4. Look for new capability worth binding

Step 3 asks whether what the platform already declares still works. This step
asks what the release adds. Read the
[changelog](https://github.com/maplibre/maplibre-gl-js/blob/main/CHANGELOG.md)
between the two versions, against three lists:

- **Gaps against the other platforms.** Anything `commonMain` declares that the
  browser answers with `NotImplementedError` or `UnsupportedOperationException`.
  A release that closes one is the reason to bind new members.
- **TODOs waiting on upstream.**
  `git grep -n TODO lib/maplibre-compose/src/jsMain` finds the ones parked
  against a MapLibre GL JS limitation.
- **New API surface.** New `Map` methods, style-spec properties, and source or
  layer types that the common API could expose.

Bind what one of those three justifies, and leave the rest undeclared. Anything
new reaches the common API through `commonMain`. Members that still differ by
backend need an `expect` and the other platforms' actuals; shared work belongs
in `commonMain` itself. Either way, the test belongs in `nextCommonTest` rather
than in a browser-only file.

## 5. Re-check the four runtime shims

This is the part the `.d.ts` diff will **not** reveal. `GlJsRuntime.kt` is
pinned to MapLibre internals, not its public API. Each shim fails loudly when
its shape moves, but only at runtime, so read the new sources rather than
waiting for the test:

| Shim                           | Upstream anchor                                                                                                  |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| `lendingContext`               | `src/ui/map.ts` — `Map` must call `canvas.getContext` exactly once, synchronously, in its constructor            |
| `redirectDefaultFramebuffer`   | `src/gl/value.ts` — `class BindFramebuffer`'s `set(v)`, whose body this replaces (`current`/`dirty`/`gl` fields) |
| `interceptRepaintRequests`     | `src/ui/map.ts` — `triggerRepaint()` must stay MapLibre's only caller of `browser.frame`                         |
| `removingWithoutLosingContext` | `src/ui/map.ts` — `remove()` must still reach the context only through `getExtension('WEBGL_lose_context')`      |

```sh
diff -ru /tmp/maplibre-gl-src.old/gl/value.ts build/js/node_modules/maplibre-gl/src/gl/value.ts
diff -ru /tmp/maplibre-gl-src.old/ui/map.ts build/js/node_modules/maplibre-gl/src/ui/map.ts
```

## 6. Verify

The browser suite drives real maps, so a declaration that no longer matches
shows up as a failure there — but only for the members those maps exercise, and
only as whatever the platform does with a wrong answer. It is not a check on the
declared set; step 3 is where that is decided.

```sh
CHROME_BIN="…" ./gradlew :lib:maplibre-compose:jsBrowserTest
```

Never pass `--tests`: it silently runs nothing and reports success. Failures are
in `lib/maplibre-compose/build/reports/tests/jsBrowserTest/**.html`. The suite
needs the machine awake, because an idle machine stalls `requestAnimationFrame`
and the tests die as timeouts instead of assertion failures.

Then `./gradlew :demo-app:common:compileKotlinJs` and `mise run check`.
