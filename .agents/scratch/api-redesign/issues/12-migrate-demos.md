# 12: Migrate demo applications

**What to build:** Convert every demo to the new map ownership API so that each
platform demonstrates the same runtime, logical-map, presentation, and style
composition model.

**Blocked by:** 10, 11

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Every demo creates or remembers MapState through MapRuntime.
- [ ] Files under `org/maplibre/compose/docsnippets` are excluded and remain the
      sole responsibility of ticket 13.
- [ ] Demo style content uses reusable StyleComposition values.
- [ ] Viewport-dependent demo behavior uses the current MapPresentation.
- [ ] Android, iOS, Desktop, and Web demos build successfully.

## Test ledger

- Update demo compile coverage and `BenchmarkStatsTest.kt` only if its caller
  surface changes; do not add behavioral copies of library tests.
- Run `mise run build:android-app`, `mise run build:ios:device`,
  `mise run build:desktop-app`, and `mise run build:js-app`.
