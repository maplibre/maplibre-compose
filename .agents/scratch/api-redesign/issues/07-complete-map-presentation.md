# 07: Put viewport-bound behavior on MapPresentation

**What to build:** Complete MapPresentation as the only public owner of
viewport-dependent behavior. Keep the durable camera position on MapState and
bind every presentation operation to its render lease.

**Blocked by:** 04, 05

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] MapState exposes its durable camera position as read-only state.
- [ ] Camera set, fit, and animation operations belong to MapPresentation.
- [ ] Viewport, visible-region, projection, and rendered-feature queries belong
      to MapPresentation.
- [ ] Gesture state, gesture events, and presentation render settings belong to
      MapPresentation.
- [ ] Camera padding, camera constraints, render, gesture, and tile-LOD options
      enter MaplibreMap as one immutable MapPresentationOptions value.
- [ ] Map click, long-click, and frame callbacks enter MaplibreMap as one
      MapPresentationCallbacks value.
- [ ] Style loading is observed through MapStyleState rather than duplicate
      composable callbacks; logging belongs to MapRuntimeOptions.
- [ ] Overlay and content-window insets remain UI-only MaplibreMap inputs.
- [ ] Public observable values use Compose snapshot state.
- [ ] Suspending operations accept calls from any coroutine dispatcher and run
      engine work on the owner context.
- [ ] A cached presentation fails immediately after detachment.
- [ ] A detached operation never waits for or targets a future presentation.
- [ ] A replacement camera animation cancels only the prior camera mutation.

## Test ledger

- Rewrite `CameraMoveReportingTest.kt`, `MapCameraTransitionTest.kt`,
  `MapQueryTest.kt`, `MapVisibleAreaTest.kt`, `MapTileLodTest.kt`,
  `MapLoadReportingTest.kt`, `StyleFailureTest.kt`, `LayerClickOrderTest.kt`,
  and relevant `MlnFfiMapInputTest.kt` cases through MapPresentation.
- Delete tests for CameraState waiting on a future map and duplicate
  adapter-level checks once lease behavior is covered by the common lifecycle
  suite.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.
