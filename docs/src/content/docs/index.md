---
title: Overview
description:
  MapLibre Compose embeds interactive vector maps in your Compose Multiplatform
  apps.
---

## Introduction

MapLibre Compose is a [Compose Multiplatform][compose] wrapper around the
[MapLibre][maplibre] SDKs for rendering interactive maps. You can use it to add
maps to your Compose UIs on Android, iOS, Desktop, and Web.

## Usage

- [Getting Started](/maplibre-compose/getting-started/)
- [API Reference](/maplibre-compose/api/)
- [Demo App][repo-demo]

## Status

A large subset of MapLibre's features are already supported, but the full
breadth of the MapLibre SDKs is not yet covered. What is already supported may
have bugs. API stability is not yet guaranteed; we're still exploring how best
to express an interactive map API in Compose.

| Feature                                           |        Android         |          iOS           |     Desktop (JVM)      |        Web (JS)        | Web (Wasm) |
| :------------------------------------------------ | :--------------------: | :--------------------: | :--------------------: | :--------------------: | :--------: |
| Renderer                                          | [MapLibre Native][MLN] | [MapLibre Native][MLN] | [MapLibre Native][MLN] | [MapLibre GL JS][MLJS] |     ❌     |
| Load a map with HTTP resource URLs                |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Load a map with Compose resource URIs             |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Configure gestures (pan, zoom, rotate, pitch)     |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Respond to a map click or long/right click        |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Query visible map features                        |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Get, set, and animate the camera position         |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Convert between screen and geographic coordinates |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Get the currently visible region and bounding box |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Insert, remove, and replace layers                |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Configure layers with expressions                 |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Add data sources by URI or GeoJSON                |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Add images to the style                           |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Add Material 3 controls                           |           ✅           |           ✅           |           ✅           |           ✅           |     ❌     |
| Download offline regions                          |           ✅           |           ✅           |           ✅           |           ❌           |     ❌     |
| Show the user's location                          |           ✅           |           ✅           |      Linux, macOS      |           ✅           |     ❌     |
| Snapshot the map as an image                      |           ❌           |           ❌           |           ❌           |           ❌           |     ❌     |

[compose]: https://www.jetbrains.com/compose-multiplatform/
[maplibre]: https://maplibre.org/
[MLN]: https://github.com/maplibre/maplibre-native
[MLJS]: https://github.com/maplibre/maplibre-gl-js
[repo-demo]: https://github.com/maplibre/maplibre-compose/tree/main/demo-app
