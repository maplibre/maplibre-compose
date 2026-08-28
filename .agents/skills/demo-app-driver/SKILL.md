---
name: demo-app-driver
description: Drive the demo app over its local HTTP API to reproduce bugs, move the camera, switch demos and styles, and capture screenshots and state. Use when reproducing behavior in the demo app, verifying a fix there, or gathering evidence from a running demo.
---

# Demo app driver

The demo app hosts an HTTP JSON API on `127.0.0.1:8765` while it runs. It
exposes the live app state: demos, camera, style, rendered features, and
screenshots. There is no authentication; the API exists for local development.
Set `MAPLIBRE_DEMO_AGENT_PORT` to change the port.

The desktop (AWT, GLFW, Nucleus) and Android apps serve the API. On GLFW and
Nucleus, `/screenshot` returns 501. Drive the browser app with Playwright.

## Start the app

```sh
mise run demo:desktop    # desktop; window may stay in the background
mise run demo:android    # then: adb -s <serial> forward tcp:8765 tcp:8765
```

On Android, `adb forward` makes the device's port reachable on the host. When
several devices are connected, `adb devices` lists their serials and
`adb -s <serial>` targets the one that `demo:android` launched on. Wait for
startup, then confirm:

```sh
curl -s http://127.0.0.1:8765/health
```

## Learn the API from the app

`GET /` returns the route index: every route with its method, body fields, and
query parameters, plus usage notes. Read it rather than guessing paths; it is
the reference for this API. Every response is JSON except `/screenshot`, which
returns PNG bytes. Every error is JSON `{"error": "..."}` with a 4xx or 5xx
status, and 400 messages list valid values or ranges.

## Reproduce and capture

A typical evidence-gathering flow:

```sh
curl -s -X POST localhost:8765/demos/select -H 'Content-Type: application/json' \
  -d '{"name": "Data visualization"}'      # names from GET /demos; case-insensitive
curl -s -X PUT localhost:8765/camera -H 'Content-Type: application/json' \
  -d '{"latitude": 40.71, "longitude": -74.0, "zoom": 12, "bearing": 30, "tilt": 45}'
curl -s -X POST localhost:8765/wait/idle -d '{}'
curl -s localhost:8765/screenshot -o shot.png   # view the PNG to confirm
curl -s localhost:8765/state                   # demo, camera, fps, style, settings
```

- `POST /camera/animate` suspends until the animation ends or another camera
  command supersedes it. Await its response before calling `/wait/idle`.
- `POST /wait/idle` waits for style loads to finish and the camera to be still.
- `PUT /style` takes a style name from the error message's valid list or the
  demo's `preferredStyle`. A demo that pins its style answers 409; select
  another demo first.
- A 408 from `/demos/select` still applies the demo and flies the camera; only
  the style wait timed out.
- Camera, style, and demo operations switch the app out of the Benchmarks shell
  back to the Demos shell.

## Coordinates and assertions

`/features`, `/gestures/pan`, and `/gestures/zoom` take screen coordinates in
physical pixels: the same coordinate space as the screenshot PNG, so a point you
pick in a captured image works unchanged in API calls. The gesture endpoints
move the camera through the map's projection math.

Use `GET /features?x=…&y=…` to assert what the map renders at a point instead of
judging pixels: it returns the rendered features as GeoJSON, filterable with
`layerIds`. Find the layer IDs a demo uses in its layer composables under
`demo-app/common/src/commonMain/kotlin/org/maplibre/compose/demoapp/demos/`. An
empty result is ambiguous: the layer may be absent from the style, or the point
may miss its features. The demos panel covers the left part of the window, so
pick query points in the visible map area.

Map a latitude/longitude to screen coordinates by setting the camera so the
point sits at the center, then query at half the screenshot's width and height.

## Demo panel state

The driver operates demo selection, camera, and style. Demos keep further state
in their panel controls: the Data visualization demo gates its heatmap and
cluster layers behind a "Render as" control, so those layers exist only after a
manual selection. A demo can also swap its whole layer set per mode, so a layer
ID from the source may be absent from the live style. Read the demo's `Panel`
composable before you promise to verify mode-dependent behavior, and switch
modes by hand when a check needs one.

## If the API does not answer

- Check `/health`. Connection refused means the app is not running or, on
  Android, `adb forward` is missing.
- A port conflict disables the driver; the app logs "agent driver disabled" and
  continues without it.
- A 503 on `/screenshot` means the app's frame pipeline stalled; retry after a
  camera move.
