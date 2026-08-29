# 03: Render through MapRuntime and MapState

**What to build:** Add a complete public path that creates one logical map
through an explicit or process-owned runtime, attaches it to one MaplibreMap,
and exposes its current presentation. Keep the superseded public path
temporarily so later migrations remain green.

**Blocked by:** 02: Centralize the logical-map lifecycle

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] MapRuntime creates and tracks MapState children.
- [ ] rememberMapRuntime returns one process-owned default runtime.
- [ ] rememberMapState accepts an explicit runtime and defaults to the shared
      runtime.
- [ ] A MapState renders a basic base-style map on native and Web.
- [ ] MapState exposes at most one current MapPresentation.
- [ ] A rival attachment fails before logical or platform state changes.
- [ ] Closing a child does not close its runtime.
- [ ] Runtime closure closes every child before shared resources.
- [ ] close commits logical closure immediately, and awaitClosed observes
      completed cleanup.
