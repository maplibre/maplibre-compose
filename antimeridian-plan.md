# Plan: longitude wrapping / antimeridian contract in the public API

Working notes, 2026-09-06. Origin: maplibre-compose issue #956 discussion
(westnordost + sargunv) after PR #1291 made native viewport queries return
unwrapped ("world-copy-preserving") longitudes, matching web.

## Problem

MapLibre repeats the world horizontally. After #1291, three KDoc sites document
that viewport outputs may exceed ±180° or span more than 360°:

- `Viewport.visibleBoundingBox` (camera/Viewport.kt:26)
- `MapState.positionFromScreenLocation` (map/MapRuntime.kt:932)
- `VisibleRegion` class (util/VisibleRegion.kt:9)

Everything else in the public API is silent, including functions returning the
same data (`MapState.getVisibleBoundingBox`, `MapRuntime.kt:920`), the inverse
projection (`screenLocationFromPosition`), all camera inputs
(`CameraPosition.target`, bounds fitting, `CameraConstraints.boundingBox`),
feature queries, location, sources, offline, and overlays.

spatial-k (`org.maplibre.spatialk:geojson:0.7.0`) performs **no** coordinate
range validation (RFC 7946-correct) but never says so. `BoundingBox`'s KDoc
implies west ≤ east and never reproduces the RFC 7946 §5.2 antimeridian
convention (crossing box has east < west). No wrap/unwrap/split helpers exist.

Two conventions collide in the spatial-k `BoundingBox` type:

1. Continuous/unwrapped (our viewport output): west ≤ east always, values may
   exceed ±180, span may exceed 360°. Not RFC-conformant.
2. RFC 7946: longitudes within ±180, antimeridian crossing as east < west,
   cannot express span > 360°.

## Decisions

- Take **option 1**: introduce a viewport-specific bounds type `VisibleBounds`
  with continuous-longitude semantics (west ≤ east guaranteed, span may exceed
  360°), replacing spatial-k `BoundingBox` as the return type of
  `Viewport.visibleBoundingBox` and `MapState.getVisibleBoundingBox()`. Breaking
  change; no compatibility guarantees to honor.
- Document the input side; no normalization of `CameraPosition.target` (would
  break the world-copy use case).
- spatial-k gets docs clarifications + helpers (`wrapped()`,
  `splitAtAntimeridian()`) — delegated to a separate agent in the spatial-k
  repo.

## Open behavioral questions (probes needed before implementation)

1. `screenLocationFromPosition(Position(lon=190))`: which world copy does it
   project to, native vs JS? (native uses `pixelForLatLng`,
   `MlnFfiMapSession.kt:1520`; JS `map.project`, `GlJsMapSession.kt:957`)
2. Does `CameraConstraints.boundingBox` accept an antimeridian-crossing box
   (east < west or east > 180), on each engine? (internal comment at
   `MlnFfiMapSession.kt:1408` says unbounded ≠ world bounds)
3. Do `queryRenderedFeatures` geometry longitudes differ in wrapping between
   native and JS? (both pass engine output verbatim)

Probe via liveMapTest (native/desktop + JS browser tests per AGENTS.md).

## Probe results (2026-09-06, JVM/native + Chrome/GL JS)

1. `screenLocationFromPosition` DIFFERS: native wraps the input longitude and
   projects onto the world copy nearest the camera (lon -170, 190, 550 all →
   same on-screen x). GL JS projects continuously (lon -170 → offscreen left,
   190 → onscreen, 550 → one world further right). Decision: normalize the input
   to the copy nearest the camera target in the GL JS session so both platforms
   share the native contract; document it once.
2. `CameraConstraints.boundingBox`: both engines accept an antimeridian-
   crossing box in BOTH encodings (east < west and east > 180) and clamp the
   camera into the allowed band; the clamp edge differs (native picked 170, GL
   JS picked 180). Document as supported, clamp detail engine-defined.
3. `queryRenderedFeatures`: both engines return geometry unnormalized —
   antimeridian-crossing features come back split into two polygons, one with
   continuous (177.98..182.02) and one with wrapped (-182.02..-177.98)
   longitudes, and a feature rendered in several world copies may appear
   multiple times. Document: coordinates exactly as the engine produces them,
   may fall outside ±180° in either direction.
4. Camera target input DIFFERS: setCameraPosition(target lon 539.5) reads back
   179.5 on native (engine wraps) but stays 539.5 on GL JS. Left as-is
   (normalizing input could change animation paths); documented on
   `CameraPosition.target`.

## Implementation steps (this repo)

