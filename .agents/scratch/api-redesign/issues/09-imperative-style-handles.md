# 09: Expose generation-bound imperative style handles

**What to build:** Expose imperative source and layer access through live
handles that belong to exactly one loaded style identity. Keep persistent
application content in StyleComposition.

**Blocked by:** 01, 06

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] Each live handle combines a resource ID with one opaque style identity.
- [x] A handle can mutate only the loaded style that created it.
- [x] Starting a base-style reload invalidates every outgoing handle.
- [x] A stale handle fails clearly without mutating a replacement style or
      another map.
- [x] Imperative mutations disappear after a base-style reload.
- [x] Persistent sources, layers, and images remain the responsibility of
      StyleComposition.
- [x] Immutable definitions remain reusable across maps and snapshotters.
- [x] Typed source handles cover transient data updates, feature state, source
      and cluster queries, and custom-source invalidation.
- [x] Typed layer handles cover imperative property access.
- [x] Base-style resources are acquired by ID only after MapStyleState is ready.

## Test ledger

- Rewrite `FeatureStateTest.kt`, `GeoJsonClusterTest.kt`,
  `BaseStyleSourceReadTest.kt`, `GeoJsonSourceUpdateTest.kt`,
  `GeoJsonSourceStyleReloadTest.kt`, `VectorSourceQueryTest.kt`, and
  custom-source invalidation tests through typed generation-bound handles.
- Consolidate stale-handle semantics in common tests and keep platform tests for
  each distinct engine operation only.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.

## Answer

`MapStyleState` acquires typed source and layer handles from the current ready
loaded style. Each handle carries that style identity, and every operation
checks the identity before and after platform work so a reload or engine
replacement cannot redirect a cached handle.

The public source handle subtypes cover GeoJSON mutation, feature state, source
and cluster queries, and custom-source invalidation. `LayerHandle` covers
imperative property access. Persistent resources remain immutable
`StyleComposition` definitions; imperative mutations are not replayed after a
base-style reload.

| Classification | Test                           |
| -------------- | ------------------------------ |
| Rewritten      | `MapPresentationTest`          |
| Rewritten      | `GeoJsonSourceUpdateTest`      |
| Rewritten      | `CustomGeometrySourceTest`     |
| Rewritten      | `CustomVectorSourceNativeTest` |
| Added          | `LayerHandlePropertyTest`      |
| Added          | `FeatureStateTest`             |
| Added          | `GeoJsonClusterTest`           |
| Added          | `BaseStyleSourceReadTest`      |
| Added          | `GeoJsonSourceStyleReloadTest` |
| Added          | `VectorSourceQueryTest`        |
| Consolidated   | Common stale-handle semantics  |
| Deleted        | None                           |

The common presentation suite owns style identity, type replacement, engine
replacement, and stale-operation failures. Real-engine tests remain only for
distinct property, mutation, query, reload, and invalidation boundaries. No
superseded binding-layer test was restored.

Validation passed with `mise run check`, `mise run style-spec:parity --check`,
`mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
`caffeinate -dimsu mise run test:js`.
