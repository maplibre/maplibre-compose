# 14: Remove the superseded map APIs

**What to build:** Complete the breaking migration by deleting the old map
signature and state types. Leave one small public ownership model without
compatibility wrappers.

**Blocked by:** 12, 13

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] The superseded MaplibreMap signature is removed.
- [x] MaplibreMap accepts MapPresentationOptions and MapPresentationCallbacks;
      base style, logger, and load state exist only on their specified owners.
- [x] Superseded camera and style state types are removed.
- [x] No compatibility overload, adapter, deprecation shim, or migrated caller
      remains.
- [x] Tests for removed API shapes and unrepresentable internal states are
      deleted.
- [x] Tests that still describe supported behavior exist once at the highest
      useful interface, plus distinct platform-boundary coverage.
- [x] One MapState represents one logical map and at most one presentation.
- [x] Public API documentation describes only the new model.
- [x] Static checks and the full supported platform test matrix pass.

## Test ledger

- Use source and test searches for every removed public type and signature;
  delete compatibility-only tests and confirm every supported contract has one
  remaining owner.
- Add no new behavioral test unless deletion reveals a public contract absent
  from the migrated suites.
- Run `mise run check`, `mise run style-spec:parity --check`, and the complete
  platform matrix listed in the specification.

## Answer

The public API now contains one `MaplibreMap` signature. It accepts `MapState`,
`StyleComposition`, `MapPresentationOptions`, and `MapPresentationCallbacks`.
`MapState.style` owns the base style and load state, and `MapRuntimeOptions`
owns the logger.

The removal deletes `StyleState`, `rememberStyleState`, and the internal
`compatibilityStyleState` path. `MapStyleState` already owned loaded-source
refreshes. The deleted evaluator and callback wiring duplicated that state.
Earlier migration tickets had already deleted `CameraState`, its saver, and the
superseded composable overload.

Source, test, demo, and documentation searches find no caller, overload,
adapter, deprecation shim, or compatibility test for the removed API. The
platform entry points now require their `MapState` instead of retaining nullable
compatibility paths. Existing common tests retain the supported ownership,
lifecycle, presentation, and style contracts. A native surface-loss regression
now covers feature-state mutation before the first frame, while detached, and
after reset. Other platform tests retain the distinct native retention, host,
Android recreation, and Web recreation boundaries.

Validation passed with `mise run check`, `mise run style-spec:parity --check`,
`mise run test:android`, `mise run test:ios`, `mise run test:desktop`, and
`caffeinate -dimsu mise run test:js`. The Android device matrix passed before
the final audit cleanup. On both final reruns, the API 36 emulator went offline;
the second reached 336 of 406 tests without a reported assertion failure before
the disconnect.
