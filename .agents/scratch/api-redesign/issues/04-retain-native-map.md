# 04: Retain native engine maps between presentations

**What to build:** Preserve one native engine map when its MapState loses a UI
presentation, then attach that same engine map to the next presentation without
reloading its durable state.

**Blocked by:** 03: Render through MapRuntime and MapState

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Android, iOS, and Desktop create the engine map lazily.
- [ ] Detachment makes MapState.presentation null and invalidates the departed
      presentation.
- [ ] Reattachment uses the same native engine-map identity.
- [ ] The camera position and applied base style survive detachment.
- [ ] A cached presentation fails after its lease ends.
- [ ] Closing a detached MapState releases the retained engine map.
- [ ] Native live-map tests prove engine identity and state retention.
