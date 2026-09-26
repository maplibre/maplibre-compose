# Code metrics

A snapshot of the Kotlin under `lib/` and `demo-app/` at one commit: size,
complexity, and package dependencies, all measured from syntax. Compare
snapshots to see whether the codebase is getting simpler.

```bash
mise run metrics                 # working tree -> build/metrics/head.json
mise run metrics --ref v0.18.0   # a commit -> build/metrics/v0.18.0.json
mise run metrics:history         # every release tag, then the working tree
```

The snapshot's `summary` is a flat set of numbers to graph. The sections after
it hold the detail behind each number:

- `sourceSets`: size and declaration counts per module and source set.
- `packages`: size, imports in and out, and instability.
- `packageGraph`: import edges, cycles, and pairs that import each other.
- `distributions` and `largest`: how file, type, and function sizes spread, and
  which are biggest.

`Report.kt` documents each field.

## Dashboard

The docs site's [Code metrics](../../docs/src/pages/metrics.astro) page charts
these measurements across the history of `main`. To preview it with local data:

```sh
mise run metrics:site-data
mise run metrics:dev
```

Then open `http://127.0.0.1:4321/maplibre-compose/metrics/`. The export starts
at v0.14.0, the first release without the C++ JNI code; `--from <ref>` starts
elsewhere. `--step 16` measures every sixteenth commit for a quicker preview.

`history.py` runs the reporter with `--scopes` over the first-parent commits it
hasn't measured yet, caching each result under `build/metrics/history/` by the
reporter's jar and dependency versions. It writes this to
`docs/public/metrics-data/`, which Git ignores:

- `index.json`: every commit with its date, subject, and tags; the available
  scopes; and the Detekt thresholds behind the complex and long function counts.
- `series/<scope>.json`: one column per charted metric, aligned with the commits
  in the index.
- `snapshots/<commit>/<scope>.json`: the report behind the hotspots and package
  tables, fetched when a commit is selected.
- `snapshots/<commit>/files.json`: every production file, for the module tree.

A scope is all code, the library, the demo app, or one module. Each is
recomputed from its own files, so its percentiles and package graph ignore code
outside it. The published site shows the page without data until an export is
hosted with it.
