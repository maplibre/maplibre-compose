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

## Local dashboard

Generate history and serve it without uploading anything:

```sh
mise run metrics:site-data -- --since 2026-07-25
mise run metrics:dev
```

Open `http://127.0.0.1:4321/maplibre-compose/metrics/`. The dev task prints the
address if that port is occupied. `--step 16` produces a faster preview by
sampling every sixteenth commit, always including the latest commit. The page
labels sampled history. Without `--since`, export the last two months of the
current branch's first-parent history.

The dashboard defaults to Library. Scope, module, source set, and period apply
to the charts and tables. Overview, Complexity, Dependencies, and Size select
chart groups. Charts share a cursor and zoom; click one to select a snapshot.
Hover shows one commit readout and updates the values beside each series,
without covering the plots. Click a series label to hide or show it in that
chart. Use Ctrl + scroll to zoom and Shift + drag to pan. The snapshot selector
also works from the keyboard. Dates use UTC and charts use actual commit times.

Distribution trends show median (p50), p90, and p99. Open **Distribution** on a
chart, or the **Distributions** tab, for the selected snapshot's count, mean,
p50/p75/p90/p99, maximum, and frequency bars. Every observation contributes to
one range; bars show each range's percentage of the population. Range widths
vary and are labeled. Comparisons use the same ranges and a separate population
total for each snapshot. The maximum links to its source declaration.

Percentiles use nearest rank. The reporter stores exact value frequencies in
`distributions.*.histogram`, so the display does not infer a distribution from
percentiles or from the truncated top lists. Definitions are available on each
chart, in the distribution view, and beside every row in **All metrics**.
Reference lines use Detekt's per-function defaults: cognitive complexity 15,
cyclomatic complexity 14, and function length 60 code-bearing lines. The
snapshot view counts observations strictly above each reference. These are
review heuristics, not repository quality gates or calibrated defect risks.
Linked sources distinguish tool defaults, empirical research, and architectural
principles. Context metrics such as total lines and mean package instability
have no invented good/bad bands.

Function and type lengths count code-bearing lines; file lengths count physical
lines, including comments and blanks.

Comparison is optional. Enabling it adds a baseline and deltas. Packages and
source sets have sortable numeric columns. Functions and files are ranked top
lists, so they do not show additions, removals, or deltas inferred from absence.
Source links point to the selected commit. Filters and comparison selections are
stored in the URL.

### Scoped measurements

`--scopes` asks the Kotlin reporter to calculate library, demo, module, and
production source-set reports from the parsed files. Percentiles are recomputed
from each scope's functions and types. They are never averaged from source-set
percentiles. Dependency graphs are restricted to packages inside the scope;
imports crossing its boundary count as external. Test size is included in
unfiltered scopes and excluded by production source-set filters.

The exporter runs missing commits in one JVM with `--refs-file`, reusing its
parser. It caches measurements under `build/metrics/history/` by the installed
analyzer and dependency versions, so local analyzer changes invalidate cached
measurements. It does not check out old code.

### Data and hosting

Generated data under `docs/public/metrics-data/` is ignored by Git:

- `index.json`: commit metadata and the available scopes.
- `series/<reporter>/<scope>.json`: summaries over time for one scope.
- `snapshots/<reporter>/<commit>/<scope>.json`: one scoped report for
  drill-down.

The index is written last. The browser initially fetches the index, one summary
series, and one snapshot. Comparison loads the baseline on demand. This keeps
module and source-set detail out of the initial download.

No R2 credentials, uploader, or publishing workflow is configured. The same
files can later be served through a read-only Pages Function backed by R2.
Reporter versions use separate paths. Monthly bundles are unnecessary for the
current scoped series and single-snapshot drill-down.

Build and verify the static site after generating data:

```sh
mise run metrics:test
mise run build:docs
mise run metrics:test-ui
```

The Chromium check serves `docs/dist` locally under the deployment prefix. It
checks scope filters, comparisons, sorting, chart selection, source links,
keyboard controls, narrow layouts, and fetch failures. Screenshots are written
to `build/metrics/screenshots/`. It needs at least three exported snapshots.
Rebuild the site after changing its source or data.

## Future performance dashboard

A performance page can share commit selection, chart interactions, and the
on-demand storage pattern. Keep its data separate: benchmark records need
scenario, device, backend, configuration, viewport, repetitions, and
median/range values. See the [benchmark workflow](../../benchmarks/README.md).
Implement shared components when that page establishes what both dashboards
need; code metrics have no device dimension.
