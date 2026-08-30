# 06: Reuse StyleComposition across map consumers

**What to build:** Add a reusable StyleComposition value that each map evaluates
in an independent Compose composition. Reconcile each complete immutable
revision while preserving native state and replaying Web state across
presentation loss.

**Blocked by:** 01, 04, 05

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] One StyleComposition definition can be supplied to two maps.
- [x] Each consumer receives independent remember state and effects.
- [x] Shared application state enters each evaluator through hoisted inputs.
- [x] An evaluator publishes a complete immutable ordered style revision.
- [x] A desired revision contains no engine map, live binding, or mutable
      definition.
- [x] Definition identity is resource kind plus ID; duplicate IDs fail
      evaluation and layer order is explicit.
- [x] Mutable payloads are defensively copied, and painter-backed images resolve
      to immutable payloads for the evaluator density and layout direction.
- [x] Custom provider replacement publishes a new complete revision.
- [x] Detachment disposes the evaluator without removing the last applied native
      revision.
- [x] Web replay applies the last desired revision to the replacement map.
- [x] Reattachment evaluates current external state before the first visible
      frame.
- [x] A base-style reload reconciles the complete latest revision.
- [x] Reconciliation failure reports Failed for the latest request, keeps the
      surface hidden, and can be superseded by a later revision or base style.

## Test ledger

- Rewrite `StyleCompositionOrderTest.kt`, `StyleOwnershipTest.kt`,
  `StyleNodeTest.kt`, `SymbolLayerCompositionTest.kt`,
  `MlnFfiStyleSwitchTest.kt`, and `BrowserMapStyleStateTest.kt` around complete
  immutable revisions and independent evaluators.
- Consolidate ordering and ownership semantics in common tests; keep live tests
  only for native and GL JS reconciliation boundaries.
- Run `mise run style-spec:parity --check`, `mise run test:android`,
  `mise run test:desktop`, and `mise run test:js`.

## Answer

`StyleComposition` is now a reusable value. Each map evaluates it in an
independent nested composition and publishes a complete immutable revision of
its sources, layers, images, ordering, and handlers. Persistent platform
reconcilers apply those revisions without tying desired state to a live style
binding.

Native detachment retains the last applied revision and reevaluates current
hoisted state before revealing a reattached presentation. Web presentations
replay the latest revision after engine replacement. Base-style and
reconciliation failures remain hidden and can be superseded by later requests.

The style-spec parity, Android host, Desktop, and browser test suites pass.
