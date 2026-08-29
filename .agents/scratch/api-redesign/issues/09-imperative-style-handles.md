# 09: Expose generation-bound imperative style handles

**What to build:** Expose imperative source and layer access through live
handles that belong to exactly one loaded style identity. Keep persistent
application content in StyleComposition.

**Blocked by:** 01, 06

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Each live handle combines a resource ID with one opaque style identity.
- [ ] A handle can mutate only the loaded style that created it.
- [ ] Starting a base-style reload invalidates every outgoing handle.
- [ ] A stale handle fails clearly without mutating a replacement style or
      another map.
- [ ] Imperative mutations disappear after a base-style reload.
- [ ] Persistent sources, layers, and images remain the responsibility of
      StyleComposition.
- [ ] Immutable definitions remain reusable across maps and snapshotters.
- [ ] Typed source handles cover transient data updates, feature state, source
      and cluster queries, and custom-source invalidation.
- [ ] Typed layer handles cover imperative property access.
- [ ] Base-style resources are acquired by ID only after MapStyleState is ready.

## Test ledger

- Rewrite `FeatureStateTest.kt`, `GeoJsonClusterTest.kt`,
  `BaseStyleSourceReadTest.kt`, `GeoJsonSourceUpdateTest.kt`,
  `GeoJsonSourceStyleReloadTest.kt`, `VectorSourceQueryTest.kt`, and
  custom-source invalidation tests through typed generation-bound handles.
- Consolidate stale-handle semantics in common tests and keep platform tests for
  each distinct engine operation only.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.
