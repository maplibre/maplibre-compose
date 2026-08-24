---
title: Overview
description:
  MapLibre Compose embeds interactive vector maps in your Compose Multiplatform
  apps.
---

MapLibre Compose is a [Compose Multiplatform][compose] wrapper around the
[MapLibre][maplibre] SDKs for rendering interactive maps. You can use it to add
maps to your Compose UIs on Android, iOS, Desktop, and Web.

On Android, iOS, and Desktop, the map renders with [MapLibre Native][MLN]. In
the browser, the map renders with [MapLibre GL JS][MLJS] on the Kotlin/JS
target. The Kotlin/Wasm target is not yet supported. Offline map downloads are
available on every platform except the browser.

The API is not yet stable. Expect breaking changes between minor releases while
the API design evolves.

## Next steps

- [Getting started](/maplibre-compose/getting-started/) sets up the library and
  displays a first map.
- The [live demo](/maplibre-compose/demo/) shows the library running in your
  browser, built from the [demo app][repo-demo].
- The [API reference](/maplibre-compose/api/) documents every public symbol.

[compose]: https://www.jetbrains.com/compose-multiplatform/
[maplibre]: https://maplibre.org/
[MLN]: https://github.com/maplibre/maplibre-native
[MLJS]: https://github.com/maplibre/maplibre-gl-js
[repo-demo]: https://github.com/maplibre/maplibre-compose/tree/main/demo-app
