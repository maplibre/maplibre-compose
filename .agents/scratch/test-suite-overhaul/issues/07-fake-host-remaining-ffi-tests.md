# 07: Host remaining FFI surface tests on FakeMlnFfiMapHost

**What to build:** Move Compose-plus-FFI cases whose bug is host lifecycle, not
MapLibre rasterization, onto `FakeMlnFfiMapHost`. `MlnFfiMapSurfaceRecoveryTest`
(16 methods) is the pattern.

**Blocked by:** 01

**Type:** task

**Status:** ready-for-agent

Candidates:

- Idle and repaint _counts_ that can be driven by `requestFrame`
  (`MlnFfiMapIdleTest`, `MlnFfiMapRepaintTest`) once the scheduler is injectable
- Composition attach/detach that does not need a presented pixel
  (`MlnFfiMapCompositionTest` paths that only check owner loop and presentation
  identity)
- Android surface replacement host sequencing, if the device case is only
  proving acquire/lose/restore order

Keep a live `BridgeMapFixture` case when the bug is native attach, style
survival, or camera survival (`MlnFfiSurfaceLossTest` first two methods).

## Comments

### 2026-08-31 — maplibreNativeTest audit

Full tables: [maplibre-native-test-audit.md](../maplibre-native-test-audit.md).

`FakeMlnFfiMapHost` already covers `MlnFfiMapSurfaceRecoveryTest` (16 methods).
It cannot absorb native style, camera, tiles, projection, offline, or pixels.

Absorb later, and only after a recording `MlnFfiMapHostSession` exists:

- `MlnFfiMapIdleTest` — wrapper `requestFrame` silence. Keep the camera-rest
  case live; that is mbgl idle.
- `MlnFfiMapRepaintTest` — wrapper `requestFrame` after add/remove source/image.
  Keep a live “a frame was rendered” smoke. No pixels.

Do not move onto FakeHost: `CustomGeometrySourceTest`,
`CustomVectorSourceNativeTest`, `MlnFfiSurfaceLossTest` (native attach / style /
camera / feature-state replay), `MlnFfiMapResizeTest` (`attachCount` /
`retargetCount`), `PlatformMapAccessTest`, or any source/layer round-trip.

## Test ledger

- Each moved case fails if the host skips a required acquire, draw, or close.
- `mise run test:android` can run the fake-host cases.
- Device and desktop keep one live replacement smoke if the fake cannot
  represent EGL/Metal teardown.
