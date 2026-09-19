# Demo benchmarks

Open **Benchmarks** in the demo to run a camera animation, Compose-clock camera
setters, input response, or one of the generated style, data, padding, image,
and resize workloads. Each run creates an isolated map, warms it up, measures
for twelve seconds, and closes it. Cancel or leave the benchmark to release its
map. The scene uses local GeoJSON and a production `placedAt` overlay; it
requires no network or offline tile pack. An optional deterministic circle load
increases map work without changing the reference markers. Every run also
records per-frame timing inside the app, so jank is measurable on platforms
without a trace adapter.

The capture runner uses that same scenario. Run from the repository root, with a
fresh output directory each time. Build before comparing changes, and run
captures serially without builds or other benchmarks competing for resources.

## Android

```sh
mise run benchmark:build:android
mise run benchmark:run -- android --device emulator-5554 \
  --config animation,surface,default,0 --mode both \
  --output build/benchmarks/surface-animation-1
mise run benchmark:run -- android --device emulator-5554 \
  --config input,texture,default,5000 --mode both \
  --output build/benchmarks/texture-input-1
```

Configuration is `scenario,surface,maximumFps,load[,params]`. Scenarios are
`animation`, `setters`, `input`, `style-complex`, `style-swap`, `style-mutate`,
`geojson-update`, `padding`, `images`, and `resize`; Android surfaces are
`surface` and `texture`. Use `default` for the library's unset FPS cap, or a
number from 1 to 240. Load is the number of additional circles, from 0 to 10000.
The optional `params` field is a JSON object of the scenario's knobs; omitting
it or passing `{}` runs the scenario's documented defaults. The demo panel edits
the same object in a text field. A bad key or value fails decoding with
`MAP_BENCHMARK ERROR` instead of silently running defaults.

| Scenario         | Knobs (defaults)                                          |
| ---------------- | --------------------------------------------------------- |
| `animation`      | none                                                      |
| `setters`        | none                                                      |
| `input`          | none                                                      |
| `style-complex`  | `layers` 8, `features` 2000, `sources` 2                  |
| `style-swap`     | `intervalMs` 1500, `count` 8, `layers` 6, `features` 1500 |
| `style-mutate`   | `rateHz` 8, `pairs` 4                                     |
| `geojson-update` | `rateHz` 4, `features` 5000                               |
| `padding`        | `amplitudeDp` 120, `periodMs` 2000                        |
| `images`         | `count` 8, `intervalMs` 500, `sizePx` 32                  |
| `resize`         | `periodMs` 2000, `minPercent` 50                          |

Every scenario measures the same twelve-second interval; a knob that finishes
early (such as a small `style-swap` `count`) idles until the interval ends.

The input runner injects eight taps through Android's input pipeline; each tap
alternates the camera between two positions.

The runner installs and verifies the release APK, starts the app with the
configuration, and stops it after capture. `--app` selects another APK.
`--mode visual` records pixels, `--mode performance` records Perfetto without
video, and `--mode both` records both. Performance-only runs require
`--visual-reference <capture-directory>` from the same artifact, configuration,
device, and host. The runner revalidates its raw pixels before publishing
performance results. Use these paired runs to check how much recording affects
results; they cannot prove that every unrecorded run displayed the same motion.
Android performance tracing requires API 29 or newer; the demo and visual
scenario retain the library's minimum API. Release builds are profileable by the
shell. Android captures require a system animator duration scale of 1×; the
runner checks and records this setting.

```sh
mise run benchmark:run -- android --device emulator-5554 \
  --config animation,surface,default,0 --mode performance \
  --visual-reference build/benchmarks/surface-animation-1 \
  --output build/benchmarks/surface-animation-no-video-1
```

Capture and lifecycle waits allow sixty seconds, covering the fifteen-second
readiness limit, warm-up, twelve-second workload, shutdown, and launch margin.
Android, desktop, and web recordings retain this full interval; measurements use
only the marked workload.

## What the measurements mean

| Measurement                       | Source and scope                                                                                                                                                                                                                                                                               |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Map/overlay separation            | Centers of the red native marker and cyan Compose ring in composed recordings; distributions in pixels and density-independent pixels.                                                                                                                                                         |
| CPU time                          | Android: Perfetto scheduled execution summed across app threads. Desktop/iOS: process CPU counter deltas. Includes native renderer threads; excludes time waiting and other processes.                                                                                                         |
| In-app frame intervals            | `MapEvent.FrameRendered` timestamps taken by the benchmark app on every platform. Distributions, plus jank counts above 1.5× and 2× the budget implied by the FPS cap (60 Hz assumed with `default`). Native builds also report engine encoding, rendering, draw-call, and mode distributions. |
| Window GPU time                   | Android `FrameMetrics.GPU_DURATION`, API 31+. Measures the app window's GPU work, including TextureView composition; excludes the map's separately submitted GPU commands.                                                                                                                     |
| App GPU active time               | Perfetto `power/gpu_work_period`, attributed to the app UID, on devices whose driver exposes it. Uses reported active duration; boundary periods are excluded and reported period coverage is retained. Missing data is unavailable, never zero.                                               |
| Missed window deadlines           | Android `FrameMetrics.TOTAL_DURATION > DEADLINE`, API 31+. Lost metric reports invalidate the performance result.                                                                                                                                                                              |
| Presentation events               | Android FrameTimeline, grouped by layer and reason. Transaction entries and prediction errors are retained separately; they are not counts of dropped map buffers. Coverage depends on Android and the presentation path.                                                                      |
| Input-to-captured-display latency | Input event timestamp to the first recording frame showing its alternating camera step, independently for map and overlay. Requires Android screenrecord Winscope v2 boot-clock metadata; reports bounds between adjacent capture frames.                                                      |

