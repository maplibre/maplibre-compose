# Desktop MapLibre Native FFI rewrite

## Status

This document is the implementation plan for replacing the desktop JNI
integration with the Kotlin Multiplatform bindings published by
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi).

Merge this plan before implementation begins. The rewrite then lands end to end
on a dedicated follow-up branch. Intermediate commits may introduce the new
implementation in layers, but the completed implementation has one desktop path
and no runtime fallback to the legacy JNI code.

The scope is everything MapLibre Native FFI can provide. Desktop behavior that
the FFI does not back — device orientation, for instance — is out of scope even
where a desktop actual exists as a stub.

## Decisions

- Desktop runs on Java 25.
- Compose Desktop consumes the JVM variant of `maplibre-native-ffi`; it does not
  consume the Kotlin/Native Linux targets.
- The first complete backend matrix is:

  | Host    | MapLibre render backend | Compose consumer backend |
  | ------- | ----------------------- | ------------------------ |
  | Linux   | Vulkan                  | OpenGL                   |
  | Windows | Vulkan                  | Direct3D 12              |
  | macOS   | Metal                   | Metal                    |

- Each application packages exactly one MapLibre Native FFI runtime for its
  operating system and architecture.
- Backend choice and host graphics integration remain abstractions. The map,
  camera, style, event, and resource implementations do not depend on the
  initial backend matrix.
- The default Compose Desktop host uses the existing reflection-based Skiko
  bridge. Applications can replace it with a host integration that provides
  supported GPU context and render-target access. `compose-glfw` is an initial
  alternative integration to validate.
- The old bindings modules, C++ JNI code, vendored submodules, build machinery,
  runtime capabilities, and documentation are deleted in the first
  implementation commit. Git history remains the reference for the old
  implementation.
- The Nix development shell is deleted rather than trimmed. It existed only to
  provide a C++ toolchain for building MapLibre Native on NixOS, which the FFI
  removes the need for; the graphics libraries the runtime loads come from the
  host system.
- Missing FFI capabilities receive a precise `TODO(maplibre-native-ffi)` comment
  at the fallback or unsupported boundary. The finished rewrite contains no
  unexplained no-ops and no executable `TODO()` stubs.

## Outcomes

The completed implementation provides:

- working interactive desktop maps on Linux, Windows, and macOS;
- one Kotlin implementation of MapLibre map behavior above the FFI binding;
- a replaceable Compose host/GPU integration boundary;
- camera, events, gestures, styles, sources, layers, images, feature queries,
  resources, and offline behavior backed by the FFI;
- deterministic ownership and teardown of runtime, map, render-session, and GPU
  resources;
- packaged demo applications with the correct native runtime and Java 25
  runtime;
- automated tests for host-independent behavior and a documented manual machine
  matrix for graphics integration;
- no dependency on SimpleJNI, custom JNI, a vendored MapLibre Native checkout,
  or a local CMake build.

## Architecture

```text
MaplibreMap composable
  |
  +-- DesktopMapSession ------------------------------------------+
  |     MapAdapter                                                |
  |     RuntimeHandle -> MapHandle -> RenderSessionHandle         |
  |     event pump, style state, queries, resource loading        |
  |                                                               |
  +-- DesktopMapHostFactory                                       |
        |                                                         |
        +-- default Skiko host                                    |
        |     Linux:  Vulkan image -> OpenGL/Skia                 |
        |     Windows: Vulkan image -> D3D12/Skia                 |
        |     macOS:   Metal texture -> Metal/Skia                |
        |                                                         |
        +-- application host                                      |
              compose-glfw or another Compose host                |
              supplies context, render target, synchronization,   |
              presentation, and invalidation                      |
                                                                 |
maplibre-native-ffi JVM binding <--------------------------------+
  |
MapLibre Native C ABI
```

The FFI owns MapLibre concepts and validates native lifetimes and thread
affinity. MapLibre Compose owns Compose state, input semantics, frame
scheduling, graphics-host integration, and conversion to its public types.

## Dependency and publication changes

### Java

- Set the desktop JVM compilation and published desktop variant to Java 25.
- Keep Android bytecode at its existing target. Replace the shared `jvmTarget`
  property with target-specific Android and desktop settings.
- Use a Java 25 toolchain for desktop compilation, tests, demo execution, and
  desktop packaging.
- Package a Java 25 runtime with native desktop distributions.
- Add `--enable-native-access=ALL-UNNAMED` to demo execution, tests that reach
  the FFI, and packaged application JVM arguments.
- Document the native-access argument for consumers that run an unpackaged JVM
  application.

### Maven

Add the Central Portal snapshot repository while snapshots are in use:

```kotlin
maven {
  url = uri("https://central.sonatype.com/repository/maven-snapshots/")
  content { includeGroup("org.maplibre.nativeffi") }
}
```

`desktopMain` depends on the backend-independent KMP binding:

```kotlin
implementation(
  "org.maplibre.nativeffi:maplibre-native-ffi:$maplibreNativeFfiVersion"
)
```

The application, rather than the MapLibre Compose library, selects the native
runtime. The initial demo dependencies are equivalent to:

```kotlin
runtimeOnly(
  "org.maplibre.nativeffi:maplibre-native-ffi-runtime-vulkan-jvm:" +
    "$maplibreNativeFfiVersion:natives-linux-x64"
)
```

