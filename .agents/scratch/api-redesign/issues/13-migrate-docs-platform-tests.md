# 13: Migrate documentation and platform tests

**What to build:** Update public documentation, compiled snippets, and
real-engine tests to describe and exercise only the new API. Keep shared
lifecycle semantics in common fake-adapter tests.

**Blocked by:** 10, 11

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] Documentation and compiled snippets show only MapRuntime, MapState,
      MapPresentation, and StyleComposition.
- [x] This ticket solely owns `docs/` and every
      `demo-app/common/src/*/kotlin/org/maplibre/compose/docsnippets` package.
- [x] Native tests cover compatible retention, incompatible engine replacement,
      and durable-state replay through the public API.
- [x] Browser tests cover destruction, recreation, replay, and readiness through
      the public API.
- [x] Desktop tests cover presentation-host replacement independently from
      runtime and logical-map lifetime.
- [x] Platform tests that duplicate common behavior without testing an engine
      boundary are deleted.
- [x] Documentation builds and the focused native, browser, and desktop tests
      pass.
- [x] The PR contains a final table classifying every affected platform test as
      retained, rewritten, consolidated, or deleted.

## Test ledger

- Treat every current `liveMapTest` and `jsTest` map/style/source test as an
  explicit keep, rewrite, consolidate, or delete decision in the PR.
- Limit test changes to `androidDeviceTest`, `androidJvmTest`, `iosTest`,
  `jvmTest`, `jsTest`, `liveMapTest`, and `maplibreNativeTest`. Common tests
  remain owned by tickets 02, 06, 07, and 10.
- Consolidate shared lifecycle cases into the common fake-adapter suite; retain
  platform tests only for native identity, GL JS recreation, rendering, input,
  and host integration boundaries.
- Run `mise run build:docs`, `mise run test:android`,
  `mise run test:android:device`, `mise run test:desktop`, `mise run test:ios`,
  and `mise run test:js`.

## Answer

The documentation now describes the runtime, logical map, presentation, and
style-composition ownership model. Generated examples wrap every source and
layer region in `StyleComposition` and pass that value to `MaplibreMap`. The
pages no longer reconstruct the removed `MaplibreMap { ... }` overload or name
the superseded camera and style state APIs.

The browser attribution tests now observe `MapState.style`, the public
`MapStyleState`, instead of the internal compatibility `StyleState`. The native
retention, native replacement, Web recreation, readiness, and desktop host tests
already exercised the required public seams and remain unchanged. The Android
device-test manifest now registers `MapStateRecreationActivity`, which restores
the activity-recreation test after its ownership-API rename.

The inventory found no platform test that duplicated a common lifecycle contract
without also observing a platform boundary. The following table records every
live-map test and every browser map, style, source, rendering, or input test. A
retained row covers every test function in that file.

