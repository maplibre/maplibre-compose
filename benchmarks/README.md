# Map performance benchmarks

Run repeatable map workloads in the demo app, then compare repeated captures on
one device. Configuration separates **scene**, **workload**, and
**implementation**. Compose handle writes and declarative updates use the same
prepared geometry and schedule. Classic Android and iOS SDK adapters are not yet
implemented.

Each run loads local fixtures, waits for a fully rendered map, executes a full
warm-up pass, restores the starting state, measures one pass, and closes the
map. The default pass lasts twelve seconds. Warm-up retains resource and
renderer caches: these are warm workloads, not cold-cache or startup
measurements.

## Run and compare

```sh
mise run benchmark:build:android
mise run benchmark:run -- list
mise run benchmark:run -- android --device DEVICE_SERIAL \
  --case paint-points --mode visual --repeat 5 \
  --output build/benchmarks/imperative
mise run benchmark:run -- android --device DEVICE_SERIAL \
  --case paint-points --implementation compose-declarative --mode visual --repeat 5 \
  --output build/benchmarks/declarative
mise run benchmark:compare -- build/benchmarks/imperative \
  build/benchmarks/declarative --across-implementations
```

For a before/after code comparison, omit `--across-implementations`. Comparisons
require at least three runs per group. They reject differences in configuration,
fixture identity, viewport, device, display, or capture mode. Build artifacts
may differ between groups, but must remain identical within each group. Results
are medians and ranges across runs, not a significance test. Compare operation
counts alongside CPU; fewer updates can reduce CPU without improving
performance.

Use a physical device for performance conclusions. Run captures serially without
builds or other benchmarks in the background. Inspect thermal state, reverse
baseline/candidate order, and repeat before attributing differences to code.
Android captures save thermal-service snapshots before and after the run.

Output directories must be new. The runner retains configuration, artifact
hashes, app logs, video, optional trace, and JSON reports. A failed capture
stops `--repeat`; keep its evidence for diagnosis. Reanalyze or test the tooling
with:

```sh
mise run benchmark:run -- analyze --output build/benchmarks/imperative/001
mise run benchmark:test
```

## Scenes and workload boundaries

[Named cases](cases.json) select useful combinations without running every
possible combination. `--config` accepts JSON overrides; the resolved
protocol-v2 object is recorded in metadata and checked against the app log. The
demo panel accepts that same object. For example:

```sh
mise run benchmark:run -- android --device DEVICE_SERIAL \
  --case update-points --implementation compose-declarative \
  --config '{"scene":"points-10000","rateHz":10,"durationMs":12000}' \
  --output build/benchmarks/large-update
```

| Scene                                       | Contents and purpose                                                                                        |
| ------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `minimal`                                   | Background only; idle and platform/input overhead                                                           |
| `points-100`, `points-1000`, `points-10000` | Prepared point features; small, ordinary, and stress volumes                                                |
| `route-2000`                                | One line with 2,000 vertices; route geometry replacement                                                    |
| `basemap-sf`                                | Pinned San Francisco vector tiles, buildings, roads, and street labels; tiled rendering and camera movement |

Synthetic fixtures report both feature and vertex counts. Point fixtures are
spread across a fixed geographic extent, so submitted count is not the same as
visible count. `layers` duplicates the data layer (default one, presets use
eight for style work); these are deliberately controlled overdraw and mutation
tests, not a claim that real styles contain identical layers. The compact
basemap style is a real tiled workload, but does not represent every production
style.

| Workload         | Operation                                                           | Compose implementations        |
| ---------------- | ------------------------------------------------------------------- | ------------------------------ |
| `idle`           | Settled map with no commands                                        | Imperative, declarative        |
| `camera`         | Frame-driven pan, zoom, bearing, and pitch tour                     | Imperative                     |
| `animation`      | Four awaited engine camera animations                               | Imperative                     |
| `paint`          | Toggle existing layer colors                                        | Imperative, declarative        |
| `layout`         | Toggle layer visibility; no source replacement                      | Imperative, declarative        |
| `layers`         | Remove/add declared layers over a stable source                     | Declarative                    |
| `source`         | Replace prepared GeoJSON at a fixed rate                            | Imperative, declarative        |
| `source-latency` | Replace GeoJSON, then query until the rendered revision is observed | Imperative, declarative        |
| `style`          | Replace the complete base style and await style-ready               | Imperative, declarative        |
| `resize`         | Animate map height between 50% and 100%                             | Declarative                    |
| `padding`        | Animate bottom padding between 0 and 200 dp                         | Declarative                    |
| `recompose`      | Invalidate declared content with unchanged map inputs               | Declarative                    |
| `images`         | Remove/register a prepared 32 × 32 bitmap used by point symbols     | Imperative                     |
| `input`          | Runner-injected taps alternate camera positions                     | Imperative; Android automation |

Paint, layout, structure, and data replacement are separate to identify which
path regressed. The structural case keeps one hidden anchor layer so removing
the tested layers does not also remove and reparse their reference-counted
source. `layout` currently tests visibility, not symbol relayout. Source
throughput and completion latency are separate because rendered-feature queries
add work. A completion query observes a rendered feature revision, not physical
display presentation. Likewise, style-ready includes style loading but does not
prove tile rendering has finished.

The current API has no declarative camera-position setter or imperative layer
construction API. Unsupported implementation/workload pairs are rejected rather
than labeled as equivalent. Resize and padding use the Compose map view in both
styles of map ownership, so there is only one case for each.

