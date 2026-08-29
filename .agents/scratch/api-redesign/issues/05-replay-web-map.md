# 05: Replay Web maps between presentations

**What to build:** Preserve one Web logical map while destroying its detached GL
JS map, then create a new GL JS map and restore the durable desired state on the
next presentation.

**Blocked by:** 03: Render through MapRuntime and MapState

**Status:** ready-for-agent

- [ ] Web destroys the GL JS map after detachment.
- [ ] MapState retains the desired camera position and base style.
- [ ] Reattachment creates a new GL JS map identity.
- [ ] The new map receives the retained desired state before its first visible
      frame.
- [ ] Events from the destroyed map cannot update MapState.
- [ ] A cached Web presentation fails after detachment.
- [ ] Browser tests prove destruction, recreation, replay, and stale-event
      rejection.
