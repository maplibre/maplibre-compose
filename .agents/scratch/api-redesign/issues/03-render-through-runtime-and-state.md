# 03: Render through MapRuntime and MapState

**What to build:** Add a complete public path that creates one logical map
through an explicit or process-default runtime, attaches it to one MaplibreMap,
and exposes its current presentation. Keep the superseded public path
temporarily so later migrations remain green.

**Blocked by:** 02

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] MapRuntime creates and tracks MapState children.
- [x] Independently configured MapRuntime instances can coexist and close
      independently.
- [x] rememberMapRuntime returns one process-default runtime. Closing it
      permanently closes that instance.
- [x] rememberMapState accepts an explicit runtime and defaults to the shared
      runtime.
- [x] rememberMapState closes the state it creates when its call leaves
      composition; the caller closes a state from createMapState.
- [x] Saveable restoration creates a new logical map, restores its camera
      position, and reapplies the caller's initial base style.
- [x] A MapState renders a basic base-style map on native and Web.
- [x] MapState exposes at most one current MapPresentation.
- [x] A rival attachment fails before logical or platform state changes.
- [x] Closing a child does not close its runtime.
- [x] Runtime closure closes every child before shared resources.
- [x] MapRuntime.close commits logical closure immediately, and awaitClosed
      observes completed cleanup.

## Test ledger

- Add public-API tests for runtime child ordering, independently configured
  runtimes, remembered-state disposal, caller-closed state, and camera restore.
- Rewrite `AndroidMapStateRecreationTest.kt`, `MapLibreConfigurationTest.kt`,
  and `MlnFfiOfflineRuntimeTest.kt` where their contracts remain public; remove
  process-singleton assertions.
- Run `mise run test:android`, `mise run test:android:device`,
  `mise run test:desktop`, and `mise run test:js`.

## Answer

`MapRuntime` now creates and tracks logical `MapState` children. Every runtime
exposes the same closure API. Closing the process default permanently closes
that instance. Native runtime options are passed directly to each map session,
so independently configured runtimes can render and close without changing the
legacy process configuration.

`rememberMapState` closes the state when its call leaves composition. Its saver
creates a new state with the saved camera position and the caller's current
initial base style. `MaplibreMap(state)` reserves one presentation before it
creates a platform map, publishes that presentation after attachment, and
releases it during disposal. The previous overload remains available for later
migration tickets.

The new common, browser, native composition, Android recreation, and desktop
configuration tests cover runtime closure order, independent runtimes,
remembered and caller-closed lifetimes, restoration, base-style rendering, and
single-presentation rejection. The Android host, Android device, Desktop, and
Web suites pass. A temporary mutation that skipped child closure made the
closure-order regression test fail. This result confirms that the test exercises
the production invariant.
