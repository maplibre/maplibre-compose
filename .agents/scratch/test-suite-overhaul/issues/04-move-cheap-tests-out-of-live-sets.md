# 04: Move cheap tests out of live source sets

**What to build:** Relocate tests that do not need a MapLibre runtime or a GPU
out of `liveMapTest` and `maplibreNativeTest` into `commonTest` or a native-only
unit set that android host can run when the types allow it.

**Blocked by:** 01

**Type:** task

**Status:** resolved

- [x] `MapOverlayTest` and `MaplibreLogoTest` live in `commonTest` and return
      before `runComposeUiTest` when `supportsComposeRuntimeTests` is false.
- [x] `FileUrlTest` uses a `Path` under `SystemTemporaryDirectory` and does not
      call `FfiTestPlatform`.

Classes that import `org.maplibre.nativeffi` stay in `maplibreNativeTest`.
Ticket 09 allowlists them for the desktop unit filter:

- `JsonConversionsTest`, `MlnFfiConversionsTest`
- `OfflineProgressMappingTest`
- `MlnFfiResourceProviderTest`, `MlnFfiResourceRequestTest`
- `MlnFfiTileRequestCoordinatorTest`
- `ImagePremultiplyTest`

Do not move a class that calls `BridgeMapFixture.create`, `createMapFixture`, or
`runFfiComposeUiTest`.

## Comments

### 2026-08-31 — maplibreNativeTest audit

Full class tables:
[maplibre-native-test-audit.md](../maplibre-native-test-audit.md).

38 classes, 146 methods. 11 cheap (65 methods) create no map, GPU, or live
session. Extra cheap rows ticket 04 did not list:
`RenderBackendNegotiationTest`, `MlnFfiFeatureStateStoreTest`.
`MlnFfiMapSurfaceRecoveryTest` is already layer 2 (`FakeMlnFfiMapHost`); keep it
in this source set and put it on the unit filter.

`FileUrlTest` used to call `createCacheFile()`, which loads the native library
for a temp path. It now creates a directory under `SystemTemporaryDirectory`.

Types that import `org.maplibre.nativeffi` and must stay native:
`MlnFfiConversionsTest`, `OfflineProgressMappingTest`,
`MlnFfiResourceRequestTest`, `MlnFfiTileRequestCoordinatorTest`,
`ImagePremultiplyTest` (`PremultipliedRgba8Image`).

## Answer

`MapOverlayTest` and `MaplibreLogoTest` compose against `mapRuntimeForTest` and
need a Compose UI host, not a live map. They live in `commonTest` with the other
Compose-only tests. Each `@Test` returns when `supportsComposeRuntimeTests` is
false. `androidHost` does not call `runComposeUiTest`.

`FileUrlTest` creates its directory under `SystemTemporaryDirectory` in
`@BeforeTest` and deletes the file that the existence case writes. The four
`fileUrlOf` / `pathOfFileUrl` assertions are unchanged. The class obtains the
path without `FfiTestPlatform` and without loading the native library.

## Test ledger

- `mise run test:android` compiles the overlay cases and returns before
  `runComposeUiTest` (`supportsComposeRuntimeTests` is false on the host).
- `mise run test:android:device` and desktop/JS still compose them.
- `FileUrlTest` does not load the native library.