1. Run probes (above); evaluate whether the plan survives the findings.
2. Add `VisibleBounds` type (commonMain, near `Viewport`): southwest/northeast
   Positions or explicit west/south/east/north doubles; KDoc states the
   continuous convention in full (may exceed ±180, may span >360, west ≤ east
   always; contrast with RFC 7946 bbox semantics). Include `wrapped()` and/or
   `toBoundingBox()` conversion applying RFC encoding when span ≤ 360.
3. Change `Viewport.visibleBoundingBox` and `MapState.getVisibleBoundingBox()`
   to return `VisibleBounds`; update platform implementations
   (`MapViewportGeometry.kt`, `GlJsViewport.kt`, `MlnFfiMapSession.kt`).
4. KDoc sweep:
   - `getVisibleRegion`, `viewport`, `MapClickHandler`, `MaplibreMap`
     onClick/onLongClick: state that delivered positions preserve world copies.
   - `screenLocationFromPosition`: state which world copy an out-of-range
     longitude projects to (per probe 1).
   - `CameraPosition.target`: out-of-range longitude selects a world copy;
     camera behaves identically on each.
   - `fitCameraToBounds`/`animateCameraToBounds`: shortest-path across the
     antimeridian (already tested at `MapCameraTransitionTest.kt:62`).
   - `CameraConstraints.boundingBox`: per probe 2.
   - `queryRenderedFeatures`/`querySourceFeatures`: geometry returned exactly as
     the engine produces it, unnormalized (per probe 3).
   - `TileSetOptions.boundingBox`, `PositionQuad`: add missing KDoc.
5. Extend liveMapTest coverage where probes reveal behavior worth pinning.
6. Docs site: "Coordinates and the antimeridian" page (follow docs-writing
   skill); link from camera.mdx sections on bounds fitting and screen
   conversion.
7. Update AGENTS.md if it documents anything this changes (check).

## spatial-k work (delegated agent)

- `Position` KDoc: no range validation, per RFC 7946 §3.1.1; out-of-range values
  accepted and round-trip through serialization unchanged.
- `BoundingBox` KDoc: reproduce RFC 7946 §5.2 antimeridian convention (east <
  west when crossing).
- Add helpers: `Position.wrapped()`, `BoundingBox.wrapped()`,
  `BoundingBox.splitAtAntimeridian(): List<BoundingBox>` handling both the RFC
  encoding (east < west) and continuous encoding (east > 180).

## Status

Done in this worktree:

- `VisibleBounds` type added (`util/VisibleBounds.kt`): southwest/northeast
  Positions, west/south/east/north, longitudeSpan/latitudeSpan, `wrapped()` →
  RFC 7946 `BoundingBox` (east < west when crossing; span ≥ 360 → full world;
  meridian edges handled via separate west/east wrap ranges). Unit tests in
  `VisibleBoundsTest.kt` (commonTest).
- `Viewport.visibleBoundingBox` → `visibleBounds: VisibleBounds`;
  `MapState.getVisibleBoundingBox()` → `getVisibleBounds()`; `MapAdapter`, both
  sessions, snapshotter adapter, test fakes, live tests, docsnippets all
  migrated. No API-dump tooling in this repo.
- GL JS `screenLocationFromPosition` now wraps the input longitude onto the
  world copy nearest the camera (`GlJsMapSession.kt`), matching native.
- KDoc sweep: `CameraPosition.target`, fit/animate bounds (shortest path),
  `CameraConstraints.boundingBox`, `queryRenderedFeatures` ×2,
  `querySourceFeatures`, cluster getters, `FeaturesClickHandler`,
  `MapClickHandler`, `MaplibreMap` onClick/onLongClick, `getVisibleRegion`,
  `viewport`, `Viewport.visibleRegion`, `TileSetOptions`, `PositionQuad`,
  `LocationMeasurement.position`.
- Probes converted into `AntimeridianContractTest.kt` (liveMapTest): nearest-
  copy projection + round trip; divergent camera read-back pinned per flavor;
  constraints accept crossing boxes in both encodings; query geometry split with
  > 180 and <-180 pieces.
- Docs: camera.mdx gained "The repeated world and the antimeridian" plus a
  compiled snippet region `repeated-world` in docsnippets/Camera.kt.
- Deliberately untouched (unverified): OfflinePackDefinition bounds/shape
  antimeridian semantics; overlay `placedAt` inherits
  screenLocationFromPosition's now-documented contract.

Validation: `mise run check` ✔ (after `mise run fix`); JVM contract tests ✔;
full test:desktop / test:js / test:android / build:docs — see final report.
