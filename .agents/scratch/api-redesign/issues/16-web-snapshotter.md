# 16: Capture Web snapshots with an independent snapshotter

**What to build:** Implement MapSnapshotter on Web with a dedicated non-UI GL JS
map and rendering target. Capture must remain independent from every interactive
MapState.

**Blocked by:** 15

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] Web capture uses a dedicated GL JS map and private rendering target.
- [x] The rendering target is non-visible, owned by the snapshotter engine, and
      removed from the DOM during cleanup.
- [x] The snapshotter never attaches to MaplibreMap.
- [x] Capture does not attach, detach, pause, or retarget an interactive map.
- [x] Immutable request values control each output.
- [x] StyleComposition evaluation remains independent from interactive maps.
- [x] FIFO execution, desired-style claiming, cancellation, timeout, and closure
      follow the common snapshotter contract.
- [x] Cancellation and closure release the GL JS map, DOM target, and rendering
      resources.
- [x] Browser tests verify representative composed output and interactive-map
      independence.

## Test ledger

- Reuse the common queue contract from ticket 15 rather than copying it into
  browser tests.
- Add focused browser cases for representative pixels, private DOM ownership,
  cleanup, and independence from an attached interactive map.
- Run `mise run test:js` under `caffeinate -dimsu` on macOS.

## Answer

The Web adapter owns a non-interactive MapLibre GL JS map in a private hidden
DOM target. It keeps that engine across compatible captures, applies every
request's logical extent, density, camera, and transparency, and copies the
physical drawing buffer into the returned bitmap. Closing the snapshotter
removes both the map and its target.

Active Web cancellation removes the private engine because GL JS cannot cancel
every in-flight style and render operation independently. The common adapter
contract now reports whether cancellation retained or released the engine. A
release invalidates the old style binding and returns the snapshotter style to
pending before queued work recreates the engine. Native cancellation reports
that its engine was retained.

Browser tests cover composed pixels, private target ownership and cleanup,
successive request extents, camera, density, and transparency, engine recreation
after cancellation, and independence from an attached interactive map.
Validation passed with `mise run check`, `caffeinate -dimsu mise run test:js`,
`mise run test:android`, `mise run test:desktop`, and `mise run test:ios`.
