# 05: Replay Web maps between presentations

**What to build:** Preserve one Web logical map while destroying its detached GL
JS map, then create a new GL JS map and restore the durable desired state on the
next presentation.

**Blocked by:** 03

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] Web destroys the GL JS map after detachment.
- [x] MapState retains the desired camera position and base style.
- [x] Detachment changes style load state to Pending without discarding desired
      style configuration.
- [x] Reattachment creates a new GL JS map identity.
- [x] The new map receives the retained desired state before its first visible
      frame.
- [x] MapState publishes a presentation when its host and viewport are usable,
      while the surface remains hidden until style reconciliation succeeds.
- [x] Style failure leaves the presentation attached, reports failure through
      MapStyleState, and keeps the placeholder visible.
- [x] Events from the destroyed map cannot update MapState.
- [x] A cached Web presentation fails after detachment.
- [x] Browser tests prove destruction, recreation, replay, and stale-event
      rejection.

## Test ledger

- Rewrite `BrowserMapLifecycleTest.kt`, `BrowserStyleStateTest.kt`, and
  `BrowserCameraTransitionLifecycleTest.kt` around logical state, engine
  identity, presentation readiness, and replay.
- Delete browser cases that only assert obsolete session callback storage or
  composition structure.
- Run `mise run test:js` under `caffeinate -dimsu` on macOS.

## Answer

Web map sessions now wait for a non-empty viewport before they publish a
presentation. The session applies the retained camera and presentation state
before it starts the initial style request. The Compose surface remains hidden
until both replay and style reconciliation complete.

Detachment destroys the GL JS map and resets the viewport, replay, and style
readiness state. `MapState` retains the desired camera and base style, reports
the style as `Pending`, and applies both values to a new GL JS map after the
next attachment. Events from the destroyed map and operations on its cached
presentation cannot modify the logical map.

The browser lifecycle, style-state, and camera-transition tests add coverage
through `MapState` and `MapPresentation`. They cover engine destruction and
recreation, camera and style replay, viewport-ready publication, style failure,
and stale event rejection. The tests retain the distinct attribution and queued
transition contracts. The removed cases duplicated detachment and recreation
behavior through the superseded compatibility callbacks.

Validation passed with `mise run check` and
`caffeinate -dimsu mise run test:js`. A sensitivity run failed when viewport
readiness was bypassed, as required.