| Classification | Test                                                        | Platform boundary                                                            |
| -------------- | ----------------------------------------------------------- | ---------------------------------------------------------------------------- |
| Retained       | `BrowserStyleStateTest`: detached map and viewport tests    | Web recreation, replay, and presentation readiness                           |
| Rewritten      | `BrowserStyleStateTest`: four source and style-switch tests | Public Web style state and TileJSON metadata                                 |
| Retained       | `AndroidMapStateRecreationTest`                             | Android activity recreation and saveable camera restoration                  |
| Retained       | `MlnFfiMapCompositionTest`                                  | Native retention, incompatible replacement, replay, and rendering            |
| Retained       | `MlnFfiMapInputTest`                                        | Native Compose pointer and keyboard input                                    |
| Retained       | `MlnFfiStyleSwitchTest`                                     | Native asynchronous style loading and reconciliation                         |
| Retained       | `DesktopPresentationHostLifetimeTest`                       | Desktop host replacement independent from runtime and logical-map lifetime   |
| Retained       | `MlnFfiGestureTokenOrderingTest`                            | Native owner-thread gesture ordering                                         |
| Retained       | `MlnFfiMapIdleTest`                                         | Native frame scheduling                                                      |
| Retained       | `MlnFfiMapPixelTest`                                        | Native render-target pixels                                                  |
| Retained       | `MlnFfiMapRepaintTest`                                      | Native redraw requests after style mutations                                 |
| Retained       | `MlnFfiMapResizeTest`                                       | Native resize and render-session retention                                   |
| Retained       | `MlnFfiProjectionTest`                                      | Native projection and owner-thread snapshots                                 |
| Retained       | `MlnFfiStyleFailureTest`                                    | Native URL-style failure reporting                                           |
| Retained       | `MlnFfiSurfaceLossTest`                                     | Native surface loss, restoration, and cleanup                                |
| Retained       | `MlnFfiTileLodTest`                                         | Native tile-LOD option propagation                                           |
| Retained       | `LayerHandlePropertyTest`                                   | Live-engine imperative layer handles                                         |
| Retained       | `LayerPropertyRoundTripTest`                                | Live-engine style-spec property round trips                                  |
| Retained       | `CameraMoveReportingTest`                                   | Live-engine gesture and programmatic camera events                           |
| Retained       | `MapCameraTransitionTest`                                   | Live-engine camera animation, fit, constraint, and cancellation behavior     |
| Retained       | `MapQueryTest`                                              | Live rendered-feature queries and viewport readiness                         |
| Retained       | `MapVisibleAreaTest`                                        | Live viewport, projection, and visible-region geometry                       |
| Retained       | `SourceChangeReportingTest`                                 | Live source-change callbacks                                                 |
| Retained       | `MapOverlayTest`                                            | Map-overlay composition and placement lifecycle                              |
| Retained       | `MaplibreLogoTest`                                          | Map-overlay logo composition                                                 |
| Retained       | `CustomVectorSourceTest`                                    | Live custom-protocol rendering and cancellation                              |
| Retained       | `FeatureStateTest`                                          | Live feature-state rendering through a source handle                         |
| Retained       | `GeoJsonClusterTest`                                        | Live cluster queries through a source handle                                 |
| Retained       | `ImageSourceDrawTest`                                       | Live image-source pixels and coordinates                                     |
| Retained       | `BaseStyleSourceReadTest`                                   | Live base-style source handles and metadata                                  |
| Retained       | `ExpressionSplitJoinEngineTest`                             | Live expression acceptance by the engines                                    |
| Retained       | `BrowserCompositingTest`                                    | Shared WebGL state, targets, resize, and Skia interop                        |
| Retained       | `BrowserMapLifecycleTest`                                   | GL JS destruction, recreation, camera replay, and surface readiness          |
| Retained       | `BrowserStyleConformanceTest`                               | GL JS base and composed style behavior                                       |
| Retained       | `SameOriginWorkerUrlTest`                                   | Browser worker URL and blob behavior                                         |
| Retained       | `BrowserCameraTransitionLifecycleTest`                      | GL JS transition cancellation and stale-engine rejection                     |
| Retained       | `BrowserMapStateTest`                                       | Browser default runtime, presentation publication, and rival-session refusal |
| Retained       | `BrowserStyleFailureTest`                                   | GL JS style and source failure isolation                                     |
| Retained       | `ScrollNotchesTest`                                         | Browser wheel and trackpad normalization                                     |
| Retained       | `BrowserCustomVectorSourceTest`                             | GL JS protocol URL isolation and failure propagation                         |
| Retained       | `BrowserRasterDemSourceTest`                                | GL JS raster-dem option acceptance                                           |
| Retained       | `BrowserStyleUriTest`                                       | Browser style loading from a URL                                             |
| Retained       | `GlJsImageTest`                                             | GL JS image conversion                                                       |
| Consolidated   | None                                                        | Shared lifecycle behavior remains in common fake-adapter tests               |
| Deleted        | None                                                        | Every inventoried test observes a distinct platform boundary                 |

Validation passed with `mise run check`, `mise run build:docs`,
`mise run test:android`, `mise run test:android:device`,
`mise run test:desktop`, `mise run test:ios`, and
`caffeinate -dimsu mise run test:js`.
