# Map benchmarks

A local tool for comparing map performance across code changes and between
MapLibre Compose and the classic Android and iOS SDKs.

## Run and compare

Build, then capture at least three repetitions on one device:

```sh
mise run benchmark:build:android
mise run benchmark:run -- android --device SERIAL --case paint-points \
  --repeat 3 --output build/benchmarks/before
```

Rebuild after your change and repeat with `--output build/benchmarks/after`,
then compare:

```sh
mise run benchmark:compare -- build/benchmarks/before build/benchmarks/after
```

To compare SDKs instead, repeat with `--implementation classic-android` or
`classic-ios` on the corresponding platform. Compose defaults to
`compose-imperative`; `compose-declarative` is also available. The runner
selects the app to install.

Each capture saves `app.log` and `performance.json`, including the app's build
commit, dirty-checkout flag, and pinned SDK versions. `--output` is required and
must name a new directory. Comparisons report medians, ranges, and percentage
changes; single runs can also be compared.

## Other platforms

Prefix the tasks below with `mise run`. Add `--case`, `--repeat`, and `--output`
as in the Android example.

| Target        | Build task                        | Run task                                         |
| ------------- | --------------------------------- | ------------------------------------------------ |
| iOS simulator | `benchmark:build:ios`             | `benchmark:run -- ios --device UDID`             |
| iPhone        | `benchmark:build:ios -- --device` | `benchmark:run -- ios --device IDENTIFIER`       |
| Desktop       | `benchmark:build:desktop`         | `benchmark:run -- desktop --app EXECUTABLE_PATH` |
| Browser       | `benchmark:build:js`              | `benchmark:run -- web`                           |

Boot the iOS simulator before running. For an iPhone, configure Xcode signing,
unlock and trust the phone, and find its identifier with
`xcrun devicectl list devices`. Android and iOS build tasks produce both the
Compose and classic SDK apps.

## Choose a workload

List presets with `mise run benchmark:run -- list` and select one with `--case`.
Presets cover camera movement, style and source updates, layer changes, and
other map operations. Supported implementations vary by workload; `recompose` is
Compose-only, as is `overlays-points`, which runs the camera tour with the
default Compose map controls. Other workloads hide optional map controls on both
SDKs, retaining attribution for real-data basemap scenes.

Override preset settings with JSON, for example
`--config '{"durationMs":3000,"rateHz":2}'`. See [cases.json](cases.json) for
presets and `mise run benchmark:run -- --help` for command options.

## Interpret results

- Use the same device, viewport, workload, and prepared data. Keep the device
  otherwise idle and thermally stable, with Android animation scale at 1×. Use
  physical hardware for performance conclusions.
- Each run warms up before measurement. Compare operation counts alongside
  timings: fewer completed operations can look like lower cost.
- Submission timings measure API calls or state assignments, excluding later
  declarative recomposition. Completion signals and render-event counts are not
  display presentation timestamps, FPS, or jank measurements.
- Browser runs provide workload timings but no process CPU or native engine
  timings. Comparisons omit unavailable metrics. SDKs may embed different
  MapLibre Native revisions, so SDK comparisons measure the delivered stacks.

Build and run tasks prepare and cache benchmark data on first use; measured runs
use packaged resources offline. To refresh the cache, run
`mise deps install benchmarks --force` and rebuild. See [fixtures](fixtures/)
for attribution and font licensing.