Use Vulkan runtime classifiers for Linux and Windows and the Metal runtime
classifier for macOS. Host and architecture detection lives in reusable Gradle
build logic so the demo, tests, documentation, and downstream examples do not
copy different detectors.

The default host bridge also depends on the smallest useful set of LWJGL
modules. Platform native classifiers remain application runtime dependencies
unless they can be represented correctly as Gradle variants in a dedicated
runtime artifact.

### Backend model

The implementation discovers the backend compiled into the loaded FFI runtime
with `Maplibre.supportedRenderBackends()`. It intersects that set with the
backends supported by the selected desktop host factory.

The first rewrite expects one result and reports a diagnostic containing:

- the loaded FFI backends;
- the host factory's supported producer and consumer backends;
- the operating system and architecture;
- the missing runtime or bridge dependency when it is known.

Backend-specific code is registered by
`(operating system, producer backend,
consumer backend)`. Map state does not
contain `when (operatingSystem)` or backend-specific native handles. Adding
OpenGL, another Vulkan presentation path, or a different Compose consumer
therefore adds a host bridge registration and runtime dependency, not a second
map implementation.

## Desktop host integration SPI

Add a desktop-only host SPI in `org.maplibre.compose.desktop`. This is a
long-lived product extension point, not migration scaffolding: it keeps MapLibre
map behavior independent of the Compose host and allows applications to supply
supported GPU context and render-target integration.

The SPI is desktop-only because GPU context discovery, native render targets,
and presentation are desktop host concerns; the common `MaplibreMap` API remains
host-independent. Publish it as ordinary public desktop API under the same v0.x
compatibility expectations as the rest of MapLibre Compose.

The API needs these concepts; exact names can be refined before the first public
snapshot:

- `DesktopMapHostFactory`: reports supported backend combinations and creates a
  host surface for one map.
- `DesktopMapHost`: owns host graphics objects and provides a composable drawing
  surface.
- `DesktopMapFrame`: describes one renderable target, logical and physical
  extent, scale factor, generation, and presentation timestamp.
- `DesktopRenderTarget`: carries backend-neutral typed wrappers around the
  native handles required to construct FFI descriptors.
- `DesktopMapRenderer`: receives surface availability, extent changes, frames,
  surface loss, and close.
- `LocalDesktopMapHostFactory`: a composition local whose default is the Skiko
  implementation.

The host boundary owns:

- discovering or creating the host GPU context;
- allocating, importing, exporting, and releasing render targets;
- synchronization between MapLibre's producer backend and Compose's consumer
  backend;
- making the correct context current around renderer access;
- drawing or presenting the last completed target;
- scheduling a Compose invalidation on frame request;
- handling surface loss, resize, and application shutdown.

The map session owns:

- converting a host target to an FFI render-target descriptor;
- attaching and closing the FFI render session;
- all `RuntimeHandle`, `MapHandle`, and `RenderSessionHandle` calls;
- deciding whether a MapLibre render update is pending.

The public host SPI uses MapLibre Compose handle and descriptor types rather
than exposing generated FFM classes. Conversion to
`org.maplibre.nativeffi.render` types stays internal. Validate the boundary
against at least two structurally different host implementations.

### Default Skiko host

Adapt the working host code from the `maplibre-native-ffi` Compose example:

- `ComposeNativeSurface`;
- common surface lifecycle and frame types;
- `SkikoHost`;
- Linux Vulkan-to-OpenGL;
- Windows Vulkan-to-D3D12;
- macOS Metal-to-Metal.

Keep reflection isolated in the default host package. No map, style, input, or
resource class reflects into Skiko.

Reflection failures become a structured unsupported-host result that explains
the expected and observed Skiko classes. Pin the supported Compose/Skiko version
and add a test that checks the reflected member contract without starting
MapLibre.

The reflection bridge is replaceable implementation detail. When Compose/Skiko
provides a supported graphics-context hook, replace this bridge behind the same
host SPI rather than changing the map implementation or requiring applications
to adopt a new integration model.

### Alternative hosts

