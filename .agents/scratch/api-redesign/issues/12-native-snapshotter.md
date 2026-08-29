# 12: Capture native snapshots with an independent snapshotter

**What to build:** Add a runtime-owned MapSnapshotter that reuses its own native
engine map and evaluates StyleComposition independently from every interactive
map.

**Blocked by:** 06: Reuse StyleComposition across map consumers

**Status:** ready-for-agent

- [ ] MapRuntime creates and tracks MapSnapshotter children.
- [ ] A snapshotter has no UI presentation attachment API.
- [ ] Each capture receives immutable size, camera, density, layout-direction,
      and output options.
- [ ] Repeated captures reuse the snapshotter engine map without retaining the
      prior capture request.
- [ ] A map and snapshotter evaluate the same StyleComposition independently.
- [ ] Concurrent captures execute in a documented serial order.
- [ ] Cancellation, timeout, snapshotter closure, and runtime closure release
      native resources.
- [ ] Android, iOS, and Desktop integration tests render representative composed
      content.
