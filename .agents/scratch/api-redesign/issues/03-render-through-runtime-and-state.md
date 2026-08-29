# 03: Render through MapRuntime and MapState

**What to build:** Add a complete public path that creates one logical map
through an explicit or process-owned runtime, attaches it to one MaplibreMap,
and exposes its current presentation. Keep the superseded public path
temporarily so later migrations remain green.

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] MapRuntime creates and tracks MapState children.
- [ ] Independently configured OwnedMapRuntime instances can coexist and close
      independently.
- [ ] rememberMapRuntime returns one process-owned default runtime without a
      public closure operation.
- [ ] rememberMapState accepts an explicit runtime and defaults to the shared
      runtime.
- [ ] rememberMapState closes the state it creates when its call leaves
      composition; createMapState returns caller-owned state.
- [ ] Saveable restoration creates a new logical map, restores its camera
      position, and reapplies the caller's initial base style.
- [ ] A MapState renders a basic base-style map on native and Web.
- [ ] MapState exposes at most one current MapPresentation.
- [ ] A rival attachment fails before logical or platform state changes.
- [ ] Closing a child does not close its runtime.
- [ ] Owned-runtime closure closes every child before shared resources.
- [ ] OwnedMapRuntime.close commits logical closure immediately, and awaitClosed
      observes completed cleanup.

## Test ledger

- Add public-API tests for runtime child ordering, independently configured
  runtimes, remembered-state disposal, caller-owned state, and camera restore.
- Rewrite `AndroidCameraStateRecreationTest.kt`, `MapLibreConfigurationTest.kt`,
  and `MlnFfiOfflineRuntimeTest.kt` where their contracts remain public; remove
  process-singleton assertions.
- Run `mise run test:android`, `mise run test:android:device`,
  `mise run test:desktop`, and `mise run test:js`.
