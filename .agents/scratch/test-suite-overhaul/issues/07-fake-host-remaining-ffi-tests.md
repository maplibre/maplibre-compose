# 07: Host remaining FFI surface tests on FakeMlnFfiMapHost

**What to build:** Move Compose-plus-FFI cases whose bug is host lifecycle, not
MapLibre rasterization, onto `FakeMlnFfiMapHost`. `MlnFfiMapSurfaceRecoveryTest`
(16 methods) is the pattern.

**Blocked by:** 01

**Type:** task

**Status:** resolved

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

## Answer

Host retain, replace, and remount now live on `FakeMlnFfiMapHost`. Composition
methods that still call `createNativeMapRuntime` or `currentStyleLayerIds()`
stayed live. Idle and repaint counts stayed live: `MlnFfiStyleBinding.mutateMap`
still needs a live `MapHandle.requestRepaint()`, and mbgl idle is a native bug.

### Landed on FakeHost / recording session

- [x] `MlnFfiMapSurfaceReplacementTest.a_stable_surface_keeps_one_host_across_recompositions`
      — one host, no close, acquire and draw still happen
- [x] `MlnFfiMapSurfaceReplacementTest.replacing_the_composed_surface_closes_the_old_host_and_acquires_the_new_one`
      — old host closes; new host acquires and draws
- [x] `MlnFfiMapSurfaceReplacementTest.a_host_receives_its_surface_after_reusable_content_is_reactivated`
      — FakeHost twin of `AndroidSurfaceReplacementTest` method 2
- [x] `RecordingMlnFfiMapHostSession` plus `RecordingMlnFfiMapRenderer` shared
      with `MlnFfiMapSurfaceRecoveryTest`
- [x] `MlnFfiMapHostSessionRequestTest` (2 methods) — session `requestFrame`
      after available / resize / lose+restore, no native loop

### Stayed live, and why

`MlnFfiMapCompositionTest` (all 16 methods):

- `presentation_options_update_without_replacing_the_native_map` — adapter
  identity on a real `MlnFfiMapSession`; still calls `createNativeMapRuntime`
- `a_map_state_retains_its_native_map_between_presentations` —
  `currentStyleLayerIds()` and camera on a real session
- `an_incompatible_presentation_scale_replaces_the_native_map_and_replays_logical_state`
  — same
- `changing_layout_direction_keeps_the_live_session_and_host` — live
  `MlnFfiMapSession.layoutDirection`
- `one_style_composition_is_evaluated_independently_for_two_maps`,
  `detached_native_map_keeps_its_applied_revision_until_current_state_is_reattached`,
  `a_layer_removed_and_re_added_comes_back` — `currentStyleLayerIds()`
- `a_later_revision_supersedes_reconciliation_failure_before_the_surface_is_revealed`,
  `an_unloaded_style_keeps_the_transparent_load_placeholder` — publish /
  placeholder
- `the_first_camera_position_reaches_the_map` — camera
- `overlay_placed_at_follows_the_camera_target_when_the_map_resizes` — overlay
  `placedAt`
- `changing_geojson_data_recomposes_and_requests_a_frame` — live GeoJSON
- `map_state_renders_a_base_style_and_publishes_one_presentation` — real
  presentation and style load
- `an_empty_style_composes_without_error`,
  `the_offline_demo_layer_composes_without_error`,
  `an_empty_geojson_source_composes_without_error` — `runBridgeMapTest` waits
  for a real `RENDERED` frame

Other live classes this ticket named and left in place:

- `AndroidSurfaceReplacementTest.a_surface_map_without_an_overlay_produces_a_frame_after_replacement`
  — EGL / #1150
- `AndroidSurfaceReplacementTest.a_surface_host_receives_its_surface_after_reusable_content_is_reactivated`
  — device SurfaceView order; FakeHost twin added
- `MlnFfiMapIdleTest` (all 3) — mbgl idle; scheduler is not injectable without a
  live `MapHandle`
- `MlnFfiMapRepaintTest` (all 6) — live `RENDERED` smoke after style mutation
- `MlnFfiSurfaceLossTest` — native attach / style / camera
- Custom geometry/vector, style images, projection, offline, pixel smoke
