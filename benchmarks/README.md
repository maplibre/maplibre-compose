# Map benchmarks

Measure the same map workload before and after a code change, or compare its
Compose imperative, Compose declarative, and classic Android SDK
implementations. The classic iOS SDK implementation is not included yet.

## Run and compare

Build the baseline, then capture at least three repetitions on one device:

```sh
mise run benchmark:build:android
mise run benchmark:run -- android --device SERIAL --case paint-points \
  --repeat 3 --output build/benchmarks/before
```

Build the candidate and repeat with `--output build/benchmarks/after`. Compare:

```sh
mise run benchmark:compare -- build/benchmarks/before build/benchmarks/after
```

To compare Compose implementations, capture another group with
`--implementation compose-declarative`. On Android, use
`--implementation classic-android` for the traditional SDK’s `MapView`. For
example:

```sh
mise run benchmark:run -- android --device SERIAL --case paint-points \
  --implementation classic-android --repeat 3 --output build/benchmarks/classic
mise run benchmark:compare -- build/benchmarks/before build/benchmarks/classic
```

Single runs can also be compared. The comparison reads saved `performance.json`
files and reports medians, ranges, and percentage changes, alongside each run's
configuration and viewport. Choose comparable runs yourself: use the same
device, viewport, prepared data, and workload when measuring a code change.
There are no artifact hashes or compatibility gates.

Each run saves `app.log` and `performance.json`. Name output directories for the
change or implementation you are measuring. Use an otherwise idle device with
stable thermal conditions and Android animation scale at 1×. Canonical numbers
should come from physical hardware.

Android installs the project's release APK once before running the repetitions.
Rebuild with `benchmark:build:android` after code changes. Other targets:

- Browser: `benchmark:build:js`, then `benchmark:run -- web --output PATH`.
- Desktop: `benchmark:build:desktop`, then
  `benchmark:run -- desktop --app PATH_TO_PACKAGED_EXECUTABLE --output PATH`.
- iOS simulator: `benchmark:build:ios` compiles the framework. Run
  `mise run demo:ios UDID` after every code change to build and install the app,
  then `benchmark:run -- ios --device UDID --output PATH`.

All runs require `--output`; existing output directories are not overwritten.

## Workloads and scenes

`mise run benchmark:run -- list` prints presets. Override their configuration
with `--config '{"durationMs":3000,"rateHz":2}'`.

| Workload         | Operation                                                      | Compose implementations |
| ---------------- | -------------------------------------------------------------- | ----------------------- |
| `idle`           | Leave the map unchanged                                        | Both                    |
| `camera`         | Set camera positions every display frame                       | Imperative              |
| `animation`      | Run camera animations                                          | Imperative              |
| `paint`          | Change layer colors                                            | Both                    |
| `layout`         | Toggle layer visibility                                        | Both                    |
| `layers`         | Remove and restore layers                                      | Declarative             |
| `source`         | Replace GeoJSON at a fixed rate                                | Both                    |
| `source-latency` | Replace GeoJSON and wait for the revision in rendered features | Both                    |
| `style`          | Replace the style and wait for style readiness                 | Both                    |
| `resize`         | Change map dimensions                                          | Declarative             |
| `padding`        | Change camera padding                                          | Declarative             |
| `recompose`      | Recompose unchanged map content                                | Declarative             |
| `images`         | Replace a bitmap image in place                                | Imperative              |

The classic Android adapter supports every workload above except `recompose`,
which measures Compose-specific work. It shares the fixtures, camera path,
workload clock, warm-up/reset sequence, and result format. Resize changes the
`MapView` layout; padding updates the SDK camera padding. The SDK dependency
uses the same `maplibre.android.backend` build choice as Compose (OpenGL by
default, or Vulkan). Versions remain pinned in the version catalog; the two
products may embed different MapLibre Native revisions.

Scenes are `minimal`, `points-100`, `points-1000`, `points-10000`, `route-2000`,
and `basemap-sf`. Point counts and route vertices provide controlled scaling;
the San Francisco basemap adds real vector-tile geometry and labels. Synthetic
fixtures are diagnostic loads, not sampled application traffic. Presets use
moderate volumes; select larger scenes explicitly when investigating scaling.

`layers` controls layer count (1–32), `rateHz` the scheduled mutation rate
(0.1–120), and `durationMs` the warm-up and measurement duration (3000–30000).
Camera movement and resize follow display frames. `surface` selects Android
`surface` or `texture`; `maximumFps` optionally caps rendering. Unsupported
workload, scene, and implementation combinations fail before capture.

## Measurement

Every run loads its scene, executes a full warm-up pass, resets, then measures
one pass. Logs report completed operation counts, submission timings where
available, and completion timings for style and source-latency workloads.
Submission measures the API call or state assignment; a declarative state
assignment does not include subsequent recomposition. Style readiness and a
rendered-feature revision are distinct completion signals, neither a display
presentation timestamp.

Native runs record process CPU time and engine encoding/rendering statistics.
Render events can be dropped by the public event stream; their count is not a
presented-frame count or a jank metric. Browser runs provide workload timings
but no process CPU or native engine timings. Comparison omits unavailable
metrics. Inspect operation counts alongside timings: fewer completed operations
can otherwise look like lower cost.

## Fixture preparation

Benchmark build and run tasks invoke the opt-in `deps:benchmarks` task. It
creates synthetic GeoJSON and downloads current VersaTiles tiles and Noto Sans
glyphs into the ignored build directory. Normal development does not download
benchmark data. Benchmarks load packaged resources without network requests.
There is no fixture manifest or pinned download hash.

To refresh cached data, run `mise deps install benchmarks --force` and rebuild.
Attribution and font licensing are in [fixtures](fixtures/).

`mise run benchmark:test` checks fixture generation, configuration, log parsing,
and comparisons. The Python harness uses only the standard library.
