# Native FFI 0.202609.0 upgrade

Audit date: 2026-09-05. The previous pin was `0.202608.3`.
[Maven Central metadata](https://repo.maven.apache.org/maven2/org/maplibre/nativeffi/maplibre-native-ffi/maven-metadata.xml)
identifies `0.202609.0` as the latest released Kotlin binding. Its tag points to
`a64390894`, including the JVM logging shutdown fix. GitHub's latest **core**
release is a separate release stream and does not identify the latest Kotlin
artifact.

The
[binding release comparison](https://github.com/maplibre/maplibre-native-ffi/compare/bindings/kotlin/v0.202608.3...bindings/kotlin/v0.202609.0)
contains 24 commits. The embedded Native core advances from `550f64be2` to
`2a8ebc490`: a surface-transform fix, a custom-layer pre-render null check, GLFW
Metal sizing, the Android 13.5.1 release, and the `mbgl` to `mln` directory
rename. There are no new style properties in that core range. The pinned
style-spec parity check still passes with the existing unsupported-property
table.

## Applied changes

- Pin every FFI binding and runtime dependency to `0.202609.0` through the
  version catalog.
- Move feature-state operations to `MapHandle`, following
  [FFI #652](https://github.com/maplibre/maplibre-native-ffi/pull/652). Remove
  the Kotlin state mirror, its lock, and renderer replay flags. The native map
  retains state before the first frame, without a surface, and through renderer
  replacement. Reads now consult native state, including changes made through a
  borrowed native map handle.
- Use `VulkanHandle.ofBits` for images, image views, and Vulkan surfaces. These
  are 64-bit non-dispatchable handles; devices, queues, and OpenGL surfaces
  remain pointers. This is a required binding API migration.
- Use
  [unwrapped unprojection](https://github.com/maplibre/maplibre-native-ffi/pull/683)
  for viewport corners and public screen-to-position conversion. Remove the
  center-based longitude repair, which could not recover viewports spanning more
  than one world. Both visible regions and bounding boxes now preserve world
  copies; snapshot capture uses the same viewport reader.
- Use
  [resource cancellation callbacks](https://github.com/maplibre/maplibre-native-ffi/pull/684)
  to cancel application provider loads, replacing the coroutine that polled
  every 16 ms. Register before starting the load, so cancellation during
  registration starts no work. Packaged-resource blocking reads retain their
  existing lifetime.

Native coordinates returned by `positionFromScreenLocation`, visible regions,
and snapshot viewports can now exceed ±180°. Bounds can span more than 360°.
This intentional behavior change matches the browser and is documented in KDoc.
Applications that need canonical longitudes must wrap them themselves.

## Improvements included by the dependency

- [FFI #686](https://github.com/maplibre/maplibre-native-ffi/pull/686) fixes the
  JVM-exit deadlock in native logging. This addresses Compose
  [#1289](https://github.com/maplibre/maplibre-compose/issues/1289).
- [FFI #685](https://github.com/maplibre/maplibre-native-ffi/pull/685) reduces
  image-upload copies in Kotlin/JVM, Kotlin/Native, and Android. Our existing
  `setStyleImage` path benefits without changing its defensive-copy contract.
- [FFI #654](https://github.com/maplibre/maplibre-native-ffi/pull/654) enables
  Goldfish mitigation on OpenGL render sessions; #676 adds style-reload coverage
  and a GL-validity patch. These benefit Android maps and snapshots. Compose's
  surface replacement handling remains necessary: it addresses a different
  lifecycle boundary.
- [FFI #682](https://github.com/maplibre/maplibre-native-ffi/pull/682) routes
  unconsumed native logs to Android, Apple, and Windows platform loggers.
- [FFI #674](https://github.com/maplibre/maplibre-native-ffi/pull/674) validates
  offline merge inputs read-only before scheduling. Missing or unreadable
  sources fail earlier. Compose already documents the compatible MapLibre
  database, unmodified source, and exclusion of ambient resources; no API
  rewrite is needed.

## Decisions for triage

1. **Source volatility: implemented.**
   [FFI #673](https://github.com/maplibre/maplibre-native-ffi/pull/673) now
   backs the native-only `SourceHandle.isVolatile` extension property. It reads
   and writes native state through the existing handle lifetime guard. It
   changes persistent storage policy for subsequent tile requests without
   clearing already cached tiles. Sources without tile requests retain it as
   metadata. This small live-handle API needs no browser fallback or source
   constructor redesign.
2. **Android ARMv7.** FFI now supports OpenGL and Vulkan on ARMv7, including the
   Android binding. Our Vulkan loader AAR still restricts its native build to
   `arm64-v8a` and `x86_64`. Enabling ARMv7 requires auditing our handle
   conversions, Compose/Skiko and other native dependencies, packaging, and a
   32-bit device test. Recommendation: a dedicated platform-support change.
3. **Superseding a pending style load.** `applyRequestedStyle` deliberately
   waits while `styleLoadPending` is true. Replacing a style whose document
   never finishes loading therefore does not reach native cancellation. The new
   request callback cannot change this. Supporting immediate replacement
   requires a decision about style-event attribution and lifecycle
   serialization; it is separate from cancelling obsolete tile requests after a
   loaded style changes.

musl Linux support is out of scope for this upgrade and its follow-up PRs.

## Follow-up effort

Estimates cover implementation, tests, and review by one engineer. Hardware and
upstream dependency availability can add calendar time.

| Follow-up                      | Engineering effort | Disposition                                                                                                                                                                                                                                       |
| ------------------------------ | ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Native source volatility       | 1–2 hours          | Implemented in this branch.                                                                                                                                                                                                                       |
| Android ARMv7                  | 1–2 days           | Fix pointer versus 64-bit Vulkan handle conversion in our JNI shim, audit transitive native libraries, enable packaging, and validate on a 32-bit device. The existing `VkSurfaceKHR` to `intptr_t` conversion cannot simply be enabled on ARMv7. |
| Supersede a pending style load | 1–2 days           | Design late-event ownership and cancellation, then cover stalled loads, rapid replacement, failures, teardown, and snapshots. The current serialized policy is deliberate.                                                                        |

## Shutdown verification

The logger subprocess probe was run with the published Kotlin binding and Metal
runtime artifacts, swapping only those two jars between `0.202608.3` and
`0.202609.0`. Both versions received a real asynchronous native parser warning,
closed their native map/runtime, and printed the same marker immediately before
requesting JVM exit. Environment: macOS ARM64, Zulu JDK 25.0.4.

| FFI        | Callback  | Result                                                                                |
| ---------- | --------- | ------------------------------------------------------------------------------------- |
| 0.202608.3 | Installed | Reached exit marker, hung beyond the 12-second deadline, then was sampled and killed. |
| 0.202608.3 | Cleared   | Reached exit marker, hung beyond the 12-second deadline, then was sampled and killed. |
| 0.202609.0 | Installed | Reached exit marker and exited with code 0 in 1.040 seconds.                          |
| 0.202609.0 | Cleared   | Reached exit marker and exited with code 0 in 0.879 seconds.                          |

Both old-version samples show the reported failure:

```text
VM_Exit::doit → __cxa_finalize_ranges → mln::Log::~Log
  → mln::ThreadedScheduler::~ThreadedScheduler → thread join

Logger worker: UpcallContext::~UpcallContext → jni_DetachCurrentThread
  → VM_Exit::wait_if_vm_exited
```

The initial stress verification also passed six child JVMs, covering 18 Compose
runtime lifecycles and 36 captures. The permanent `NativeProcessExitTest` keeps
one child, one runtime, and one capture. It observes an asynchronous parser
warning through Compose's own logging bridge, closes the snapshotter and
runtime, and asserts actual process exit within 30 seconds. A hung child is
forcibly reaped. The installed/cleared raw callback matrix remains covered in
native-ffi's `LogProcessExitTest`; Compose does not repeat it.

These shutdown comparisons are local macOS/JDK 25 evidence; they do not
establish a separate Windows/Linux process-exit result.

Raw comparison results and the two old-version stack samples are in
`build/reports/native-shutdown/`.

## Open-issue sweep

All 25 open Compose issues were inspected. No issue was edited or closed.

| Issues                                                            | Disposition after the bump                                                                                                                                                           |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [#1289](https://github.com/maplibre/maplibre-compose/issues/1289) | Fixed upstream and included by this pin. Candidate to close when the bump ships.                                                                                                     |
| [#956](https://github.com/maplibre/maplibre-compose/issues/956)   | Unblocked upstream and implemented here, with antimeridian and 562.5° viewport regressions. Candidate to close when this change ships.                                               |
| [#696](https://github.com/maplibre/maplibre-compose/issues/696)   | Uploads do less copying, but repeated painters still need deduplication in Compose. Keep open.                                                                                       |
| [#478](https://github.com/maplibre/maplibre-compose/issues/478)   | Global state remains blocked by [Native #3302](https://github.com/maplibre/maplibre-native/issues/3302). Feature state moving to the map does not add the `global-state` expression. |
| #1201, #952, #951, #230                                           | Gesture consumption, callbacks, and gesture redesign remain Compose work. The existing gesture design document is unaffected.                                                        |
| #958, #680, #475, #474, #446                                      | Layer-role anchors, source typing, and expression API design need independent decisions. No new upstream capability changes them.                                                    |
| #821, #151                                                        | Text offset scaling and expression-valued variable-anchor offsets are unchanged.                                                                                                     |
| #407                                                              | Overlay synchronization depends on frame and Compose presentation timing. Unwrapped coordinates fix world identity, not frame lag.                                                   |
| #869, #209, #1169                                                 | Browser computed sources, Wasm, and browser support floors are unchanged by the native dependency.                                                                                   |
| #404, #26                                                         | Plugin layers and secondary-platform support still require host/API integration; new ARMv7 binaries do not complete these requests.                                                  |
| #1066, #1033, #567, #85                                           | Windows contributor tools, flaky tests, demo distributions, and versioned docs are unaffected. The shutdown fix does not establish that unrelated gesture timeouts are fixed.        |

The four source TODOs concern Linux heading, the browser Skiko bridge, Firefox
wheel units, and a dedicated em-offset type. None is unblocked by this release.
The Android surface replacement regression comment also remains applicable.

The closed upstream request-correlation issue #681 adds no request identity API
in this release. Cancellation callbacks only apply to requests a provider takes;
they do not correlate provider, URL-transform, and HTTP-header hooks.

## Validation

Initial upgrade validation:

- `mise run style-spec-parity -- --check`: passed.
- `mise run check`: passed.
- `mise run test:desktop`: passed on macOS ARM64/Metal; 712 passed and four
  Linux Vulkan/OpenGL interop tests skipped. The test process exited normally.
- `mise run test:ios`: passed; 575 simulator tests.
- `mise run test:js`: passed; 451 tests in real headless Chrome, including the
  shared repeated-world regression.
- `mise run test:android`: passed; 299 Android host tests. This suite does not
  exercise native rendering.
- `mise run test:android:device`: passed on the Android 16 ARM64 emulator; 627
  map-library tests plus 18 tests in the other modules.

The new coverage checks antimeridian corners, a 562.5° viewport, screen
unprojection of a repeated world, cancellation during callback registration, and
cancellation of a real native tile request after replacing its loaded style.
Existing rendered-pixel tests verify feature-state mutation and reset while no
surface exists, followed by surface restoration.

Linux/Windows Vulkan execution, Android Vulkan, Android x64 Goldfish, physical
Android/iOS devices, and ARMv7 targets were not validated locally. The Vulkan
adapter changes compile in the shared native source set. These are local
results; the pull request reports remote CI separately.

The coverage cleanup passed the five focused desktop tests in
`NativeProcessExitTest` and `MapVisibleAreaTest`. The two rotated/tilted
viewport tests now share one fixture and retain every assertion. The
repeated-world test, native request cancellation, cancellation during
registration, source volatility, and existing feature-state surface-loss
coverage remain distinct regression checks.
