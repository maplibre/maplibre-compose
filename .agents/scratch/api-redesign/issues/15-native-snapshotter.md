# 15: Capture native snapshots with an independent snapshotter

**What to build:** Add a runtime-owned MapSnapshotter that reuses its own native
engine map and evaluates StyleComposition independently from every interactive
map.

**Blocked by:** 06

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] MapRuntime creates and tracks MapSnapshotter children.
- [ ] A snapshotter has no UI presentation attachment API.
- [ ] Each capture receives immutable size, camera, density, layout-direction,
      and output options.
- [ ] Repeated captures reuse the snapshotter engine map without retaining the
      prior capture request.
- [ ] A map and snapshotter evaluate the same StyleComposition independently.
- [ ] Captures execute FIFO, start an evaluator with the request density and
      layout direction, and wait for a complete first revision reflecting
      current external state.
- [ ] A retained revision may optimize reconciliation but never replaces the
      per-capture evaluation.
- [ ] Queued cancellation removes the request; active cancellation or caller
      timeout abandons its result and delays the next capture until terminal
      cleanup.
- [ ] Native capture selects an offscreen backend from runtime configuration and
      internal platform support without borrowing a presentation host.
- [ ] Snapshotter closure and runtime closure refuse new work, clear queued
      work, and release native resources after active cleanup.
- [ ] Android, iOS, and Desktop integration tests render representative composed
      content.

## Test ledger

- Add one common fake-adapter queue suite for FIFO order, style claiming, queued
  and active cancellation, caller timeout, closure, and cleanup failure.
- Add a case that changes external Compose state while the snapshotter is idle
  and proves the next capture evaluates the new state.
- Add one shared native live-map contract for representative composed output;
  keep platform-specific cases only for backend or image differences.
- Run `mise run test:android`, `mise run test:desktop`, and `mise run test:ios`.
