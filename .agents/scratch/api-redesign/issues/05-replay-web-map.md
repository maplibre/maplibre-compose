# 05: Replay Web maps between presentations

**What to build:** Preserve one Web logical map while destroying its detached GL
JS map, then create a new GL JS map and restore the durable desired state on the
next presentation.

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Web destroys the GL JS map after detachment.
- [ ] MapState retains the desired camera position and base style.
- [ ] Detachment changes style load state to Pending without discarding desired
      style configuration.
- [ ] Reattachment creates a new GL JS map identity.
- [ ] The new map receives the retained desired state before its first visible
      frame.
- [ ] MapState publishes a presentation when its host and viewport are usable,
      while the surface remains hidden until style reconciliation succeeds.
- [ ] Style failure leaves the presentation attached, reports failure through
      MapStyleState, and keeps the placeholder visible.
- [ ] Events from the destroyed map cannot update MapState.
- [ ] A cached Web presentation fails after detachment.
- [ ] Browser tests prove destruction, recreation, replay, and stale-event
      rejection.

## Test ledger

- Rewrite `BrowserMapLifecycleTest.kt`, `BrowserStyleStateTest.kt`, and
  `BrowserCameraTransitionLifecycleTest.kt` around logical state, engine
  identity, presentation readiness, and replay.
- Delete browser cases that only assert obsolete session callback storage or
  composition structure.
- Run `mise run test:js` under `caffeinate -dimsu` on macOS.
