# 10: Migrate core map consumers to the new ownership API

**What to build:** Move the core map, camera, source, layer, and style packages
to MapRuntime, MapState, MapPresentation, and StyleComposition. Keep ancillary
library modules out of this change.

**Blocked by:** 06, 07, 08, 09

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Core map components use the new ownership model.
- [ ] Shared tests for supported behavior use MapRuntime and MapState.
- [ ] Tests for states that the new ownership model cannot represent are deleted
      rather than translated.
- [ ] Duplicate internal tests are consolidated at the lifecycle authority or
      public API seam.
- [ ] The core map, camera, style, sources, and layers packages no longer depend
      on superseded camera or style state types.
- [ ] The new API requires no compatibility behavior from migrated library
      callers.
- [ ] Common and focused platform tests pass after the migration.
- [ ] The PR contains a final table classifying every affected test as retained,
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
