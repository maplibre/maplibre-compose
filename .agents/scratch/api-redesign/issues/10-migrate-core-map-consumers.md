# 10: Migrate core map consumers to the new ownership API

**What to build:** Move the core map, camera, source, layer, and style packages
to MapRuntime, MapState, MapPresentation, and StyleComposition. Keep ancillary
library modules out of this change.

**Blocked by:** 06, 07, 08, 09

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] Core map components use the new ownership model.
- [x] Shared tests for supported behavior use MapRuntime and MapState.
- [x] Tests for states that the new ownership model cannot represent are deleted
      rather than translated.
- [x] Duplicate internal tests are consolidated at the lifecycle authority or
      public API seam.
- [x] The core map, camera, style, sources, and layers packages no longer depend
      on superseded camera or style state types.
- [x] The new API requires no compatibility behavior from migrated library
      callers.
- [x] Common and focused platform tests pass after the migration.
- [x] The PR contains a final table classifying every affected test as retained,
      rewritten, consolidated, or deleted.

## Test ledger

- Inventory every test under the core `map`, `camera`, `style`, `sources`, and
  `layers` packages before migration and record each retained, rewritten,
  consolidated, or deleted contract in the PR.
- Keep shared ownership behavior in the common lifecycle and style suites; keep
  real-engine tests only for queries, rendering, source behavior, and other
  platform boundaries.
- Do not migrate overlay, location, Material 3, demo, documentation-snippet, or
  platform-test callers in this ticket.
- Run `mise run style-spec:parity --check`, `mise run test:android`,
  `mise run test:desktop`, `mise run test:ios`, and `mise run test:js`.

## Answer

`MaplibreMap` now consumes `MapState`, `MapPresentationOptions`, callbacks, and
`StyleComposition` directly. `MapState` stores the durable camera position and
applies it before presentation publication. Camera callbacks validate the
current adapter before they read platform state or update the durable value.

`compatibilityStyleState` remains an internal hook for the browser tests that
ticket 13 owns. No migrated production caller accepts or creates a superseded
camera or style state. Ticket 11 retains ownership of the overlay public API.

| Classification | Test                             |
| -------------- | -------------------------------- |
| Rewritten      | `MapPresentationTest`            |
| Retained       | `GestureMathTest`                |
| Retained       | `MapExtentTest`                  |
| Retained       | `MapLifecycleAuthorityTest`      |
| Retained       | `MapRuntimeTest`                 |
| Retained       | `TapPairingTest`                 |
| Retained       | `SymbolLayerCompositionTest`     |
| Retained       | `UnknownLayerJsonTest`           |
| Retained       | `CustomSourceDefinitionTest`     |
| Retained       | `GeoJsonConflationTest`          |
| Retained       | `RasterDemSourceJsonTest`        |
| Retained       | `SourceJsonTest`                 |
| Retained       | `TileCoordinateTest`             |
| Retained       | `StyleCompositionEvaluatorTest`  |
| Retained       | `StyleCompositionOrderTest`      |
| Retained       | `StyleDefinitionAndIdentityTest` |
| Retained       | `StyleNodeTest`                  |
| Retained       | `StyleOwnershipTest`             |
| Consolidated   | None                             |
| Deleted        | None                             |

The rewritten presentation tests cover delayed camera-event rejection before a
platform read and initial durable-camera application before publication. The
retained tests stay at their existing ownership, definition, serialization,
composition, revision, identity, reconciliation, or pure-function seams. The
inventory found no duplicate, compatibility-only, or impossible-state contract.

Validation passed with `mise run check`, `mise run style-spec:parity --check`,
`mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
`caffeinate -dimsu mise run test:js`.
