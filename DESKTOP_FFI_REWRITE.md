# Desktop MapLibre Native FFI rewrite

## Status

This document is the implementation plan for replacing the desktop JNI
integration with the Kotlin Multiplatform bindings published by
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi).

Merge this plan before implementation begins. The rewrite then lands end to end
on a dedicated follow-up branch. Intermediate commits may introduce the new
implementation in layers, but the completed implementation has one desktop path
and no runtime fallback to the legacy JNI code.

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
- Nix dependencies, retaining graphics loaders needed to run the FFI;
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
   - Update public docs, development tasks, Nix environment, roadmap, and
     release notes.

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

- [ ] Java 25 is used for desktop compilation, execution, tests, and packaging.
- [ ] Android retains its intended bytecode target.
- [ ] Linux Vulkan, Windows Vulkan, and macOS Metal render through the FFI.
- [ ] The default Skiko host is replaceable through the public host SPI.
- [ ] A compose-glfw fixture renders through the same map session.
- [ ] Multiple maps and repeated create/dispose cycles are stable.
- [ ] Runtime events drive lifecycle callbacks and repaint scheduling.
- [ ] Camera, gestures, projection, density, and bounds are implemented.
- [ ] Every desktop source and layer actual is implemented.
- [ ] Images and rendered feature queries are implemented.
- [ ] Compose resources load through the runtime resource boundary.
- [ ] Desktop offline APIs are implemented and persistence-tested.
- [ ] Every known FFI gap has a specific `TODO(maplibre-native-ffi)` comment and
      defined behavior.
- [ ] No executable desktop `TODO()` or placeholder `Nothing` remains.
- [ ] JNI bindings modules, vendored submodules, and native build CI are gone.
- [ ] Demo distributions include exactly one correct FFI runtime.
- [ ] Automated tests and the machine validation matrix pass.
- [ ] Getting-started, contribution, roadmap, and release documentation describe
      the new integration and Java 25 requirement.

## Implementation notes to keep current

Update this section as the branch develops:

- FFI snapshot version/commit used:
- Compose/Skiko version used by the reflection adapter:
- Runtime classifiers verified:
- Cache/runtime sharing decision:
- FFI gaps found:
- Machine validation results:
