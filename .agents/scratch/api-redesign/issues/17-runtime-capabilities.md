# 17: Report runtime capabilities

**What to build:** Expose exact offline-pack and ambient-cache capabilities
through MapRuntime, move OfflineManager ownership to the runtime, and make its
unsupported common operations fail explicitly.

**Blocked by:** 03, 14, 16

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] MapRuntimeCapabilities exposes supportsOfflinePacks and
      supportsAmbientCacheManagement.
- [x] MapRuntime exposes its owned OfflineManager.
- [x] OfflineManager is no longer documented or implemented as a process-wide
      singleton; each owned runtime has an independent manager.
- [x] Native runtimes report the cache and offline operations that they support;
      Web reports both capabilities false and an empty pack set.
- [x] Pack create, resume, pause, delete, and invalidate operations require
      supportsOfflinePacks.
- [x] Ambient-cache invalidate, clear, and maximum-size operations require
      supportsAmbientCacheManagement.
- [x] Calling an unsupported common operation throws
      UnsupportedOperationException.
- [x] Ordinary Web map and snapshotter creation remains available.
- [x] Runtime options remain independent from presentation-host configuration.
- [x] Common and platform tests verify reported capabilities and failures.

## Test ledger

- Rewrite `MlnFfiOfflineRuntimeTest.kt`, `MlnFfiOfflineManagerTest.kt`, and
  `MapLibreConfigurationTest.kt` only where runtime ownership or capabilities
  replace their current seam.
- Add common capability/failure tests and one platform truth test per reported
  capability; do not repeat child-lifecycle coverage.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.

## Answer

`MapRuntimeCapabilities` and the runtime-managed `OfflineManager` are now common
APIs. Native runtimes report both capabilities and create one native offline
manager per runtime. Runtime closure stops that manager after it closes the
runtime's map and snapshotter children. The common boundary rejects manager
operations and retained-pack metadata updates as soon as runtime closure starts.
Web reports both capabilities as false, exposes an empty pack set, and rejects
unsupported operations before they reach the platform backend. Web map-state and
snapshotter creation remain available.

The public offline types moved to common code. The process configuration no
longer constructs or stores an offline manager, and `rememberOfflineManager` was
removed. Documentation, demos, tests, and Material components now receive the
manager from `MapRuntime`. Native managers reject packs that belong to a
different manager. This prevents one runtime from targeting another runtime's
region ID. Pack definitions state their pixel ratio explicitly, and native packs
preserve it when the database is reopened.

Common tests cover every operation with mixed capability values. Desktop tests
verify native capability values and independent manager identities. Browser
tests verify false capability values, the empty pack set, explicit failures, and
unaffected child creation. A native cache test verifies foreign-pack rejection.
Deliberately bypassing one pack guard and one ambient-cache guard made both
common tests fail. Removing the runtime check from either a manager operation or
a retained pack's metadata update made the post-close test fail.

Static checks, style-spec parity, documentation, publication, Android host and
API 36 device, iOS simulator, Web, and Desktop tests pass.
