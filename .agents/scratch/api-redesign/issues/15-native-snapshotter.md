# 15: Capture native snapshots with an independent snapshotter

**What to build:** Add a runtime-owned MapSnapshotter that reuses its own native
engine map and evaluates StyleComposition independently from every interactive
map.

**Blocked by:** 06

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] MapRuntime creates and tracks MapSnapshotter children.
- [x] A snapshotter has no UI presentation attachment API.
- [x] Each capture receives immutable size, camera, density, layout-direction,
      and output options.
- [x] Repeated captures reuse the snapshotter engine map without retaining the
      prior capture request.
- [x] A map and snapshotter evaluate the same StyleComposition independently.
- [x] Captures execute FIFO, start an evaluator with the request density and
      layout direction, and wait for a complete first revision reflecting
      current external state.
- [x] A retained revision may optimize reconciliation but never replaces the
      per-capture evaluation.
- [x] Queued cancellation removes the request; active cancellation or caller
      timeout abandons its result and delays the next capture until terminal
      cleanup.
- [x] Native capture selects an offscreen backend from runtime configuration and
      internal platform support without borrowing a presentation host.
- [x] Snapshotter closure and runtime closure refuse new work, clear queued
      work, and release native resources after active cleanup.
- [x] Android, iOS, and Desktop integration tests render representative composed
      content.

## Test ledger

- Add one common fake-adapter queue suite for FIFO order, style claiming, queued
  and active cancellation, caller timeout, closure, and cleanup failure.
- Add a case that changes external Compose state while the snapshotter is idle
  and proves the next capture evaluates the new state.
- Add one shared native live-map contract for representative composed output;
  keep platform-specific cases only for backend or image differences.
- Run `mise run test:android`, `mise run test:desktop`, and `mise run test:ios`.

## Answer

`MapRuntime` now creates and tracks presentation-free `MapSnapshotter` children.
Each capture evaluates the snapshotter's `StyleComposition` in a new Compose
evaluator with the request density and layout direction, reconciles its first
complete revision, and returns a transparent or white-composited bitmap.
Requests run in FIFO order and use current external state without retaining the
previous capture request.

The native adapter owns a private static map, offscreen render target, and
render session. It selects Android OpenGL, Apple Metal, or Desktop Metal/Vulkan
from runtime support. Compatible captures reuse the private engine. A density
change replaces that fixed-scale engine and replays the base style and newly
evaluated revision inside the same logical snapshotter.

The common fake-adapter suite covers queueing, style ownership, environment,
cancellation, timeout, closure, and cleanup failures. Shared native tests render
representative composed content and exercise consecutive captures at different
densities. Validation passed with `mise run check`, `mise run test:android`,
`mise run test:desktop`, `mise run test:ios`, and the full
`mise run test:android:device` suite.
