# 11: Remove the superseded map APIs

**What to build:** Complete the breaking migration by deleting the old map
signature and state types. Leave one small public ownership model without
compatibility wrappers.

**Blocked by:** 09: Migrate library consumers to the new ownership API; 10:
Migrate demos, documentation, and platform tests

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] The superseded MaplibreMap signature is removed.
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
