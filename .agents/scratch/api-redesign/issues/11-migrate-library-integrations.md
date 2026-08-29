# 11: Migrate library integrations to the new ownership API

**What to build:** Move the overlay and location packages plus the Material 3
module onto the completed core ownership API without retaining adapters for
superseded map, camera, or style state.

**Blocked by:** 10

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Overlay projection and placement use the current MapPresentation.
- [ ] Location components use durable MapState values and current-presentation
      operations at the appropriate boundaries.
- [ ] Material 3 controls target MapState or MapPresentation according to the
      operation they perform.
- [ ] No integration retains a cached presentation across detachment.
- [ ] Tests for removed state combinations are deleted rather than recreated.
- [ ] Duplicate ownership tests remain in the core suite rather than every
      integration module.
- [ ] Focused common and platform tests for each changed module pass.
- [ ] The PR contains a final table classifying every affected test as retained,
      rewritten, consolidated, or deleted.

## Test ledger

- Rewrite `MapOverlayTest.kt`, `MaplibreLogoTest.kt`, `LocationPuckTest.kt`, and
  `LocationStateTest.kt` only where they exercise the migrated boundary.
- Limit production changes to the `overlay` and `location` packages in
  `lib/maplibre-compose` and the `lib/maplibre-compose-material3` module. Any
  additional package requires an explicit ticket update before implementation.
- Preserve pure location-provider tests unchanged and do not duplicate core
  MapState or presentation lifecycle scenarios in integration modules.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.
