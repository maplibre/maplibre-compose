# 07: Put viewport-bound behavior on MapPresentation

**What to build:** Complete MapPresentation as the only public owner of
viewport-dependent behavior. Keep the durable camera position on MapState and
bind every presentation operation to its render lease.

**Blocked by:** 04, 05

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] MapState exposes its durable camera position as read-only state.
- [x] Camera set, fit, and animation operations belong to MapPresentation.
- [x] Viewport, visible-region, projection, and rendered-feature queries belong
      to MapPresentation.
- [x] Gesture state, gesture events, and presentation render settings belong to
      MapPresentation.
- [x] Camera padding, camera constraints, render, gesture, and tile-LOD options
      enter MaplibreMap as one immutable MapPresentationOptions value.
- [x] Map click, long-click, and frame callbacks enter MaplibreMap as one
      MapPresentationCallbacks value.
- [x] Style loading is observed through MapStyleState rather than duplicate
      composable callbacks; logging belongs to MapRuntimeOptions.
- [x] Overlay and content-window insets remain UI-only MaplibreMap inputs.
- [x] Public observable values use Compose snapshot state.
- [x] Suspending operations accept calls from any coroutine dispatcher and run
      engine work on the owner context.
- [x] A cached presentation fails immediately after detachment.
- [x] A detached operation never waits for or targets a future presentation.
- [x] A replacement camera animation cancels only the prior camera mutation.

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

## Answer

`MapState` now retains a read-only camera position and publishes one snapshot-
observable `MapPresentation` for the current render lease. Camera mutations,
visible-area reads, projection, rendered-feature queries, movement state, and
presentation settings are available only through that lease. Detachment
invalidates cached presentations immediately and cancels in-flight lease-bound
work. A new camera animation cancels the previous camera mutation without
cancelling unrelated work.

`MaplibreMap` now accepts one immutable `MapPresentationOptions` value and one
`MapPresentationCallbacks` value. Base-style load status is observed through
`MapStyleState`, runtime options configure logging, and overlays receive
`MapState` plus the current presentation. The demo, controls, location
integration, benchmarks, documentation snippets, and platform tests use the new
API. The public `CameraState`, its saver, the superseded composable signature,
and compatibility-only tests were removed.

Validation passed with `mise run check`, `mise run test:android`,
`mise run test:desktop`, `mise run test:ios`, and
`caffeinate -dimsu mise run test:js`.