Build a small integration fixture for
[`compose-glfw`](https://github.com/sargunv/compose-glfw). It supplies the
factory through `LocalDesktopMapHostFactory` and uses its GPU context hook
instead of Skiko reflection.

The fixture proves that:

- the default host is optional;
- a host can provide context access without relying on AWT or Skiko internals;
- the same `DesktopMapSession` and public `MaplibreMap` composable work;
- backend selection and render-target attachment do not depend on
  `ComposeWindow`.

The fixture may remain a sample or move to the compose-glfw repository after the
SPI stabilizes.

## Runtime, threading, and lifecycle

Start with one runtime per map, on one dedicated owner thread per map. This
matches the working FFI Compose example and isolates maps and graphics contexts.
Multiple simultaneous maps therefore use separate runtime owner threads.

Before offline support is finalized, test whether multiple runtimes can safely
use the same persistent cache database. If shared database access is not
supported, introduce a process-level runtime service and serialize its maps on
one owner thread. Record the result next to the runtime factory; do not hide the
choice inside the offline implementation.

All calls touching runtime, map, projection, or render-session handles execute
synchronously on the owner thread. Immutable copied results cross back to the
Compose thread. Callbacks enter Compose state through the UI dispatcher.

Creation order:

1. Select the desktop host and backend.
2. Create the host surface and establish its renderer-access dispatcher.
3. Create `RuntimeHandle` on that dispatcher.
4. Install resource provider and transform callbacks.
5. Create `MapHandle`.
6. Attach a render session when the first non-empty host target is available.
7. Load the initial style and apply the requested camera/options.

Close order:

1. Stop accepting input and frame requests.
2. Detach and close `RenderSessionHandle`.
3. Close projections and outstanding map-owned operations.
4. Close `MapHandle`.
5. Drain or cancel runtime-owned operations and callbacks.
6. Close `RuntimeHandle`.
7. Release producer render targets and contexts.
8. Release consumer imports and host objects.

Every close operation is idempotent at the Compose layer. Disposal, window
close, recomposition with a new map key, and surface loss have tests. Cleaner
behavior in the binding remains a safety net rather than the normal lifecycle.

## Frame and event loop

Use MapLibre's event queue as the source of map lifecycle and render
invalidation. A frame tick:

1. runs `RuntimeHandle.runOnce()`;
2. drains all available runtime events;
3. updates Compose callbacks and the render-pending bit;
4. attaches or resizes the render session if the target generation changed;
5. calls `renderUpdate()` only when an update is pending;
6. completes producer synchronization;
7. asks the host to draw or present the most recently completed target.

The initial implementation may request a Compose frame continuously while a map
is active, matching the FFI example. Before completion, change this to an
event-driven idle loop with frame-clock ticking only while:

- MapLibre reports an update or `needsRepaint`;
- a camera transition is active;
- a resource or runtime task needs pumping;
- the host surface has requested recovery or presentation.

Measure idle CPU use and animated frame pacing. `RenderOptions.maximumFps`
limits scheduling rather than sleeping on the native owner thread.

Translate runtime events as follows:

| FFI event                     | MapLibre Compose behavior                                  |
| ----------------------------- | ---------------------------------------------------------- |
| `MAP_STYLE_LOADED`            | publish a new `SafeStyle`, then compose user style content |
| `MAP_LOADING_FINISHED`        | `onMapFinishedLoading`                                     |
| `MAP_LOADING_FAILED`          | `onMapFailLoading` with code and message                   |
| `MAP_CAMERA_WILL_CHANGE`      | `onCameraMoveStarted`                                      |
| `MAP_CAMERA_IS_CHANGING`      | `onCameraMoved`                                            |
| `MAP_CAMERA_DID_CHANGE`       | final move and `onCameraMoveEnded`                         |
| `MAP_RENDER_UPDATE_AVAILABLE` | mark render pending and request a frame                    |
| `MAP_RENDER_FRAME_FINISHED`   | update FPS and honor `needsRepaint`                        |
| `MAP_RENDER_ERROR`            | log and expose the failure through the map error path      |
| `MAP_STYLE_IMAGE_MISSING`     | log initially; reserve a style-image callback hook         |
| offline events                | update desktop offline state and complete operations       |

Gesture origin is tracked by Compose input state because the FFI deliberately
does not own platform gestures.

## Map adapter and input

Replace `DesktopMapAdapter` with the FFI-backed `DesktopMapSession`. Preserve
the existing common `MapAdapter` contract and implement:

- camera snapshots, jumps, easing, flying, cancellation, bounds, and fit;
- visible bounding box and visible region;
- coordinate projection in both directions;
- debug options and maximum FPS;
- rendered feature queries for points and boxes;
- meters per dp with correct logical/physical density handling;
- click and long-click callbacks;
- callback and option updates across recomposition.

Implement gestures with Compose pointer and key APIs:

- primary drag pan;
- secondary or control-primary drag bearing and pitch;
- scroll zoom anchored at the pointer;
- double-click zoom and shift-double-click zoom;
- keyboard pan and zoom;
- primary click and secondary/long click;
- cancellation when focus, pointer capture, or surface is lost.

Input coordinates enter as physical Compose pixels, convert once to logical map
coordinates using the current scale factor, and return public `DpOffset` values
without a second density conversion. Add tests at fractional and integer scale
factors.

Keep the gesture implementation independent of the host factory so AWT, Skiko,
compose-glfw, and future hosts have identical behavior.

## Styles, sources, layers, and expressions

The FFI generic JSON style API is the primary desktop implementation surface.

### JSON conversion

Add tested conversions for:

- `CompiledExpression` to FFI `JsonValue`;
- Kotlin serialization `JsonElement` to and from FFI `JsonValue`;
- SpatialK GeoJSON to and from FFI geometry, feature, and identifier types;
- colors, formatted values, padding, offsets, enum strings, and literal
  expression rules;
- FFI query results to SpatialK features.

Keep these conversions centralized. Layer setters and query code do not build
independent JSON representations.

### Source objects

Desktop source actuals are live descriptors:

- before attachment they store source ID, type, data, and options;
- `DesktopStyle.addSource` creates the source through the matching FFI call or
  generic source JSON;
- after attachment, mutable operations call the FFI immediately on the owner
  thread;
- removal unbinds the descriptor and rejects later mutation with a clear
  unloaded-style diagnostic;
- base-style sources are reconstructed from `styleSourceIds`, `styleSourceType`,
  and `styleSourceInfo`.

Implement GeoJSON, vector, raster, raster DEM, image, and custom/computed source
behavior. Cluster children, leaves, and expansion zoom use the `supercluster`
feature-extension query.

### Layer objects

Desktop layer actuals store ID, type, source ID, source layer, zoom range,
visibility, filter, layout properties, and paint properties.

Before attachment, setters update the descriptor. Adding a layer emits one
complete layer JSON object at the requested anchor. After attachment, setters
use `setLayerProperty`, `setLayerFilter`, and layer move/remove APIs.

Base-style layers are reconstructed from `styleLayerIds`, `styleLayerType`, and
`styleLayerJson`. Layer ordering and all `Anchor` modes retain their common
`LayerManager` behavior.

Every currently declared desktop layer setter is implemented. A backend or FFI
limitation has a `TODO(maplibre-native-ffi)` comment and a tested diagnostic or
documented fallback.

### Images

Convert Compose `ImageBitmap` pixels to tightly packed premultiplied RGBA8 and
use `setStyleImage`. Preserve pixel ratio and SDF behavior.

The current FFI image options do not carry MapLibre Compose
`ImageResizeOptions`. Initially upload the image without stretch metadata and
place this comment at that exact boundary:

```kotlin
// TODO(maplibre-native-ffi): Preserve stretchable image content insets once
// the C API and Kotlin StyleImageOptions expose them.
```

Do not silently drop any other image option.

## Rendered queries

Queries execute on the current `RenderSessionHandle`, since rendered feature
state belongs to the session.

- Convert point and rectangle queries to `RenderedQueryGeometry`.
- Convert layer IDs and compiled predicates to `RenderedFeatureQueryOptions`.
- Preserve feature geometry, properties, identifier, source ID, source layer ID,
  and state during conversion.
- Define behavior before the first completed frame and during surface loss.
  Prefer an empty result with a debug log for a temporarily unattached session;
  reserve exceptions for closed maps and invalid caller input.
- Use `queryFeatureExtension` for cluster APIs.

## Resources and cache

Create a desktop resource adapter before the map is constructed.

- Load HTTP and HTTPS through the FFI's default loader.
- Resolve Compose resource and classpath/JAR URIs through a custom resource
  provider or resource transform.
- Copy response bytes before returning across callback boundaries.
- Propagate cancellation, cache metadata, modified/expiry times, and errors.
- Use an operating-system-appropriate persistent cache path by default.
- Make cache path and maximum ambient cache size configurable through a desktop
  runtime options API.
- Test spaces, Unicode paths, packaged JAR resources, missing resources,
  cancellation, and application shutdown with requests in flight.

Remove the current special case that reads only `jar:file:` style documents in
the map adapter. Resource behavior belongs to the runtime and applies equally to
styles, sprites, glyphs, tiles, and images.

## Offline support

Make `desktopMain` depend on `maplibreNativeMain` and add desktop actuals for:

- `rememberOfflineManager`;
- `OfflinePack`;
- pack creation, listing, metadata, status, resume, pause, invalidate, and
  delete;
- ambient cache clear, invalidate, and size operations;
- tile count limit and error events.

Build suspending operations on `OfflineOperationHandle` plus runtime events.
Cancellation closes or cancels the operation and never leaves a continuation
registered after runtime disposal.

Use immutable copied status values to update Compose state. Test restart
persistence against a temporary database and verify that map and offline
operations can share the configured cache strategy.

## Diagnostics and FFI gap policy

Introduce one internal exception/diagnostic translation layer. Messages include
the failing operation and preserve the FFI status, native code, and message.
Expected temporary render states such as "no update available" do not become
application errors.

When MapLibre Compose needs a missing FFI operation:

1. implement a correct local fallback when one exists;
2. add
   `TODO(maplibre-native-ffi): <specific missing API and required
   semantics>`
   at the boundary;
3. add a focused test for the fallback or diagnostic;
4. list the gap in the pull request's test notes;
5. remove the fallback and TODO after the FFI snapshot exposes the operation.

The final tree must not contain desktop `TODO()` calls, placeholder `Nothing`
implementations, empty feature results standing in for supported queries, or
unexplained no-op setters.

## Legacy removal

Perform legacy removal in the first commit on the implementation branch, before
adding Java 25, FFI dependencies, host abstractions, or new rendering code. The
purpose is to make every implementation decision against the new architecture
instead of preserving assumptions from the JNI path.

Delete:

- `lib/maplibre-native-bindings`;
- `lib/maplibre-native-bindings-jni`;
- the old desktop map, style, source, layer, rendering, and input
  implementations that depend on those modules;
- both git submodule entries and `.gitmodules` if it becomes empty;
- SimpleJNI and its KSP configuration;
- desktop native variants, capabilities, and `desktopRenderer` build logic;
- CMake presets and native copy/validation tasks owned by those modules;
- CI jobs that compile or upload the JNI libraries;
- daily and release workflow steps that assemble JNI native artifacts;
- old CMake development dependencies that are no longer needed.

Update:

- `settings.gradle.kts`, root Dokka configuration, version catalog, and build
  conventions;
- demo runtime selection and packaging;
- formatting excludes;
- `mise` tasks and system dependencies;
- `AGENTS.md`, module docs, getting started, roadmap, and contribution docs;
- release notes for removed artifacts and the Java 25 minimum.

No compatibility modules, capability aliases, JNI fallback selector, or
deprecated wrapper artifacts are introduced.

Desktop compilation may be broken between this deletion and the first FFI-backed
implementation commit. Keep Android, iOS, and Web checks green and run focused
tests for each new desktop component as it lands. Do not introduce placeholder
actuals, a legacy build flag, or a temporary compatibility layer solely to keep
intermediate desktop revisions green.

## Commit sequence

This document is merged separately before the sequence starts. On the
implementation branch, keep commits cohesive and buildable where practical. The
desktop target is allowed to be red during the explicit clean-slate interval
after legacy deletion; no commit introduces a runtime dual-path or fallback.

1. **Delete the legacy desktop implementation**
   - Delete both bindings modules, vendored submodules, old desktop actuals,
     SimpleJNI, native build logic, runtime capabilities, and JNI CI/release
     jobs.
   - Remove obsolete project, documentation, task, and dependency references.
   - Verify unaffected Android, iOS, and Web tasks.

2. **Prepare Java 25 and FFI dependency resolution**
   - Split Android and desktop JVM targets.
   - Add snapshot repository, version catalog entries, native-access arguments,
     and runtime classifier build logic.
   - Add dependency-resolution tests or inspection tasks.

3. **Define the host SPI and fake host**
   - Add public desktop host interfaces, target descriptors, capability
     negotiation, lifecycle state machine, and a fake in-memory test host.
   - Test backend intersection, frame invalidation, resize, loss, and close.

4. **Port initial native host bridges**
   - Port common surface code and the Linux Vulkan, Windows Vulkan, and macOS
     Metal paths from the FFI Compose example.
   - Isolate and test Skiko reflection.
   - Add the compose-glfw fixture.

5. **Bring up FFI map rendering**
   - Add `DesktopMapSession`, runtime pumping, event translation, camera
     operations, frame scheduling, input, density handling, and teardown.
   - The demo loads, renders, resizes, accepts input, and closes on all three
     operating systems at this point.

6. **Complete styles and queries**
   - Add JSON/GeoJSON conversions, source and layer implementations, images,
     rendered queries, cluster extensions, and base-style reconstruction.
   - Remove all desktop placeholder implementations.

7. **Complete resources and offline**
   - Add Compose resource loading, persistent cache configuration, offline
     manager actuals, cancellation, and persistence tests.

8. **Finish packaging, CI, and documentation**
   - Package one runtime per OS/architecture.
   - Replace native-build workflows with consumer tests.
   - Update public docs, development tasks, roadmap, and release notes.

9. **Stabilize on the machine matrix**
   - Incorporate fixes from real GPU, display server, DPI, lifecycle, and soak
     testing as focused commits.
   - Re-run the full project build and package installation tests.

Commits may be split further by cohesive concern. Once the legacy deletion
commit lands, it is never partially reverted to ease implementation.

## Automated test plan

### Host-independent JVM tests

Use the fake desktop host and fake session/controller boundaries to cover:

- backend negotiation and diagnostics;
- owner-thread dispatch and wrong-thread prevention;
- creation and close ordering;
- event-to-callback translation;
- render-pending and frame-throttling state;
- surface resize, generation changes, loss, and recovery;
- recomposition and option updates;
- gesture state machines and coordinate density;
- expression, JSON, geometry, image, and query conversions;
- source and layer descriptor behavior before and after attachment;
- FFI gap fallbacks and TODO boundaries;
- resource provider success, failure, cancellation, and shutdown;
- offline operation completion and cancellation state.

### FFI integration tests

Run against the packaged snapshot runtime:

- load and verify the C ABI version;
- assert exactly the expected backend for each job;
- create runtime and map, load an embedded style, and receive lifecycle events;
- render to an offscreen owned target and verify non-empty RGBA readback;
- exercise camera projection, style mutation, rendered queries, and cluster
  extensions;
- create and reopen a temporary offline database;
- create and close multiple maps repeatedly;
- verify native library extraction from the classifier JAR.

These tests provide headless coverage without Skiko reflection. Backend-specific
CI images install the matching graphics loader and software or virtual GPU
support where available.

### Headless GPU tests

`FakeDesktopMapHost` stops at the graphics boundary: it hands out invented
handles, so MapLibre never attaches a render session and nothing below
`render()` runs. Everything that only fails once MapLibre is asked to do the
work — style JSON, layer validity, expression compilation, rendered queries —
needs a real device, so `HeadlessVulkanMapHost` supplies one. It creates a
genuine Vulkan instance, device, and `VkImage` with no window and no external
memory extensions, which means it also runs on a software implementation such as
lavapipe.

Two vehicles use it:

- `HeadlessMapFixture` drives a real `DesktopMapSession` frame by frame, with no
  Compose at all. Used for anything that needs a rendered frame to assert on,
  including rendered-feature queries.
- `HeadlessVulkanMapHostFactory` provided through `LocalDesktopMapHostFactory`
  runs `MaplibreMap` itself under `runComposeUiTest`, so the surface composable,
  the session, the sources, and the layers all take their real paths.

This is how the "filter value must be a non empty array" layer failure was
found: an unset filter compiles to a null literal, which mbgl reads as "match
everything", but a scalar `true` substituted for it is rejected and takes the
whole layer with it. It reproduced in a test in under a second, having only
shown up as a dialog in the demo app before.

### Compose UI tests

With the fake host:

- map surface measurement and scale changes;
- pointer and keyboard input routing;
- click/long-click coordinate reporting;
- disposal on navigation and conditional composition;
- multiple maps in one window;
- overlays above and below the map, clipping, alpha, and transforms.

### Packaged application tests

For each OS:

- build the native distribution;
- inspect it for Java 25, the FFI runtime, and LWJGL natives;
- install or unpack it in a clean environment;
- launch without a source checkout or native-library path overrides;
- capture startup logs and fail on ABI, backend, native-access, or extraction
  errors.

## Machine validation matrix

Automated CI compilation is necessary but not sufficient for GPU interop. Track
results for these environments before merging:

| OS      | Architecture | Display/GPU coverage                                      | Initial backend |
| ------- | ------------ | --------------------------------------------------------- | --------------- |
| Linux   | x64          | X11 and Wayland; Mesa Intel/AMD; NVIDIA when available    | Vulkan          |
| Linux   | arm64        | Wayland or X11 on a real ARM64 machine                    | Vulkan          |
| Windows | x64          | integrated and discrete GPU; 100% and high DPI            | Vulkan          |
| Windows | arm64        | real Windows ARM64 hardware when the runtime is published | Vulkan          |
| macOS   | arm64        | Apple Silicon; Retina and external non-Retina display     | Metal           |

Run:

- cold and warm launch;
- remote and embedded base styles;
- pan, zoom, rotate, pitch, keyboard control, click, and feature query;
- add/update/remove every source and layer family used by the demo;
- animated layers and synchronous GeoJSON updates;
- resize, maximize, minimize/restore, and move between monitors;
- fractional and integer display scaling;
- open/close the map repeatedly;
- navigate away during style and tile loading;
- two simultaneous maps;
- suspend/resume or screen lock where applicable;
- offline download, restart, and display;
- ten-minute interaction soak and longer idle soak;
- application quit from the window manager and operating-system quit action.

Record driver, display server, Java, Compose/Skiko, FFI snapshot, and runtime
classifier with each result.

Validate the compose-glfw host on at least Linux x64 and one additional
operating system before declaring the SPI usable.

## Completion checklist

- [x] Java 25 is used for desktop compilation, execution, tests, and packaging.
- [x] Android retains its intended bytecode target.
- [~] Linux Vulkan renders through the FFI. Windows Vulkan and macOS Metal are
  implemented but have never run on hardware.
- [x] The default Skiko host is replaceable through the public host SPI.
- [ ] A compose-glfw fixture renders through the same map session. Not started;
      the headless Vulkan host covers the equivalent ground for tests.
- [x] Multiple maps and repeated create/dispose cycles are stable.
- [x] Runtime events drive lifecycle callbacks and repaint scheduling.
- [x] Camera, gestures, projection, density, and bounds are implemented.
- [x] Every desktop source and layer actual is implemented.
- [x] Images and rendered feature queries are implemented.
- [x] Compose resources load through the runtime resource boundary.
- [x] Desktop offline APIs are implemented and persistence-tested.
- [x] Every known FFI gap has a specific `TODO(maplibre-native-ffi)` comment and
      defined behavior, plus an entry in `MAPLIBRE_NATIVE_FFI_FEEDBACK.md`.
- [x] No executable desktop `TODO()` remains in anything the FFI backs.
      `DesktopOrientationProvider` is still a stub and stays one: device
      orientation is not something the FFI provides, so it is out of scope.
- [x] JNI bindings modules, vendored submodules, and native build CI are gone.
- [x] Demo distributions include exactly one correct FFI runtime.
- [x] Every FFI capability the MapLibre Compose public API asks for is
      integrated. Audited by diffing the public surface of `MapHandle`,
      `RuntimeHandle`, and `RenderSessionHandle` against desktop call sites. The
      remainder is unused for a stated reason: the typed `add*Source`/
      `add*Layer` entry points, because they cannot express what the common API
      offers — there is no typed adder for fill, line, circle, or symbol layers,
      and the GeoJSON source adders take no options, so a clustered source is
      impossible through them. Creation therefore goes through the generic style
      JSON, while property updates use `setLayerProperty` and the typed source
      setters, the same shape Android uses. Also unused: the owned-texture and
      surface attach modes, because the hosts render into borrowed textures; and
      capabilities with no common API to reach them — style light, projection
      mode, feature-state writes, custom geometry sources, location indicator
      layers, resource transforms, offline database merge, and still images.
      Wiring any of those means designing a cross-platform API first, so they
      are recorded in `COMMON_API_GAPS.md` rather than done here. Finally
      `createProjection`, whose semantics do not match ours: it returns a
      snapshot of the transform, while `CameraProjection` is a live view.
- [~] Automated tests pass (50 desktop tests). The machine validation matrix
  covers Linux x64 only.
- [x] Getting-started, contribution, roadmap, and release documentation describe
      the new integration and Java 25 requirement.

## Implementation notes to keep current

Update this section as the branch develops:

- FFI snapshot version/commit used: `0.1.0-SNAPSHOT`; binding
  `0.1.0-20260725.055919-2`, Vulkan runtime `0.1.0-20260725.060227-2`.
- Compose/Skiko version used by the reflection adapter: holding at Compose
  Multiplatform 1.10.3 / skiko 0.9.37.4. All seven classes the FFI Compose
  example reflects into (`SkiaLayer`, `ComposeWindow`, `MetalRedrawer`,
  `Direct3DRedrawer`, `LinuxOpenGLRedrawer`, `LinuxOpenGLRedrawerKt`,
  `AWTLinuxDrawingSurfaceKt`) exist at that version, and
  `SkikoReflectionContractTest` now confirms the reflected _members_ exist too,
  so a Compose upgrade that moves one fails the build rather than blanking the
  map at runtime. Note Compose 1.10.3 spells the Skia canvas accessor
  `nativeCanvas`; `skiaCanvas` is the 1.11 name the FFI example uses.
- Runtime classifiers verified: `natives-linux-x64` loads and reports
  `[VULKAN]`. Published but untested here: `natives-linux-arm64`,
  `natives-windows-x64`, `natives-windows-arm64`, `natives-macos-arm64`.
- Java: toolchain 25 for every JVM compilation (`jvmToolchain`), desktop
  bytecode 25 (`desktopJvmTarget`), Android bytecode unchanged at 11
  (`androidJvmTarget`). `buildSrc` pins 25 separately because it cannot read the
  root `gradle.properties`. Gradle 9.3 runs on Java 25.
- Cache/runtime sharing decision: **two runtimes can share one persistent cache
  database**, measured by `SharedCacheDatabaseTest` rather than assumed. Two
  runtimes on two threads opened the same cache file and both pumped without
  error. The offline manager can therefore own a runtime of its own, and desktop
  does not need a process-level runtime service with every map serialized onto
  one owner thread. The test stays in the suite so a future FFI snapshot that
  changes this fails loudly instead of corrupting a user's cache.
- FFI gaps found: no visible-region API, no meters-per-pixel API, no maximum-FPS
  control, and no animation-completion signal. See "Confirmed FFI gaps" below.
- Known issue: offline status reads and status events publish to the same
  Compose state with no ordering guard, so a resume can briefly show a stale
  progress value before the next event corrects it. Cosmetic; needs a sequence
  number per region.
- Layer/source ordering: Compose adds a layer to the style _before_ the effect
  that adds its source. The applier inserts nodes and calls `onEndChanges`,
  which is where `LayerManager` reaches MapLibre, and only afterwards dispatches
  remember-observers, where `SourceReferenceEffect` lives. The mobile SDKs
  tolerate a layer naming a source that does not exist yet; the C API rejects
  it. Desktop works around it by having a layer attach its own source first,
  with `Source.attach` made idempotent so the effect's later add is harmless.
  Worth fixing in the shared layer instead: the ordering is fragile on every
  platform, and only desktop is strict enough to notice.
- Display scale: Compose Desktop under XWayland reports density 1.0 on a 1.7x
  display, so the map is rendered at 1x and upscaled by the compositor. The
  extent is logged with the first frame and reads `scale=1.0`. Nothing in the
  desktop path can fix this; validating fractional scaling needs compose-glfw,
  Windows, or macOS.
- Machine validation results: Linux x64 / Wayland+XWayland / Vulkan-to-OpenGL
  rendered the demotiles style at commit 6a5088d3, confirmed by screenshot.
- Suspend/resume note: after the machine slept, the desktop map stopped
  rendering with both the renderer thread and the AWT event thread parked idle
  and no error logged. It was not a code regression — the same commit renders
  after a fresh boot. Losing the GPU contexts underneath the Vulkan-to-OpenGL
  sharing appears to leave the frame loop with nothing to wake it. Surface loss
  and recovery is on the machine matrix; this is the first evidence it needs
  real handling rather than the current "host reports a new generation"
  assumption.
- Known issue for step 7: the demo logs
  `loading style failed: http: invalid authority`. The built-in loader cannot
  resolve the demo's non-HTTP style URI, which is what the desktop resource
  adapter is for.

Toolchain facts measured against the published snapshot rather than assumed:

- The binding is compiled to Java 24 bytecode (class file major 68), so the
  desktop target cannot run below Java 24. The plan's Java 25 clears this.
- The binding carries Kotlin metadata `mv 2.4.0` (built with Kotlin 2.4.10), but
  this repo's Kotlin 2.3.21 compiles against it cleanly. **No Kotlin upgrade is
  required**, so the rewrite does not drag a Kotlin/Compose bump along with it.
- The `natives-*` classifier jar self-extracts with no library-path overrides;
  the only JVM argument needed is `--enable-native-access=ALL-UNNAMED`.

Rough edges found in the FFI itself are collected in
[MAPLIBRE_NATIVE_FFI_FEEDBACK.md](./MAPLIBRE_NATIVE_FFI_FEEDBACK.md) for
upstreaming, so the workarounds below can be removed rather than kept.

## Native semantics that constrain the implementation

Read out of the FFI bindings, the C headers, and the working Compose example
before writing `DesktopMapSession`. Recorded here because most of it is
observable only by reading native source or by debugging a failure.

### Threading and lifetime

- One live runtime per OS thread, enforced natively, and additionally rejected
  if the thread already has any active mbgl scheduler. Never create a runtime on
  a pooled dispatcher thread, the AWT EDT, or a Skiko render thread.
- A runtime whose `close()` failed leaves its thread permanently unable to host
  another runtime. Owner threads must not be recycled unless close succeeded.
- Thread affinity is enforced in native C++ only; there is no Kotlin-side
  assertion, so a stray Compose-thread call throws `WrongThreadException` with a
  stack trace pointing at the FFI boundary rather than the caller. The session
  adds its own owner-thread assertion.
- Close order is mandatory and enforced: projections, then
  `RenderSessionHandle`, then `MapHandle`, then `RuntimeHandle`, each on the
  owner thread. A failed close leaves the handle live and must be retried;
  swallowing the exception leaks native memory. There is no JVM finalizer or
  cleaner, so teardown must be `try`/`finally` on the owner thread itself.
- `MapProjectionHandle` is the one handle that is _not_ a child of its parent:
  `MapHandle.close()` succeeds while projections are live and they keep working.
  They must be tracked and closed explicitly. A projection is also a frozen
  snapshot, so a cached one returns stale results after any camera change; use
  `pixelForLatLng` / `latLngForPixel` directly instead.
- `RuntimeHandle.close()` can spin indefinitely waiting for in-flight
  resource-provider callbacks running on network threads. Provider callbacks
  must be non-blocking, and outstanding requests must be quiesced before close.

### Event pump

- `pollEvent()` returns one event per call and `null` when the queue is empty
  _at that instant_. The pump is one `runOnce()` followed by drain-until-null.
  An empty queue is never a quiescence signal: offline events are enqueued from
  a database thread and can appear mid-drain.
- `runOnce()` never blocks and there is no wake, notify, or has-work query. The
  owner loop cannot park on a blocking queue take, or native loading stalls with
  no error. It also is not bounded to one task, so a single call can run for an
  unbounded time.
- Event types are `@JvmInline value class` wrappers over `Int`, not enums, so
  `when` gets no exhaustiveness checking and unknown native values pass straight
  through. The translation table logs unknown types rather than failing.
- `RuntimeEvent` payloads are fully copied and safe to cross threads. The
  `mapSource` and `runtimeSource` fields are not: they are live thread-affine
  handles, and `mapSource` resolves through a `WeakReference`, so the session
  must hold a strong reference to its `MapHandle` or events become
  unattributable under memory pressure.
- `pollEvent()` is not a pure read. On `MAP_STYLE_LOADED` it makes native calls
  on the source map, so it can throw from the map and must never be skipped or
  moved off the owner thread.
- Closing a map purges its queued events, and closing the runtime discards all
  of them. Teardown must never await a terminal event; it will not arrive.
- Style load failures arrive **only** as `MAP_LOADING_FAILED` events, never as
  exceptions from the style setters.

### Rendering

- `renderUpdate()` reports "nothing to render" by throwing
  `InvalidStateException`, the same type as a genuinely detached or closed
  session. It is distinguished by its diagnostic text. Treating it as an error
  fails every map on its first frame; swallowing all `InvalidStateException`
  spins forever on a dead session.
- **Borrowed-texture sessions cannot be resized.** `resize()` throws
  `UnsupportedFeatureException`, and there is no re-attach API. A size or scale
  change means: close the session, replace the host texture, build a new
  descriptor, attach again. This is what `DesktopRenderTarget.generation` exists
  to signal.
- A map allows at most one live render session; attaching a second throws rather
  than replacing. `detach()` does not release the parent retention, so a
  detached handle still blocks `MapHandle.close()`.
- `RenderTargetExtent` is **logical**. The borrowed texture must be
  `ceil(logical * scaleFactor)` physical pixels, and nothing validates that it
  is; a mismatch renders garbage silently rather than throwing.
- `renderUpdate()` is synchronous to GPU completion, so no host-side fence is
  needed before sampling the target — but it blocks the owner thread for the
  whole frame.
- Zero width or height passes the Kotlin validators and is rejected natively.
  Compose reports a zero size on first layout routinely, so map creation and
  attach must be deferred until the extent is non-empty.

### Camera

- The map's `pixelRatio` is fixed at `MapHandle.create` and is not updated by
  attach or resize. Moving a window between displays of different density
  requires recreating the map, not just re-attaching.
- All projection input and output is in **logical** pixels with a top-left
  origin; `scaleFactor` participates nowhere. Multiplying pointer positions by
  density before projecting double-applies the scale on HiDPI displays.
- There is no way to learn that a specific animation finished.
  `MAP_CAMERA_DID_CHANGE` fires identically for a jump, a completed ease, a
  cancellation, and a superseded transition, so the session stamps each
  animation request with a generation and ignores stale completions.
- `easeTo`/`flyTo` with a null animation is an instant jump, not a
  default-length animation.
- `cameraForLatLngBounds(bounds, null)` returns padding of zero, so applying the
  result verbatim silently clears the map's edge insets.
- Assigning an all-null `BoundOptions()` is a no-op, not a reset. Clearing a
  bounds constraint requires assigning world bounds explicitly.
- Camera and options objects are mutable and lack `equals`, so they are
  converted to immutable snapshots on the owner thread before reaching Compose
  state.
- Animations advance only while the runtime is pumped, so the owner loop keeps
  ticking while a transition is outstanding.

### Confirmed FFI gaps

Each needs a `TODO(maplibre-native-ffi)` at its boundary and a local fallback:

- **No visible-region API.** `latLngBoundsForCamera` is axis-aligned, so it is
  wrong for a rotated or pitched camera. Fallback: project the four viewport
  corners with `latLngsForPixels`. This requires tracking the map's logical size
  ourselves, because `MapHandle` exposes no size accessor.
- **No meters-per-pixel API.** Fallback: reimplement mbgl's formula, noting it
  uses a 512px tile size, or derive it from two `latLngForPixel` calls.
- **No maximum-FPS control.** Fallback: rate-limit `renderUpdate()` in the
  session rather than sleeping the owner thread.
- **No animation-completion signal.** Fallback: the generation counter above.
- **No way to clear a resource provider**, and it must be installed before any
  map exists. Ordering is: create runtime, set provider, create map.
