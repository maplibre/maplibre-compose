# Code metrics

A snapshot of the Kotlin under `lib/` and `demo-app/` at one commit: size,
complexity, package dependencies, abstractions, API surface, and churn. Compare
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
- `abstractions`: every interface and abstract class with its implementation
  counts in main and test code.
- `distributions` and `largest`: how file, type, and function sizes spread, and
  which are biggest.
- `churn`: commits per file over the last 180 days, and hotspots by commits
  times lines.

`Report.kt` documents each field.
