# Overview

## Introduction

MapLibre Compose is a [Compose Multiplatform][compose] wrapper around the
[MapLibre][maplibre] SDKs for rendering interactive maps. You can use it to add
maps to your Compose UIs on Android, iOS, Desktop, and Web.

## Usage

- [Getting Started](./getting-started.md)
- [API Reference](./api/index.html)
- [Demo App][repo-demo]

## Status

A large subset of MapLibre's features are already supported, but the full
breadth of the MapLibre SDKs is not yet covered. What is already supported may
have bugs. API stability is not yet guaranteed; we're still exploring how best
to express an interactive map API in Compose.

We don't yet support Wasm because one of our dependencies,
[Spatial-K][spatial-k], doesn't support it.

| Feature                                           |        Android         |          iOS           |     Desktop (JVM)      |        Web (JS)        | Web (Wasm) |
| :------------------------------------------------ | :--------------------: | :--------------------: | :--------------------: | :--------------------: | :--------: |
| Renderer                                          | [MapLibre Native][MLN] | [MapLibre Native][MLN] | [MapLibre Native][MLN] | [MapLibre GL JS][MLJS] |    :x:     |
| Load a map with HTTP resource URLs                |   :white_check_mark:   |   :white_check_mark:   |   :white_check_mark:   |   :white_check_mark:   |    :x:     |
| Load a map with Compose resource URIs             |   :white_check_mark:   |   :white_check_mark:   |          :x:           |   :white_check_mark:   |    :x:     |
| Configure gestures (pan, zoom, rotate, pitch)     |   :white_check_mark:   |   :white_check_mark:   |          :x:           |   :white_check_mark:   |    :x:     |
| Respond to a map click or long/right click        |   :white_check_mark:   |   :white_check_mark:   |          :x:           |   :white_check_mark:   |    :x:     |
| Query visible map features                        |   :white_check_mark:   |   :white_check_mark:   |          :x:           |   :white_check_mark:   |    :x:     |
| Get, set, and animate the camera position         |   :white_check_mark:   |   :white_check_mark:   |          :x:           |   :white_check_mark:   |    :x:     |
| Convert between screen and geographic coordinates |   :white_check_mark:   |   :white_check_mark:   |          :x:           |   :white_check_mark:   |    :x:     |
| Get the currently visible region and bounding box |   :white_check_mark:   |   :white_check_mark:   |          :x:           |   :white_check_mark:   |    :x:     |
| Insert, remove, and replace layers                |   :white_check_mark:   |   :white_check_mark:   |          :x:           |          :x:           |    :x:     |
| Configure layers with expressions                 |   :white_check_mark:   |   :white_check_mark:   |          :x:           |          :x:           |    :x:     |
| Add data sources by URI or GeoJSON                |   :white_check_mark:   |   :white_check_mark:   |          :x:           |          :x:           |    :x:     |
| Add images to the style                           |   :white_check_mark:   |   :white_check_mark:   |          :x:           |          :x:           |    :x:     |
| Add Material 3 controls                           |   :white_check_mark:   |   :white_check_mark:   |          :x:           |          :x:           |    :x:     |
| Download offline regions                          |   :white_check_mark:   |   :white_check_mark:   |          :x:           |          :x:           |    :x:     |
| Snapshot the map as an image                      |          :x:           |          :x:           |          :x:           |          :x:           |    :x:     |

[compose]: https://www.jetbrains.com/compose-multiplatform/
[maplibre]: https://maplibre.org/
[MLN]: https://github.com/maplibre/maplibre-native
[MLJS]: https://github.com/maplibre/maplibre-gl-js
[repo-demo]: https://github.com/maplibre/maplibre-compose/tree/main/demo-app
[spatial-k]: https://github.com/dellisd/spatial-k
