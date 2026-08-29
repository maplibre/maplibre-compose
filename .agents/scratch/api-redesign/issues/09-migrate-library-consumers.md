# 09: Migrate library consumers to the new ownership API

**What to build:** Move library components and shared tests to MapRuntime,
MapState, MapPresentation, and StyleComposition while preserving their
user-visible behavior.

**Blocked by:** 06: Reuse StyleComposition across map consumers; 07: Put
viewport-bound behavior on MapPresentation; 08: Separate desktop presentation
hosts from runtimes

**Status:** ready-for-agent

- [ ] Core map components use the new ownership model.
- [ ] Material, location, overlay, and other library integrations use the new
      state and presentation boundaries where applicable.
- [ ] Shared tests create maps through MapRuntime and MapState.
- [ ] Library code no longer depends on superseded camera or style state types.
- [ ] The new API requires no compatibility behavior from migrated library
      callers.
- [ ] Common and focused platform tests pass after the migration.
