---
name: benchmarks
description: Run, compare, publish, and backfill the map benchmarks. Use when measuring performance, changing the benchmark harness or tracked cases, or changing what the benchmarks page shows.
---

# Benchmarks

Start with [benchmarks/README.md](../../../benchmarks/README.md): it lists the
mise tasks, what a run reports, and the operation boundary of each workload.
[cases.json](../../../benchmarks/cases.json) names the presets and marks the
tracked ones, their platforms, and which also run the classic SDK.

The harness has three layers:

- `benchmarks/core` holds the workloads, clocks, and log format, shared by every
  app.
- `demo-app/common/src/*/kotlin/org/maplibre/compose/demoapp/benchmark/` is the
  Compose adapter. It depends only on the library and the core, plus the demo's
  generated `Res` for fixtures, so a backfill can compile it on its own. Keep
  demo UI out of that folder.
- `benchmarks/android` and `benchmarks/ios` are the classic SDK apps, for
  comparisons.

The Python scripts in `benchmarks/` launch the apps, parse `MAP_BENCHMARK` log
lines into `performance.json`, and compare or publish runs. A failed workload
logs `MAP_BENCHMARK ERROR`; read the app log in the run folder.

## Measuring on Android devices

Use physical devices, charging, cool, and idle. `adb devices -l` lists them.
`adb shell dumpsys thermalservice` reports `Thermal Status`; measure only at 0.
A fresh install can wait on a Google Play Protect prompt on the device until
someone answers it. Separate devices can measure in parallel. One device runs
one app at a time.

Compare results only from the same device, viewport, backend, and fixtures.
Whenever a workload's definition or operation boundary changes, earlier results
stop being comparable, and the published history needs a new backfill.

## Published data

The benchmarks page reads the R2 bucket `maplibre-compose-metrics`, served at
`https://mlc-data.sargunv.dev/`. Wrangler writes and deletes objects
(`wrangler r2 object put|delete maplibre-compose-metrics/<key> --remote`) but
cannot list them. Every key follows from the indexes below. Read objects through
the public URL with a unique query string, which skips Cloudflare's cache.

- `benchmarks/index.json`: the measured commits in date order with their release
  tags, the device scopes (`id`, `label`, `platform`, `backend`), and case
  metadata.
- `benchmarks/series/<scope>.json`: one column per `<case>.<kind>.<metric>`,
  plus `.min` and `.max` for headline metrics, aligned with `commits` in the
  index. `kind` is `compose` or `classic`. `null` means not measured.
- `benchmarks/snapshots/<commit>/<scope>.json`: every run behind a published
  median.
- `benchmarks/artifacts/index.json`: the archived backfill builds. Each manifest
  lists its APKs, the harness patch applied to the release, and the plan of
  cases it runs. Objects under `benchmarks/artifacts/sha256/` are
  content-addressed and immutable.
- `benchmark-fixtures/<version>/`: the basemap snapshot that
  `benchmarks/fixtures/manifest.json` pins.
- `index.json`, `series/`, and `snapshots/` at the root belong to the code
  metrics dashboard.

`publish.sync` reads the index, then rewrites the index, the series, and the
snapshot. Two devices publishing at once can lose one update, so publish one at
a time. To drop a device, remove its scope from the index and delete its series
and snapshots. To start the page over, delete the index, series, and snapshots
for every scope; the next publish creates a new index.

## Backfilling releases

A backfill measures past releases with today's harness, so the history uses the
same workloads as new measurements. Only Android is backfilled.
`mise run benchmark:backfill -- <action> --help` documents each step:

1. `prepare <tag> --classic <version>` checks out the release under
   `build/benchmarks/backfill/<tag>` and overlays the harness and a small
   Compose host app. For the classic SDK version, use the newest stable
   `android-v*` release of maplibre/maplibre-native that was published before
   the tag.
2. `build <tag>`. When the harness does not compile against the release, port
   the harness in the checkout. Never change the release's `lib/`.
3. `archive <tags...> --skip <case>[@<tag>]` writes manifests, and `--upload`
   replaces the archive in the bucket. Before uploading, run
   `run --local --smoke --release <tag>` on a device to catch runtime failures.
4. `run --device <serial> --scope <id> --label <name>` measures every archived
   release on one device. Restarting it reuses finished runs.
5. `publish --scope <id> --label <name>`, one device at a time.

Measure current `main` with `benchmark:publish` alongside, so cases the releases
skip still have a point on the page.

Porting means reaching the same operation through the release's public API, such
as an older name for a camera animation or image registration without prepared
pixels. When a release cannot express the operation being measured at all, skip
that case for the release rather than measuring something else under its name. A
case that fails on a release because of a library bug is skipped for that
release too; file the bug if `main` still has it. Each manifest's `harness`
patch records an earlier port and is a useful starting point. Supported releases
start at v0.16.0, the first with the `MapState` API the adapter uses.
