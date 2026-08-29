# 16: Capture Web snapshots with an independent snapshotter

**What to build:** Implement MapSnapshotter on Web with a dedicated non-UI GL JS
map and rendering target. Capture must remain independent from every interactive
MapState.

**Blocked by:** 15

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Web capture uses a dedicated GL JS map and private rendering target.
- [ ] The rendering target is non-visible, owned by the snapshotter engine, and
      removed from the DOM during cleanup.
- [ ] The snapshotter never attaches to MaplibreMap.
- [ ] Capture does not attach, detach, pause, or retarget an interactive map.
- [ ] Immutable request values control each output.
- [ ] StyleComposition evaluation remains independent from interactive maps.
- [ ] FIFO execution, desired-style claiming, cancellation, timeout, and closure
      follow the common snapshotter contract.
- [ ] Cancellation and closure release the GL JS map, DOM target, and rendering
      resources.
- [ ] Browser tests verify representative composed output and interactive-map
      independence.

## Test ledger

- Reuse the common queue contract from ticket 15 rather than copying it into
  browser tests.
- Add focused browser cases for representative pixels, private DOM ownership,
  cleanup, and independence from an attached interactive map.
- Run `mise run test:js` under `caffeinate -dimsu` on macOS.
