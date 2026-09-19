# Map benchmarks

Measure the same map workload before and after a code change, or compare its
imperative and declarative implementations. Traditional Android and iOS SDK
implementations are not included yet.

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
`--implementation compose-declarative`. Comparison allows different builds and
implementations between groups, but requires the same device, viewport, and
workload configuration. Each group must use one build. Single runs can also be
compared. Results show the median, minimum, and maximum across runs and the
percentage change in medians.

Each run saves `app.log`, `metadata.json`, and `performance.json`. Android also
saves thermal status before and after. Use an otherwise idle device with stable
thermal conditions; keep the prepared data unchanged between compared builds.
Canonical numbers should come from physical hardware.

Other targets use `benchmark:build:ios`, `benchmark:build:desktop`, or
`benchmark:build:js`, then `benchmark:run -- ios|desktop|web`. iOS requires an
installed app on a simulator and `--device UDID`. Desktop accepts `--app PATH`
for the packaged executable. Browser runs use local build output and Chromium.
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
| `images`         | Register and remove bitmap images                              | Imperative              |

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
