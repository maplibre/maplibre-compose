# 07: Put viewport-bound behavior on MapPresentation

**What to build:** Complete MapPresentation as the only public owner of
viewport-dependent behavior. Keep the durable camera position on MapState and
bind every presentation operation to its render lease.

**Blocked by:** 04: Retain native engine maps between presentations; 05: Replay
Web maps between presentations

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] MapState exposes its durable camera position as read-only state.
- [ ] Camera set, fit, and animation operations belong to MapPresentation.
- [ ] Viewport, visible-region, projection, and rendered-feature queries belong
      to MapPresentation.
- [ ] Gesture state, gesture events, and presentation render settings belong to
      MapPresentation.
- [ ] Public observable values use Compose snapshot state.
- [ ] Suspending operations accept calls from any coroutine dispatcher and run
      engine work on the owner context.
- [ ] A cached presentation fails immediately after detachment.
- [ ] A detached operation never waits for or targets a future presentation.
- [ ] A replacement camera animation cancels only the prior camera mutation.
