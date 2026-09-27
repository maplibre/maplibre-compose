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
selects the app to install. On Android, `--app PATH.apk` selects an archived
build for comparisons without rebuilding between captures.

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

## Map return and UI stalls

`map-return` recreates a map behind a panel that slides away over 300 ms. Both
Android hosts create a fresh map, install 256 circle layers and 64 prepared
images, wait for rendered content to settle, then dispose the map behind the
panel before the next return. One unmeasured map primes code and resource
caches; each measured return still rebuilds the live map and style. This
isolates the library workload without depending on an application's navigation
framework. It does not test retaining a map, application sensors, or background
lifecycle.

`sparse-paint` changes only the first of 256 layers at 30 Hz. Each layer has a
feature filter and zoom-dependent radius. Points are partitioned across layers,
so increasing declaration count does not multiply visible overdraw.
`image-burst` checks and registers 64 distinct image IDs using prepared bitmaps
at 1 Hz; it removes the previous batch before timing registration. CPU and UI
frame measurements include removal, but submission timings exclude it. Images
are registered without symbol layout to isolate registration overhead.

The cases support comparison with `--implementation classic-android`. Map return
and sparse paint use Compose declarations; image bursts use public imperative
image access, matching applications that manage their own image registry.
Override `layers`, `imageCount`, and `rateHz` to examine scaling. Map return is
currently enabled only by the Android runner.

Android captures also record window
[`FrameMetrics`](https://developer.android.com/reference/android/view/FrameMetrics),
separately from engine render events. A measurement-only UI invalidation on
every vsync keeps both hosts drawing window frames even when their map uses a
separate SurfaceView. Reports include total frame duration, delay before UI
processing, missed frame deadlines (API 31+), and dropped metric reports. These
are window responsiveness measurements, not map-surface presentation times.
Inspect the maximum delay as well as percentiles: a few long stalls can
disappear below p95 in a long run. CPU and UI measurements for map return
include teardown and the covered interval; completion measures remount through
content settlement and excludes teardown.
