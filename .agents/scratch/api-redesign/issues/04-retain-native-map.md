# 04: Retain native engine maps between presentations

**What to build:** Preserve one native engine map when its MapState loses a UI
presentation, then attach that same engine map to the next presentation without
reloading its durable state. Replace the internal engine only when a later
presentation has an incompatible render backend or scale factor.

**Blocked by:** 03

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] Android, iOS, and Desktop create the engine map lazily.
- [x] Detachment makes MapState.presentation null and invalidates the departed
      presentation.
- [x] Reattachment to a compatible host uses the same native engine-map
      identity.
- [x] An incompatible host replaces the engine identity, replays durable state,
      and leaves the logical MapState unchanged.
- [x] The camera position and applied base style survive detachment.
- [x] Current engine and style events remain observable while the native map is
      detached.
- [x] A cached presentation fails after its lease ends.
- [x] Closing a detached MapState releases the retained engine map.
- [x] Native live-map tests prove compatible identity retention, incompatible
      replacement, replay, and stale-event rejection.

## Test ledger

- Rewrite the relevant cases in `MlnFfiMapCompositionTest.kt`,
  `MlnFfiSurfaceLossTest.kt`, `MlnFfiMapSurfaceRecoveryTest.kt`,
  `MlnFfiMapResizeTest.kt`, and `RenderBackendNegotiationTest.kt` around
  retained and replaced engine identities.
- Delete tests that preserve a session or surface ownership shape superseded by
  MapState and presentation leases.
- Run `mise run test:android`, `mise run test:desktop`, and `mise run test:ios`.

## Answer

`MapState` retains a native map adapter after presentation detachment. A later
presentation reuses that adapter when the render backend and scale factor match.
An incompatible presentation closes the retained adapter, creates a new one, and
applies the durable camera and base style to it.

The retained adapter remains part of `MapState` cleanup while it is detached or
being replaced. Style-load events from the retained adapter remain observable
without a presentation. Events from a replaced adapter cannot update the logical
map. Style requests made while detached reach the retained engine, failures stay
observable through the logical state, and the desired base style reconciles on
reattachment.

The native Compose test covers compatible retention, incompatible scale-factor
replacement, replay, stale presentation invalidation, and detached closure.
Lifecycle-authority tests cover stale engine, style, presentation, and detach
events. Surface-loss, recovery, resize, and backend tests remain focused on
their distinct platform boundaries.

Validation passed with `mise run check`, `mise run test:android`,
`mise run test:desktop`, `mise run test:ios`, and
`caffeinate -dimsu mise run test:js`.