Android window metrics include only frames whose intended start and completion
fall within the Perfetto measurement interval. Collection starts before warm-up
so registration and callback delays do not determine the measured boundaries.

`FRAMETIMES` intervals measure the engine's render loop, not display
presentation: a rendered frame may never reach the panel, and independently
rendered map surfaces can miss a window deadline. Video capture stays the ground
truth for display timing where it exists. On the browser the intervals follow
MapLibre GL JS's render callback cadence, which is throttled in background tabs;
the browser adapter launches a headed browser with background throttling
disabled. Frame intervals are unavailable when a platform emits no render events
at all, and the performance report says so instead of reporting zero. The
browser also records its canvas at a variable rate, so capture sample counts can
be lower than native captures; the in-app frame metrics still cover the full
interval.

For low FPS caps, visual analysis scales the required sample count and uses the
visible end marker to verify the full interval. Sparse samples limit the
precision of separation percentiles. FrameTimeline results include only fully
contained events.

Frame intervals in a video describe the **capture**, not the map FPS. A recorder
may emit only changed frames during the input scenario; latency bounds become
wider across idle gaps. Separation percentiles count captured frames and can
therefore change with capture cadence. For input comparisons, also report how
many events showed map and overlay responses in different capture frames and the
spacing between those response frames. Latency bounds include recording-frame
uncertainty and a one-millisecond margin for the Android input clock conversion.
Screenrecord captures a virtual display; these measurements exclude the physical
touchscreen, panel scanout, and pixel response. Physical touch-to-photon latency
requires external measurement.

The scene's simple pan is a synchronization reference, not a representative
production style. Repeat comparisons in alternating order with both motions,
default and explicit FPS caps, and a larger circle load. Report device, backend,
resolution, build type, and capture mode alongside numbers. Emulator GPU and
presentation behavior cannot establish physical-phone overhead.

## Other platforms

The shared scenarios and pixel analysis run on iOS simulator, macOS desktop, and
Chromium. Their current adapters support `animation`, `setters`, and every
generated scenario as visual runs; automated input remains Android-only. Desktop
and iOS also record process CPU time across all app threads using the JVM
process CPU counter and Darwin `getrusage`, respectively. They do not yet
measure GPU time, presentation deadlines, or calibrated input latency. Web
reports the visual measurements and the in-app frame intervals, without a CPU,
GPU, or trace adapter. Use `--mode performance` on desktop or iOS with a
matching `--visual-reference` to measure CPU without video recording; the map
still runs on screen. Android's surface setting is ignored on these platforms.

```sh
mise run benchmark:build:ios
mise run demo:ios "$SIMULATOR_UDID"
mise run benchmark:run -- ios --device "$SIMULATOR_UDID" \
  --config animation,surface,60,0 --output build/benchmarks/ios-animation

mise run benchmark:build:desktop
mise run benchmark:run -- desktop --config setters,surface,60,0 \
  --output build/benchmarks/desktop-setters
```

For iOS, select an Xcode compatible with the simulator via `DEVELOPER_DIR`. For
desktop, `--app` selects an executable. Artifact matching hashes its enclosing
`.app` bundle when present, otherwise the executable itself. macOS captures only
the new benchmark process's window and needs Screen Recording access. Its
recorder requests 60 FPS, but the actual capture cadence is recorded and can be
lower.

For web, the runner serves the local build on an ephemeral localhost port and
closes the server afterward:

```sh
mise run benchmark:build:js
mise run benchmark:run -- web \
  --config animation,surface,60,0 --output build/benchmarks/web-animation
```

## Results and validation

Each directory contains metadata, app logs, a recording and/or Perfetto trace,
and JSON measurements. Visual runs also keep per-frame coordinates and a first
frame image. Retain the raw artifacts when sharing a comparison. Reanalyze
without launching the app:

```sh
mise run benchmark:run -- analyze --output build/benchmarks/surface-animation-1
mise run benchmark:test
```

Analysis rejects incomplete runs, insufficient marker coverage, stationary map
markers, mismatched configurations, truncated recordings, invalid clocks, and
missing input responses. Validation is per scenario: camera workloads
(`animation`, `setters`, `input`, `style-complex`, `padding`) must move the map
marker at least 40 dp, while style, data, image, and resize workloads verify the
marker coverage, measurement span, and sample counts without requiring motion.
`style-swap` allows more frames without both markers because replacing the style
legitimately blanks the map for a moment. Performance-only results retain their
visual reference in metadata and require it again on reanalysis. Synthetic tests
check known pixel offsets and latency bounds. Adding a scenario requires a
deterministic workload, an explicit measurement interval, a typed params class
with defaults, a validation profile, and validation that detects a broken
workload; projection callback timing is not a substitute for presentation
evidence.

Reference contracts:
[Android frame metrics](https://developer.android.com/reference/android/view/FrameMetrics),
[Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline),
[screenrecord clock metadata](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/cmds/screenrecord/screenrecord.cpp),
and
[GPU work-period interpretation](https://github.com/google/perfetto/blob/main/src/trace_processor/importers/ftrace/gpu_work_period_tracker.cc).
