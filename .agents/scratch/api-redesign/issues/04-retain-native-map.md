# 04: Retain native engine maps between presentations

**What to build:** Preserve one native engine map when its MapState loses a UI
presentation, then attach that same engine map to the next presentation without
reloading its durable state. Replace the internal engine only when a later
presentation has an incompatible render backend or scale factor.

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Android, iOS, and Desktop create the engine map lazily.
- [ ] Detachment makes MapState.presentation null and invalidates the departed
      presentation.
- [ ] Reattachment to a compatible host uses the same native engine-map
      identity.
- [ ] An incompatible host replaces the engine identity, replays durable state,
      and leaves the logical MapState unchanged.
- [ ] The camera position and applied base style survive detachment.
- [ ] Current engine and style events remain observable while the native map is
      detached.
- [ ] A cached presentation fails after its lease ends.
- [ ] Closing a detached MapState releases the retained engine map.
- [ ] Native live-map tests prove compatible identity retention, incompatible
      replacement, replay, and stale-event rejection.

## Test ledger

- Rewrite the relevant cases in `MlnFfiMapCompositionTest.kt`,
  `MlnFfiSurfaceLossTest.kt`, `MlnFfiMapSurfaceRecoveryTest.kt`,
  `MlnFfiMapResizeTest.kt`, and `RenderBackendNegotiationTest.kt` around
  retained and replaced engine identities.
- Delete tests that preserve a session or surface ownership shape superseded by
  MapState and presentation leases.
- Run `mise run test:android`, `mise run test:desktop`, and `mise run test:ios`.
