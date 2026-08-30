# 11: Migrate library integrations to the new ownership API

**What to build:** Move the overlay and location packages plus the Material 3
module onto the completed core ownership API without retaining adapters for
superseded map, camera, or style state.

**Blocked by:** 10

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] Overlay projection and placement use the current MapPresentation.
- [x] Location components use durable MapState values and current-presentation
      operations at the appropriate boundaries.
- [x] Material 3 controls target MapState or MapPresentation according to the
      operation they perform.
- [x] No integration retains a cached presentation across detachment.
- [x] Tests for removed state combinations are deleted rather than recreated.
- [x] Duplicate ownership tests remain in the core suite rather than every
      integration module.
- [x] Focused common and platform tests for each changed module pass.
- [x] The PR contains a final table classifying every affected test as retained,
      rewritten, consolidated, or deleted.

## Test ledger

- Rewrite `MapOverlayTest.kt`, `MaplibreLogoTest.kt`, `LocationPuckTest.kt`, and
  `LocationStateTest.kt` only where they exercise the migrated boundary.
- Limit production changes to the `overlay` and `location` packages in
  `lib/maplibre-compose` and the `lib/maplibre-compose-material3` module. Any
  additional package requires an explicit ticket update before implementation.
- Add loaded-source enumeration to `MapStyleState` in the core `map` package.
  Attribution controls require this current style metadata to stop depending on
  the superseded `StyleState`.
- Preserve pure location-provider tests unchanged and do not duplicate core
  MapState or presentation lifecycle scenarios in integration modules.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.

## Answer

`MapOverlayScope` now derives its presentation and style from `MapState`.
Projection and placement resolve the current presentation during layout, and the
overlay host no longer stores a presentation lease. Attribution controls use
`MapStyleState` instead of the superseded `StyleState`.

`MapStyleState.sources` exposes generation-bound handles for the current ready
style. The map refreshes that collection when a style becomes ready and when a
source changes. This supplies attribution metadata without a compatibility
state.

The location APIs already use `MapState` for the durable camera position and
`MapPresentation` for viewport and camera operations. Their production code and
provider tests remain unchanged. The Material 3 wrappers preserve the same
ownership boundaries as the base controls.

| Classification | Test                      |
| -------------- | ------------------------- |
| Rewritten      | `MapOverlayTest`          |
| Rewritten      | `BaseStyleSourceReadTest` |
| Retained       | `MaplibreLogoTest`        |
| Retained       | `LocationPuckTest`        |
| Retained       | `LocationStateTest`       |
| Consolidated   | None                      |
| Deleted        | None                      |

`MapOverlayTest` now exercises the host through `MapState`. The source-read test
verifies the current source collection and its attribution through the public
style API. The retained logo, puck, and provider tests cover distinct artwork,
measurement, staleness, lifecycle, permission, failure, and retry behavior.

Validation passed with `mise run check`, `mise run test:android`,
`mise run test:desktop`, `mise run test:ios`, and
`caffeinate -dimsu mise run test:js`.
