# 06: Isolate process-global native and cache tests

**What to build:** Run tests that open the shared FFI runtime or the shared
offline cache file in a serial Gradle process (or a dedicated test task) so they
cannot interleave schema creation with another runtime in the same JVM.

**Blocked by:** 01

**Type:** task

**Status:** ready-for-agent

Today `MlnFfiSharedCacheDatabaseTest` opens one cache from two runtimes on
purpose. Desktop logs still print
`Can't open database: table resources already exists` during unrelated failures
(maplibre-native-ffi#667). Other tests call `FfiTestPlatform.createCacheFile()`
and `MlnFfiApplication.resetForTest()` in the same process as the live suite.

- Give cache and dual-runtime tests their own `Test` task or `forkEvery = 1`
  class filter.
- Keep `resetForTest()` in `runFfiComposeUiTest` `finally`.
- Do not delete the dual-runtime case; it is the invariant the FFI issue tracks.

## Test ledger

- `two_runtimes_can_open_the_same_cache_database` still passes alone.
- An unrelated live test no longer opens a second schema into the same file
  while that case runs.
