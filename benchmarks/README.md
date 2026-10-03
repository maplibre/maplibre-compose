# Map benchmarks

Measure map performance across code changes, against the classic Android and iOS
SDKs, and over time on the documentation site's [benchmarks page][page].

## Compare two builds

```sh
mise run benchmark:build:android
mise run benchmark:run -- android --device SERIAL --case paint-points \
  --repeat 3 --output build/benchmarks/before
# Change the code, rebuild, and capture into build/benchmarks/after, then:
mise run benchmark:compare -- build/benchmarks/before build/benchmarks/after
```

Add `--implementation classic-android` or `classic-ios` to compare SDKs.
`mise run benchmark:run -- list` prints the presets in [cases.json](cases.json);
`--config '{"rateHz":2}'` overrides a setting.

| Target        | Build task                        | Run task                                         |
| ------------- | --------------------------------- | ------------------------------------------------ |
| iOS simulator | `benchmark:build:ios`             | `benchmark:run -- ios --device UDID`             |
| iPhone        | `benchmark:build:ios -- --device` | `benchmark:run -- ios --device IDENTIFIER`       |
| Desktop       | `benchmark:build:desktop`         | `benchmark:run -- desktop --app EXECUTABLE_PATH` |
| Browser       | `benchmark:build:js`              | `benchmark:run -- web`                           |

## What a run reports

- Process CPU time, per operation and per second.
- Time to style ready and to the first frame.
- Submission and completion latency, for workloads with a completion signal.
- First close-call return and adapter cleanup completion for lifecycle
  workloads.
- UI-thread callback intervals, and actual window frame timings on Android.
- Displayed map FPS and frame gaps on Android during the engine animation.
- Engine encoding and rendering time per frame.

Use one device, viewport, and data set per comparison, keep the device idle and
cool, and prefer physical hardware. SDK comparisons measure the delivered
stacks, including their MapLibre Native revisions.

## Operation boundaries

`image-cycle` reuses prepared 32×32 pixels and times removal plus registration
together. Completion includes queued commands and map settlement.
`image-preparation` instead converts a 256×256 bitmap on `Dispatchers.Default`
on every update before registering it in a visible symbol layer. Its submission
time includes suspending preparation; it is not a measure of how long the UI
thread blocks. `image-registration` remains the prepared-image replacement
control, using the same 256×256 dimensions and rate. No workload reads prepared
pixels back just to consume a result.

`style-overlay` replaces a 600-layer base style and declares an overlay using
`getBaseSource` and a predicate anchor, then waits for that overlay to render.
Each style variant uses a different overlay ID so a query cannot accept the
previous style's overlay. `overlay-update` toggles that overlay over an
unchanged style and waits for a rendered-feature query to observe the change.
These exercise metadata consumers; `style-publication` uses the same base style
without metadata consumers, through style-ready. Its completion signal precedes
rendering, so its latency cannot be subtracted from `style-overlay` to isolate
consumer cost. All three cases partition the point layers to avoid multiplying
visible overdraw.

`source-completion` uses 1,000 points and waits for the submitted revision to
appear in a rendered-feature query. It exercises the default asynchronous
preparation path. It does not measure the removed synchronous-preparation API.
Larger fixtures can complete only a few updates per run; report operation counts
and use a longer `durationMs` when investigating them.

`map-return` owns each map explicitly. The runner measures the first `close()`
while the map is still presented, then detaches the presentation and waits for
cleanup. `close_ms` measures caller return; `close_completion_ms` includes
presentation detach and `awaitClosed()`. Compose awaits native cleanup; the
classic iOS adapter removes the view but has no native destruction completion
signal. UI-frame measurements cover both phases. One unmeasured map primes
caches. There is no second composition-owned close.

`runtime-startup` creates no map. It uses a dedicated empty local database,
primes one runtime, then measures warm-process reopenings: submission is
constructor return, completion is readiness observed on the main dispatcher, and
close timings separate caller return from finished cleanup. It has no
map-startup or engine-frame metrics. This is not cold-process startup, a cold
filesystem-cache measurement, or a populated offline-pack benchmark. It is
available on MapLibre Native platforms only.

All completion signals are engine observations, not proof of screen
presentation.

## Publish to the benchmarks page

```sh
mise run benchmark:publish -- android --device SERIAL --scope pixel-8 --label "Pixel 8"
```

Runs the tracked presets three times each and uploads medians and repetition
ranges under the device scope. [cases.json](cases.json) selects the tracked
cases, their platforms, and which also run the classic SDK. Images and metadata
consumers are tracked alongside the existing map workloads. Runtime readiness
runs on Android, iOS and desktop; map return runs on Android and iOS. Classic
comparisons run only where the preset requests one and the platform has that
SDK.

Submission, completion, close return and cleanup completion remain separate
metrics on the page. Uploads need a clean checkout of a commit on `main`;
otherwise the results stay under `build/benchmarks/publish`.

Each device's measurement replaces its earlier results for that commit. Do not
mix old logs or archived builds with different operation boundaries into the
same comparison.

## Backfill releases

`mise run benchmark:backfill` measures past releases on Android with the current
harness, so the page's history uses the same workloads as new measurements. It
builds each release with the harness overlaid and archives the APKs in the
[artifact archive][artifacts]. Each manifest records the APK hashes, the release
and harness commits, the harness patch applied to the release, and the cases it
runs. Adding a device measures the archived builds; changing the harness
requires a new archive. The
[benchmarks skill](../.agents/skills/benchmarks/SKILL.md) walks through the
steps.

## Fixtures

Geometry is generated. Basemap tiles and glyphs are a snapshot of
[VersaTiles][versatiles] data pinned in
[fixtures/manifest.json](fixtures/manifest.json) and served from the project
bucket. `benchmark:fixtures:refresh` pins the current data; measurements before
and after a refresh are not comparable. See [fixtures](fixtures/) for licensing.

[page]: https://maplibre.org/maplibre-compose/benchmarks/
[versatiles]: https://versatiles.org/
[artifacts]: https://mlc-data.sargunv.dev/benchmarks/artifacts/index.json
