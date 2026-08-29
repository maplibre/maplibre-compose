# 17: Report runtime capabilities

**What to build:** Expose exact offline-pack and ambient-cache capabilities
through MapRuntime, move OfflineManager ownership to the runtime, and make its
unsupported common operations fail explicitly.

**Blocked by:** 03, 14, 16

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] MapRuntimeCapabilities exposes supportsOfflinePacks and
      supportsAmbientCacheManagement.
- [ ] MapRuntime exposes its owned OfflineManager.
- [ ] OfflineManager is no longer documented or implemented as a process-wide
      singleton; each owned runtime has an independent manager.
- [ ] Native runtimes report the cache and offline operations that they support;
      Web reports both capabilities false and an empty pack set.
- [ ] Pack create, resume, pause, delete, invalidate, and tile-count-limit
      operations require supportsOfflinePacks.
- [ ] Ambient-cache invalidate, clear, and maximum-size operations require
      supportsAmbientCacheManagement.
- [ ] Calling an unsupported common operation throws
      UnsupportedOperationException.
- [ ] Ordinary Web map and snapshotter creation remains available.
- [ ] Runtime options remain independent from presentation-host configuration.
- [ ] Common and platform tests verify reported capabilities and failures.

## Test ledger

- Rewrite `MlnFfiOfflineRuntimeTest.kt`, `MlnFfiOfflineManagerTest.kt`, and
  `MapLibreConfigurationTest.kt` only where runtime ownership or capabilities
  replace their current seam.
- Add common capability/failure tests and one platform truth test per reported
  capability; do not repeat child-lifecycle coverage.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.
