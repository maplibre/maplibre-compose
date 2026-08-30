# 12: Migrate demo applications

**What to build:** Convert every demo to the new map ownership API so that each
platform demonstrates the same runtime, logical-map, presentation, and style
composition model.

**Blocked by:** 10, 11

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] Every demo creates or remembers MapState through MapRuntime.
- [x] Files under `org/maplibre/compose/docsnippets` are excluded and remain the
      sole responsibility of ticket 13.
- [x] Demo style content uses reusable StyleComposition values.
- [x] Viewport-dependent demo behavior uses the current MapPresentation.
- [x] Android, iOS, Desktop, and Web demos build successfully.

## Test ledger

- Update demo compile coverage and `BenchmarkStatsTest.kt` only if its caller
  surface changes; do not add behavioral copies of library tests.
- Run `mise run build:android-app`, `mise run build:ios:device`,
  `mise run build:desktop-app`, and `mise run build:js-app`.

## Answer

`DemoAppState` remembers the process runtime and the shared logical map through
that runtime. One application runtime owns the shared, benchmark, and
magnifying-lens logical maps. Each logical map has an independent presentation.

The demos supply style content as `StyleComposition` values and resolve
viewport-dependent work through each map's current `MapPresentation`.
Documentation snippets remain unchanged for ticket 13.

`BenchmarkStatsTest.kt` remains unchanged because its statistics contract and
caller surface did not change. The demo migration adds no behavior that needs a
copy of the library ownership tests.

Validation passed with `mise run build:android-app`,
`mise run build:ios:device`, `mise run build:desktop-app`, and
`mise run build:js-app`.
