# Test suite overhaul

This document is the source of truth for how the test suite is layered and how
CI should run it. The layer names below are part of the design. Ticket
checklists may add cases; they must not put a wrapper assertion on a GPU.

## Objective

CI on `main` must fail when a real bug lands, and pass when the tree is sound. A
flake must not be the common outcome. The suite keeps the coverage that would
catch a real bug, and stops paying for coverage that only re-rolls the same live
engine.

The overhaul does not quarantine tests as the default fix, and it does not add
retries inside test bodies. A flake is a test on the wrong seam or a job that
multiplies that test.

## Diagnosis

Evidence from `ci.yml` on `main` and from pull-request runs, 2026-08-10 through
2026-08-31, plus issue #1033.

### Recurring flake signatures

| Signature                                                                                                  | Jobs                    | Root cause                                                                                                                                                  |
| ---------------------------------------------------------------------------------------------------------- | ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MlnFfiMapInputTest` viewport wait / 1m hang / zero-delta fling                                            | desktop, Android device | Recognition and camera effects asserted on a live GPU map. #1187 moved recognition to `MapInputRecognitionTest` on a recording target.                      |
| `MlnFfiSurfaceLossTest.feature_state_accepts_mutations_without_a_surface_and_replays_into_its_replacement` | desktop macOS           | Pixel readback after restore (`No production bridge frame has been presented`). The wrapper contract is stored feature state and a restored render session. |
| Android API 24 `InstallException: Failed to install-write all apks`                                        | `android (24)`          | Emulator session-install. #987 retries once. The same job also ran host tests, so a device flake retried the cheap suite.                                   |
| `LinuxVulkanOpenGlInteropTest` pixel / style timeout                                                       | Linux desktop           | GPU context reuse. #977 and #1008 stopped the main recurrence after 2026-08-21.                                                                             |
| `StyleFailureTest` empty error list                                                                        | iOS                     | Event race. #1028 waits on `onMapFailLoading`. Mostly gone after 2026-08-23.                                                                                |
| Entire `BrowserCompositingTest` suite                                                                      | JS                      | Harness never reserved a presentation after #1172. #1175 fixed it. Regression, not a flake.                                                                 |
| `MapVisibleAreaTest.the_viewport_matches_the_session`                                                      | live-map                | Compose snapshot vs live query. #1177 deleted the case.                                                                                                     |
| Docs Compose resource `status -1`                                                                          | docs                    | Rare local HAR fetch.                                                                                                                                       |
| Windows ARM `EXCEPTION_ACCESS_VIOLATION` in `location-runtime-windows`                                     | desktop win-arm         | Native crash. Rare; retry did not help.                                                                                                                     |

The CI retry workflow only reruns when exactly one primary job failed. During
the map API redesign, most red runs had several failed jobs, so retry did
nothing. When it did rerun the macOS surface-loss case, attempt 2 failed again.

### Why the suite stays unhealthy

1. **Wrong seam.** A wrapper contract (recognition, stored feature state, style
   identity) is asserted by waiting on a GPU frame or a pixel.
2. **Multiplied live tests.** `liveMapTest` (51 methods) runs on 8 jobs.
   `maplibreNativeTest` (145 methods plus inherited live tests) runs on 7. One
   2% flake on five desktop live jobs is a 10% red `main`.
3. **Coupled jobs.** Android host tests ran inside each emulator job, twice per
   push, and a device install flake retried them.
4. **Process-global native state.** One FFI runtime and one cache file per
   process. Desktop logs show `table resources already exists` on almost every
   failure (maplibre-native-ffi#667).
5. **Timing as the assertion.** `pumpUntil`, `waitUntil`, `settle`, and
   wall-clock `waitUntilMap` turn scheduling into pass/fail.

### Inventory (2026-08-31)

| Tier           | Source sets                                               | Methods | Hosts                        |
| -------------- | --------------------------------------------------------- | ------: | ---------------------------- |
| Cheap          | `commonTest`, location fakes, android host                |    ~250 | JVM, no MapLibre runtime     |
| Medium         | android host GMS/HMS mocks, JS URL tests, Skiko contracts |    ~120 | host JVM or headless browser |
| Live           | `liveMapTest`, `maplibreNativeTest`                       |    ~200 | MapLibre + GPU               |
| Compose + live | `androidJvmTest` FFI cases, device, pixel                 |     ~80 | Compose UI + native interop  |

`lib/maplibre-compose-material3` has no tests.

The cheap fakes that new tests should extend:

- `mapRuntimeForTest` / `RecordingStyleBinding` — layer 1
- `RecordingGestureTarget` via `runPlainComposeUiTest` — layer 1
- `FakeMlnFfiMapHost` — layer 2 (`MlnFfiMapSurfaceRecoveryTest` already uses it)
- `BridgeMapFixture` — layer 3, explicit frames, no Compose

## Domain

| Term    | Meaning                                                          |
| ------- | ---------------------------------------------------------------- |
| Layer 0 | Pure functions. No clock, no I/O, no MapLibre.                   |
| Layer 1 | Fake engine. Compose and map types against recording adapters.   |
| Layer 2 | Fake GPU host. FFI host and surface callbacks, no device.        |
| Layer 3 | Headless live engine. Real MapLibre, caller-driven frames.       |
| Layer 4 | Compose plus live engine. Only interop a fake cannot represent.  |
| Layer 5 | Pixel or compositing. Color or framebuffer ownership is the bug. |
| Engine  | MapLibre Native or MapLibre GL JS.                               |
| Wrapper | This repository's types, lifetime, and Compose integration.      |

ADR [0001](../../docs/adr/0001-test-suite-layers.md) records the placement
rules.

## Target CI topology

```text
hygiene
unit / android-host     ← layers 0–1, once
js                      ← layers 0–1 plus JS live (GL JS)
ios                     ← layers 0–3 on one simulator
android-device × API    ← layers 3–4, emulator only
desktop × backend       ← full live on one runner per backend
desktop × arch copy     ← cheap + OS-specific only (later ticket)
docs
```

Android host tests run in their own job. They do not boot an emulator and they
do not share a retry with device install.

A later ticket removes the ARM desktop live copies. Those runners keep
OS-specific tests (`location-runtime-*`, Windows D3D layout, Linux EGL interop
when that is the bug).

## What this wave changes

- Records the layers in the ADR, this spec, `AGENTS.md`, and `CONTRIBUTING.md`.
- Splits the Android CI job so host tests run once, without an emulator, and
  device tests retry on their own.
- Rewrites the macOS surface-loss feature-state case to assert stored feature
  state and a restored render session. `FeatureStateTest` keeps the pixel proof
  that feature state changes the style.

## What this wave does not change

- Desktop live matrix (ticket 05, 09)
- Moving every cheap class out of `maplibreNativeTest` (ticket 04)
- Isolating the shared cache process (ticket 06)
- Expanding `FakeMlnFfiMapHost` to idle, repaint, and composition (ticket 07)
- Replacing JS `waitUntilMap` wall-clock waits (ticket 08)
- Adding `maplibre-compose-material3` tests (ticket 10)

## Validation

- `mise run check`
- `mise run test:android` for the cheap Android suite
- Focused compile of `MlnFfiSurfaceLossTest`
- YAML parse of `.github/workflows/ci.yml`
