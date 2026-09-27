# Map benchmarks

Measure map performance across code changes and against the classic Android and
iOS SDKs.

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
- Completion latency, for workloads with a completion signal.
- Frame intervals, and window frame timings on Android.
- Engine encoding and rendering time per frame.

Use one device, viewport, and data set per comparison, keep the device idle and
cool, and prefer physical hardware. SDK comparisons measure the delivered
stacks, including their MapLibre Native revisions.

## Publish tracked results

```sh
mise run benchmark:publish -- android --device SERIAL --scope pixel-8 --label "Pixel 8"
```

Runs the tracked presets three times each, for Compose and the classic SDK, and
uploads the medians under the device scope. Uploads need a clean checkout of a
commit on `main`; otherwise the results stay under `build/benchmarks/publish`.

## Fixtures

Geometry is generated. Basemap tiles and glyphs are a snapshot of
[VersaTiles][versatiles] data pinned in
[fixtures/manifest.json](fixtures/manifest.json) and served from the project
bucket. `benchmark:fixtures:refresh` pins the current data; measurements before
and after a refresh are not comparable. See [fixtures](fixtures/) for licensing.

[versatiles]: https://versatiles.org/
