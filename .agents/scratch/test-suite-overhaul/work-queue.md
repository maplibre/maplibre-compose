# Work queue

Rules: one commit per ticket. Full live desktop suite stays on every
architecture. Material 3 stays without tests.

| Order | Ticket | Work                                                                                                                                                                                      | Files                                                                      |
| ----- | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| 1     | 03     | Rewrite `GeoJsonSourceUpdateTest` pixels to query/handle. Rewrite `CustomVectorSourceTest.an_mvt_provider_renders_its_tile` to query. Keep `FeatureStateTest` as the layer-5 paint proof. | `GeoJsonSourceUpdateTest.kt`, `CustomVectorSourceTest.kt`                  |
| 2     | 04     | Move `MapOverlayTest` and `MaplibreLogoTest` to `commonTest` behind `supportsComposeRuntimeTests`. Stop `FileUrlTest` from calling `createCacheFile()`.                                   | those three + `ComposeRuntimeTestHost` if needed                           |
| 3     | 08     | Replace `waitUntilMap` wall-clock with fixture `pumpUntil`. Dump last events/errors on failure.                                                                                           | `BrowserMapTest.kt` and its callers                                        |
| 4     | 06     | Serial/`forkEvery` for `MlnFfiSharedCacheDatabaseTest` and dual-runtime / offline pack classes.                                                                                           | Gradle + those classes                                                     |
| 5     | 09     | Local `test:desktop-unit` allowlist of layer 0–2 JVM classes. CI still runs full `test:desktop`.                                                                                          | `build.gradle.kts`, `mise.toml`                                            |
| 6     | 07     | Host retain/replace/remount on `FakeMlnFfiMapHost`. Composition methods that still call `createNativeMapRuntime` stayed live. Android EGL replacement frame stays live.                   | `MlnFfiMapSurfaceReplacementTest.kt`, `MlnFfiMapHostSessionRequestTest.kt` |

Keep as-is (swarm consensus):

- `commonTest` and `lib/location*` fakes
- `MapInputRecognitionTest` (layer 1)
- `LayerPropertyRoundTripTest`, `MapQueryTest`, `MapCameraTransitionTest`,
  `CameraMoveReportingTest`
- `FeatureStateTest` paint, `ImageSourceDrawTest`, `MlnFfiMapPixelTest`,
  `BrowserCompositingTest`, `LinuxVulkanOpenGlInteropTest`
- `LayerClickOrderTest`, `AndroidMapStateRecreationTest`,
  `AndroidSurfaceReplacementTest` frame-after-replace
- `MlnFfiMapSurfaceRecoveryTest` already on `FakeMlnFfiMapHost`
