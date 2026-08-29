# 14: Remove the superseded map APIs

**What to build:** Complete the breaking migration by deleting the old map
signature and state types. Leave one small public ownership model without
compatibility wrappers.

**Blocked by:** 12, 13

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] The superseded MaplibreMap signature is removed.
- [ ] MaplibreMap accepts MapPresentationOptions and MapPresentationCallbacks;
      base style, logger, and load state exist only on their specified owners.
- [ ] Superseded camera and style state types are removed.
- [ ] No compatibility overload, adapter, deprecation shim, or migrated caller
      remains.
- [ ] Tests for removed API shapes and unrepresentable internal states are
      deleted.
- [ ] Tests that still describe supported behavior exist once at the highest
      useful interface, plus distinct platform-boundary coverage.
- [ ] One MapState represents one logical map and at most one presentation.
- [ ] Public API documentation describes only the new model.
- [ ] Static checks and the full supported platform test matrix pass.

## Test ledger

- Use source and test searches for every removed public type and signature;
  delete compatibility-only tests and confirm every supported contract has one
  remaining owner.
- Add no new behavioral test unless deletion reveals a public contract absent
  from the migrated suites.
- Run `mise run check`, `mise run style-spec:parity --check`, and the complete
  platform matrix listed in the specification.
