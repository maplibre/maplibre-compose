# 06: Isolate process-global native and cache tests

**What to build:** Run tests that open the shared FFI runtime or the shared
offline cache file in a serial Gradle process (or a dedicated test task) so they
cannot interleave schema creation with another runtime in the same JVM.

**Blocked by:** 01

**Type:** task

**Status:** resolved

- [x] Give cache and dual-runtime tests their own `Test` task with
      `forkEvery = 1`.
- [x] Exclude those classes from the regular `jvmTest` task.
- [x] `mise run test:desktop` / `./gradlew jvmTest` still run them through
      `dependsOn`.
- [x] Keep `resetForTest()` in `runFfiComposeUiTest` `finally`.
- [x] Keep the dual-runtime case.

Today `MlnFfiSharedCacheDatabaseTest` opens one cache from two runtimes on
purpose. Desktop logs still print
`Can't open database: table resources already exists` during unrelated failures
(maplibre-native-ffi#667). Other tests call `FfiTestPlatform.createCacheFile()`
and `MlnFfiApplication.resetForTest()` in the same process as the live suite.

## Answer

`lib/maplibre-compose` registers `jvmProcessGlobalTest` in the verification
group. The task reuses the Kotlin Multiplatform `jvmTest` classpath, allowlists
the eight process-global classes, and sets `forkEvery = 1` so each class runs in
a new JVM. `jvmTest` excludes those classes and `dependsOn` the isolated task.
`mise run test:desktop` still runs `./gradlew jvmTest`, so CI keeps the coverage
without a unit filter and without a CI job change. `./gradlew jvmTest --tests`
still runs the isolated task in full. That is the same as two Gradle test tasks;
there is no command-line filter copier.

`runFfiComposeUiTest` still calls `MlnFfiApplication.resetForTest()` in
`finally`. `MapLibreConfigurationTest` stays intact, including
`independently_configured_runtimes_coexist_and_close_independently`. The whole
class is isolated because splitting that one method is messier than moving the
cheap path-math cases with it.

Isolated classes:

- `org.maplibre.compose.offline.MlnFfiSharedCacheDatabaseTest`
- `org.maplibre.compose.offline.MlnFfiOfflinePackTest`
- `org.maplibre.compose.offline.MlnFfiOfflineManagerTest`
- `org.maplibre.compose.offline.MlnFfiOfflineRuntimeTest`
- `org.maplibre.compose.map.PlatformMapAccessTest`
- `org.maplibre.compose.desktop.MapLibreConfigurationTest`
- `org.maplibre.compose.sources.ImageSourceAttachTest`
- `org.maplibre.compose.layers.UnsupportedLayerPropertyTest`

`AndroidExplicitRuntimeTest` stays in `androidDeviceTest`.

## Comments

### 2026-08-31 — maplibreNativeTest audit

Full tables: [maplibre-native-test-audit.md](../maplibre-native-test-audit.md).

Classes that _are_ the shared-cache problem:

1. `MlnFfiSharedCacheDatabaseTest` — two `RuntimeHandle`s, one file.
2. `MlnFfiOfflinePackTest`, `MlnFfiOfflineManagerTest`,
   `MlnFfiOfflineRuntimeTest` — open the cache schema without a map.
3. `PlatformMapAccessTest` — live `RuntimeImplementation` + cache, no GPU.
4. `ImageSourceAttachTest` — process-global `Maplibre.setLogCallback`.
5. `UnsupportedLayerPropertyTest` — process-global Kermit writer, never removed.

Every `BridgeMapFixture` / `createMapFixture` also opens a cache file in the
same process. `FileUrlTest` only loads the native library via
`createCacheFile()`; stop doing that and it drops off this list.

## Test ledger

- [x] `two_runtimes_can_use_the_same_cache_database` still passes alone.
- [x] An unrelated live test no longer opens a second schema into the same file
      while that case runs. `FileUrlTest` stays on the regular `jvmTest` task.
