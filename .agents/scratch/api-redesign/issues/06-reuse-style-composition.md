# 06: Reuse StyleComposition across map consumers

**What to build:** Add a reusable StyleComposition value that each map evaluates
in an independent Compose composition. Reconcile each complete immutable
revision while preserving native state and replaying Web state across
presentation loss.

**Blocked by:** 01, 04, 05

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] One StyleComposition definition can be supplied to two maps.
- [ ] Each consumer receives independent remember state and effects.
- [ ] Shared application state enters each evaluator through hoisted inputs.
- [ ] An evaluator publishes a complete immutable ordered style revision.
- [ ] A desired revision contains no engine map, live binding, or mutable
      definition.
- [ ] Definition identity is resource kind plus ID; duplicate IDs fail
      evaluation and layer order is explicit.
- [ ] Mutable payloads are defensively copied, and painter-backed images resolve
      to immutable payloads for the evaluator density and layout direction.
- [ ] Custom provider replacement publishes a new complete revision.
- [ ] Detachment disposes the evaluator without removing the last applied native
      revision.
- [ ] Web replay applies the last desired revision to the replacement map.
- [ ] Reattachment evaluates current external state before the first visible
      frame.
- [ ] A base-style reload reconciles the complete latest revision.
- [ ] Reconciliation failure reports Failed for the latest request, keeps the
      surface hidden, and can be superseded by a later revision or base style.

## Test ledger

- Rewrite `StyleCompositionOrderTest.kt`, `StyleOwnershipTest.kt`,
  `StyleNodeTest.kt`, `SymbolLayerCompositionTest.kt`,
  `MlnFfiStyleSwitchTest.kt`, and `BrowserStyleStateTest.kt` around complete
  immutable revisions and independent evaluators.
- Consolidate ordering and ownership semantics in common tests; keep live tests
  only for native and GL JS reconciliation boundaries.
- Run `mise run style-spec:parity --check`, `mise run test:android`,
  `mise run test:desktop`, and `mise run test:js`.
