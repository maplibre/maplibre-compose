# 09: Add a desktop unit Gradle filter

**What to build:** A `-Pmaplibre.tests=unit|all` switch (or a `jvmUnitTest`
task) that runs layers 0–2 on the JVM without loading a GPU render driver. This
is a local task. CI desktop jobs keep running the full live suite on every
architecture.

**Blocked by:** 04

**Type:** task

**Status:** resolved

KMP compiles `commonTest`, `liveMapTest`, `maplibreNativeTest`, and `jvmTest`
into one `jvmTest` task, so source sets cannot be run apart at execution time
without a filter.

Prefer an explicit class allowlist or JUnit tags over package excludes.
`MlnFfiConversionsTest` and `MlnFfiMapPixelTest` share a package prefix.

`mise run test:desktop` stays `all` and is what CI runs. A new
`mise run test:desktop-unit` runs the filter for machines with no GPU.

## Comments

### 2026-08-31 — maplibreNativeTest allowlist

From [maplibre-native-test-audit.md](../maplibre-native-test-audit.md), 11
classes (65 methods) belong on the unit allowlist. They share no GPU and no live
session:

`JsonConversionsTest`, `MlnFfiConversionsTest`, `OfflineProgressMappingTest`,
`MlnFfiResourceProviderTest`, `MlnFfiResourceRequestTest`,
`MlnFfiTileRequestCoordinatorTest`, `FileUrlTest`, `ImagePremultiplyTest`,
`RenderBackendNegotiationTest`, `MlnFfiFeatureStateStoreTest`,
`MlnFfiMapSurfaceRecoveryTest`.

Do not allowlist `PlatformMapAccessTest` or any `MlnFfiOffline*` class. They
create no GPU but they open a runtime or cache. Point `FileUrlTest` at a `Path`
first, or `createCacheFile()` still loads the native library.

- [x] `-Pmaplibre.tests=unit` allowlists layer 0–2 JVM classes by full name.
- [x] `mise run test:desktop-unit` runs that filter.
- [x] `mise run test:desktop` stays `all` and is what CI runs.
- [x] Process-global classes stay off the unit allowlist.

## Answer

`lib/maplibre-compose/build.gradle.kts` reads `-Pmaplibre.tests=unit` and
allowlists layer 0–2 classes by fully qualified name. The list includes
`commonTest`, FakeHost recovery and replacement, native conversions, and
Compose-only overlay cases. It omits `MlnFfiMapPixelTest`, every
`BridgeMapFixture` class, `PlatformMapAccessTest`, the `MlnFfiOffline*` classes,
and `MapLibreConfigurationTest`.

`jvmProcessGlobalTest` is disabled under the unit filter so a machine with no
GPU does not open a cache or a second runtime.

## Test ledger

- [x] `test:desktop-unit` creates no Vulkan/Metal/D3D context.
- [x] `test:desktop` still runs every live class on a machine with a GPU.
- [x] No CI job switches from `test:desktop` to the unit filter.