Scheduled updates use absolute time slots and skip missed slots without catch-up
bursts. Frame-driven workloads submit fewer steps if the UI thread slows.
Submission timings measure synchronous API/state-write cost; declarative work
usually happens later and must also be assessed through process CPU and frame
statistics. Operation counts count workload steps, not rendered updates. Fixture
construction is outside measurement; engine GeoJSON parsing and updates are
inside it.

Protocol fields are `version: 2`, `workload`, `scene`, `implementation`,
`surface: "surface"|"texture"`, `maximumFps: null|1..240`, `overlays: 0..100`,
`layers: 1..32`, `rateHz: 0.1..120`, and `durationMs: 3000..30000`. `rateHz`
applies to scheduled mutations; camera/resize/padding/recomposition use the
frame clock. The surface selection applies only to Android. Input requires one
overlay. Basemap cases disallow marker analysis. Unknown fields and invalid
combinations fail before capture.

## Measurements and limits

- **Process CPU:** counter deltas on Android, desktop, and iOS. Optional Android
  Perfetto analysis uses scheduled app-thread execution instead. Includes
  renderer threads, excludes waiting and other processes.
- **Native render statistics:** engine encoding/rendering times and draw calls.
  These are engine diagnostics, not portable GPU execution or display latency.
- **Render-event intervals:** callback delivery intervals, including intentional
  idle time and scheduling. The event stream may drop reports. These are neither
  FPS nor jank measurements; an idle map can correctly report zero frames.
- **Android window metrics:** FrameMetrics deadlines and GPU duration where
  supported. Window GPU duration excludes independently submitted map GPU work.
  Lost reports invalidate analysis; only complete frames inside the measured
  interval contribute.
- **Optional Perfetto:** app GPU work periods and FrameTimeline by layer, when
  exposed by the device. Missing data is unavailable, never zero. Presentation
  events do not count dropped map buffers.
- **Operation timing:** synchronous submission and, for the two completion
  workloads, a separately named completion signal. Batched logs include counts
  so truncated timings fail validation.

Maps have no Compose overlays by default. `overlay-sync` adds one cyan Compose
ring around a red map marker; `overlay-many` adds 100 overlays. Their captured
separation diagnoses overlay synchronization, not general map performance.
`tap-response` reports input-to-captured-response bounds using Android capture
clock metadata; it excludes physical touch hardware and panel response.

Video validation checks the measurement gate and duration. Overlay cases also
check markers, coverage, and camera movement. It does not prove every paint or
data mutation reached the display. Missing handles, resource errors, workload
timeouts, incomplete timing batches, and failed shutdown reject a capture.

Recording changes the workload. `--mode visual` records video plus available
CPU/window/render statistics. `--trace` adds Android Perfetto; `--mode both` is
shorthand for video plus tracing. Some OS builds do not expose the required
ftrace data and trace analysis fails explicitly. For unrecorded runs, first
capture a visual reference of the same configuration, artifact, device, and
host:

```sh
mise run benchmark:run -- android --device DEVICE_SERIAL \
  --case paint-points --mode performance \
  --visual-reference build/benchmarks/imperative/001 --repeat 5 \
  --output build/benchmarks/without-video
```

That reference proves its own run was visible, not every subsequent run.
Performance-only runs use process counters unless `--trace` is also supplied.

## Fixtures and other platforms

[Fixture assets](../demo-app/common/src/commonMain/composeResources/files/benchmarks/)
contain synthetic GeoJSON, 25 vector tiles, glyphs, licenses, and a SHA-256
manifest. Captures need no network. `python benchmarks/prepare_fixtures.py`
regenerates synthetic data and checks existing basemap hashes. Use `--download`
only to deliberately refresh the snapshot; review the resulting manifest and
attribution. Fixture integrity is also checked by `benchmark:test`.

Desktop, iOS simulator, and Chromium use the same shared workloads. Desktop
capture requires macOS Screen Recording access. Android requires animator scale
1×; Perfetto requires API 29+. Browser capture supports visual mode only.

```sh
mise run benchmark:build:desktop
mise run benchmark:run -- desktop --case resize-basemap --output build/benchmarks/desktop
mise run benchmark:build:ios
mise run demo:ios SIMULATOR_UDID
mise run benchmark:run -- ios --device SIMULATOR_UDID --case animation-basemap \
  --output build/benchmarks/ios
mise run benchmark:build:js
mise run benchmark:run -- web --case camera-basemap --output build/benchmarks/web
```

The iOS and browser tasks produce development builds. Do not compare them with
Android release numbers. `--app` selects an Android APK or desktop executable;
iOS uses the installed simulator app, and web serves local assets unless `--url`
is supplied.

## Extending coverage

Classic Android and iOS SDK implementations should consume these same prepared
fixtures, camera path, schedule, warm-up, and report protocol, using their
actual map views. Match engine version, backend, surface, viewport, density, and
build optimization before comparing costs. Keep each adapter's API commands
separate; only advertise workload pairs with equivalent operations and
completion signals.

Upstream's
[Android BenchmarkActivity](https://github.com/maplibre/maplibre-native/blob/de51949f665160db9c04bc441b7b83ea39425ed3/platform/android/MapLibreAndroidTestApp/src/main/java/org/maplibre/android/testapp/activity/benchmark/BenchmarkActivity.kt)
informed full workload warm-up, real tiled scenes, repeated runs, and thermal
capture. Its timing boundaries and rendering synchronization flags must be
matched explicitly before reusing its results. The upstream iOS headless
benchmark is not an MLNMapView baseline.

Additional suites should cover controlled cold resource loading, map creation
and destruction, symbol relayout, and feature-state updates. They are not
represented by the current warm mutation cases. Cold-cache claims require an
isolated resource cache; creating a fresh map alone is insufficient.
