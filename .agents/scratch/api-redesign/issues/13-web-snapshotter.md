# 13: Capture Web snapshots with an independent snapshotter

**What to build:** Implement MapSnapshotter on Web with a dedicated non-UI GL JS
map and rendering target. Capture must remain independent from every interactive
MapState.

**Blocked by:** 12: Capture native snapshots with an independent snapshotter

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] Web capture uses a dedicated GL JS map and private rendering target.
- [ ] The snapshotter never attaches to MaplibreMap.
- [ ] Capture does not attach, detach, pause, or retarget an interactive map.
- [ ] Immutable request values control each output.
- [ ] StyleComposition evaluation remains independent from interactive maps.
- [ ] Repeated and serialized captures follow the common snapshotter contract.
- [ ] Cancellation and closure release the GL JS map and rendering resources.
- [ ] Browser tests verify representative composed output and interactive-map
      independence.
