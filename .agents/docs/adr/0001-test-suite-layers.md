# 0001: Test suite layers

## Status

Accepted.

## Context

CI on `main` fails often enough that pull-request results are ignored. The
failures cluster in a few signatures, not in a broad scatter of assertions:

- Compose UI tests that create a live GPU map and wait for a viewport or frame
- Pixel readback after a surface is replaced
- Android API 24 emulator session-install
- Shared offline-cache database warnings in every desktop log

The suite already has the right source-set names (`commonTest`, `liveMapTest`,
`maplibreNativeTest`) and the right cheap fakes (`RecordingStyleBinding`,
`mapRuntimeForTest`, `FakeMlnFfiMapHost`, `runPlainComposeUiTest`). New tests
keep landing in the live GPU sets anyway, then run on every platform that
inherits those sets. A recognition test on a live map, or a feature-state test
that asserts a pixel after surface restore, turns a wrapper contract into a GPU
timing test.

About 650 test methods exist. Roughly 250 are cheap and deterministic. The rest
share a live MapLibre runtime and a GPU, and CI multiplies them:

- `commonTest` runs on 10 jobs
- `liveMapTest` runs on 8 jobs
- `maplibreNativeTest` runs on 7 jobs

The cheap copies are fast and fine. The live copies are independent dice rolls
of the same flake. Retrying a whole job after one flake re-runs everything and
still fails when two jobs flake together.

## Decision

Every test belongs to one layer. A test sits at the cheapest layer that can
catch the bug it exists to catch. A higher layer does not repeat a lower layer's
assertion.

| Layer                      | What it is allowed to touch                                                      | Typical host                                      |
| -------------------------- | -------------------------------------------------------------------------------- | ------------------------------------------------- |
| 0 Pure                     | Functions, JSON, math                                                            | `commonTest`                                      |
| 1 Fake engine              | `MapState`, style composition, gesture recognition, with recording adapters      | `commonTest`, `runPlainComposeUiTest`             |
| 2 Fake GPU host            | FFI surface and host callbacks, no device                                        | `FakeMlnFfiMapHost`                               |
| 3 Headless live engine     | Real MapLibre, explicit frames, no Compose                                       | `BridgeMapFixture`, `MapFixture`                  |
| 4 Compose plus live engine | Interop that a fake cannot represent                                             | `runFfiComposeUiTest`, one device or desktop host |
| 5 Pixel or compositing     | One representative renderer, only when color or framebuffer ownership is the bug | One JS job or one native backend                  |

Placement rules:

- Gesture recognition uses a recording `GestureTarget`. Native camera effects of
  `moveBy` / `scaleBy` live in a headless camera test, not in the recognition
  suite.
- Feature state, source attach, and style identity assert handles, JSON, or
  callbacks. A pixel is allowed only in a dedicated layer-5 case.
- Surface loss asserts attach count, style identity, camera, and stored feature
  state. It does not sample the replacement framebuffer.
- `liveMapTest` exists to compare Native and GL JS on the same contract. It is
  not the default place for a new wrapper test.
- A process-global resource (native runtime singleton, shared cache file)
  belongs in a serial suite or a separate Gradle process.

CI topology follows the layers:

- Cheap layers run once per job type that can host them, and fail on their own.
  Android host tests do not share a job with the emulator.
- Live layers run on one job per engine or backend that can disagree, not on
  every OS/arch that packages that backend.
- A retry retries one failed live job. It does not re-run cheap tests.

## Consequences

New tests go in layer 0–2 unless the author can name a bug that only a live
engine or a pixel would catch.

Existing live tests move down when their assertion is already expressible on a
fake. Coverage is relocated, not deleted.

Desktop CI keeps the full live suite on every architecture that packages a
backend. ARM runners have already caught real bugs that x64 missed. Do not drop
an arch copy to buy a greener `main`.

The contributor docs and `AGENTS.md` state the same table so agents stop adding
live-map cases for recognition, JSON, and lifecycle.

Live GPU tests stay on every architecture that can disagree. Reliability is not
a smaller matrix. A live wait fails as one `AssertionError` with presentation,
style load state, attach count, and layer ids, or it passes. A hang watchdog
that kills a process is isolated to that one method. `resetForTest()` runs after
the composition has disposed the map. `ci-retry` still reruns only one failed
primary job, and only for infra.
