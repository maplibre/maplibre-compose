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

Built, in `glfw-fixture/`, and validated on macOS arm64. It runs the whole demo
application — not a lone map — because "the same public composable works" is
only worth asserting against the style switcher, the gesture demos, and the
offline screens as well. `:lib:maplibre-compose` does not depend on it; the
dependency runs fixture → demo-app → library, so neither of the other two knows
compose-glfw exists.

The graphics half of the SPI came through unchanged: `GlfwMetalMapHost` is the
same Metal-to-Metal bridge as `MacosMetalHost` with the reflection deleted,
since compose-glfw publishes both the `MTLDevice` and Skia's `DirectContext` as
fields of `MetalRenderContext`. No SPI type needed a change to accommodate it.
What the fixture did find:

- **A retired target can still be handed back to `draw`.** The surface presents
  the last target that was rendered into while MapLibre catches up with a new
  size, so freeing on the generation bump — which `MacosMetalHost` did — hands
  Skia a released `MTLTexture` and traps inside `CFRetain`. Now stated on
  `DesktopRenderTarget.generation` and honoured by both Metal hosts. The Linux
  and Windows hosts were already safe by accident: their `draw` implementations
  ignore the handles on the target they are passed and present their own current
  texture.
- **Clearing the last completed target on resize caused a flicker**, not just a
  stretch. Every skipped frame during a drag painted transparent. AWT coalesces
  resize events enough to hide it; a GLFW window reports every intermediate size
  and the map flickered through the window background for the length of the
  drag. Reversed in `DesktopMapDrawState`.
- **`Dispatchers.Main` is the one place the library still assumes AWT.**
  `:lib:maplibre-compose` declares `kotlinx-coroutines-swing` itself and
  `DesktopOfflineManager` posts to `Dispatchers.Main`, so the desktop target
  quietly requires the AWT event thread to exist even though its graphics host
  is an SPI. It is not reachable through the SPI, and it is not only ours:
  `androidx.lifecycle` decides which thread is "the main thread" by running a
  block on `Dispatchers.Main`, so with coroutines-swing present every
  `addObserver` from a GLFW thread throws and navigation cannot complete a
  transition, while without it `repeatOnLifecycle` — which
  `rememberUserLocationState` uses — fails outright. The fixture supplies its
  own `MainDispatcherFactory` to close the gap; the durable fix belongs in
  compose-glfw, which has a UI dispatcher already and only needs to register it.
- **Two things every host must write from scratch**: a renderer thread, because
  the SPI requires a stable one through `withRendererAccess` without supplying
  one, and Objective-C messaging, because the SPI is defined in backend-neutral
  handles and neither project publishes a helper. Roughly 140 lines, duplicated
  knowingly.

Not covered: fractional display scale. macOS reports a content scale of exactly
2.0 on a Retina display whatever the display mode, so the case compose-glfw
uniquely enables still needs a Linux/Wayland machine to exercise.

## Runtime, threading, and lifecycle

Start with one runtime per map, on one dedicated owner thread per map. This
matches the working FFI Compose example and isolates maps and graphics contexts.
Multiple simultaneous maps therefore use separate runtime owner threads.

Before offline support is finalized, test whether multiple runtimes can safely
use the same persistent cache database. If shared database access is not
supported, introduce a process-level runtime service and serialize its maps on
one owner thread. Record the result next to the runtime factory; do not hide the
choice inside the offline implementation.

The runtime and map are on that owner thread; the **render session is not**.
Since maplibre-native-ffi #399 a session's owner is whichever thread attached
it, so the host's presenting thread attaches, renders, resizes and closes its
own session against the map the owner thread publishes. Rendered feature queries
and feature state go with the session, because that is where they live.

Everything else touching runtime, map, or projection handles executes on the
owner thread — blocking when the caller needs an answer, posted when it does
not. Immutable copied results cross back to the Compose thread. Callbacks are
invoked from the owner thread, and enter Compose state as snapshot writes.

This split is why the map advances at all while nothing is drawing. Before it,
the session had to pump inside the Compose draw pass, which coupled style
parsing and tile loading to the display and needed a bit that kept asking for
frames until MapLibre said it was idle. See `DesktopMapRuntimeLoop`.

Creation order:

1. Select the desktop host and backend.
2. Create the host surface.
3. On the first non-empty extent, start the map runtime thread.
4. Create `RuntimeHandle` on it, and install the resource provider with it.
5. Create `MapHandle`, replay any setup made before it existed, publish it.
6. Attach a render session, on the presenting thread, against the published map.
7. Load the initial style and apply the requested camera/options.

Close order:

1. Stop accepting input and frame requests.
2. Close `RenderSessionHandle`, on the thread that attached it.
3. Close projections and outstanding map-owned operations.
4. Resolve anything awaiting an event that will not arrive.
5. Close `MapHandle`.
6. Drain or cancel runtime-owned operations and callbacks.
7. Close `RuntimeHandle` and its wake source.
8. Release producer render targets and contexts.
9. Release consumer imports and host objects.

Steps 2 and 5 are on different threads, so teardown is a handshake: the
presenting thread closes the session, then joins the owner thread, which is what
destroys the map. Native refuses to destroy a map that still has a session
attached, and the owner thread waits — bounded — for that to have happened, so a
presenting thread that already stopped cannot wedge teardown.

Every close operation is idempotent at the Compose layer. Disposal, window
close, recomposition with a new map key, and surface loss have tests. Cleaner
behavior in the binding remains a safety net rather than the normal lifecycle.

## Frame and event loop

Two loops, and only one of them is a frame loop.

The owner thread parks in `RuntimeHandle.pump(timeout)` and is released by the
runtime's wake flag — set by style, tile, offline and resource responses, by
queued events, and by a `WakeSource` this side signals when it posts work. Each
pass drains its task queue, pumps, and drains the event queue. The park is
bounded rather than indefinite, because timers and ready sockets set the flag
only when they queue owner-thread work.

The presenting thread's frame tick:

1. starts or replaces the runtime loop if the extent's scale factor changed;
2. attaches or re-attaches the render session if the target generation changed;
3. consumes the render-request bit, set by the owner thread when MapLibre
   published an update;
4. calls `renderUpdate()`, and re-requests a frame when it reports nothing was
   drawn;
5. completes producer synchronization;
6. asks the host to draw or present the most recently completed target.

Frames are requested rather than continuous: `MAP_RENDER_UPDATE_AVAILABLE` and a
`needsRepaint` frame-finished are what ask for one. Note that camera transitions
still need them — mbgl advances a transition from `onDidFinishRenderingFrame`
while `transform.inTransition()`, so pumping alone does not move the camera.

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

| OS      | Architecture | Display/GPU coverage                                          | Initial backend |
| ------- | ------------ | ------------------------------------------------------------- | --------------- |
| Linux   | x64          | X11 and Wayland; Mesa Intel/AMD; NVIDIA when available        | Vulkan          |
| Linux   | arm64        | Wayland or X11 on a real ARM64 machine                        | Vulkan          |
| Windows | x64          | integrated and discrete GPU; 100% and high DPI                | Vulkan          |
| Windows | arm64        | real Windows ARM64 hardware when the runtime is published     | Vulkan          |
| macOS   | arm64        | Apple Silicon Retina, at 2.0 scale; external display untested | Metal           |

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
- [~] Linux Vulkan and macOS Metal both render through the FFI on hardware, and
  macOS runs the full GPU test suite on Vulkan over MoltenVK. Windows Vulkan is
  implemented but has never run on hardware.
- [x] The default Skiko host is replaceable through the public host SPI.
- [x] A compose-glfw fixture renders through the same map session.
      `glfw-fixture` runs the whole demo application in a GLFW window on macOS
      arm64, through the same `DesktopMapSession`, with no reflection and no AWT
      in the graphics path. Resizes retarget the live session rather than
      re-attaching — one `Rendered the first map frame` line per session, across
      drag resizes in both directions. Two SPI fixes came out of it; see
      "Alternative hosts".
- [x] Multiple maps and repeated create/dispose cycles are stable.
- [x] Runtime events drive lifecycle callbacks and repaint scheduling.
- [x] Camera, gestures, projection, density, and bounds are implemented.
- [x] Every desktop source and layer actual is implemented, and now tested
      rather than merely present. `LayerPropertyRoundTripTest` writes every
      declared setter on all nine layer types — 119 cases, each both before and
      after attachment — and asserts what MapLibre reports back. That is what
      found `iconOverlap`/`textOverlap` rejecting the whole layer, a live setter
      throwing into the composition, and reconstructed base-style sources
      carrying `SourceType(nativeValue=1)` as their type. Two limits are
      recorded at their boundaries rather than claimed: some tileset fields are
      parsed into something MapLibre never serializes, and a base-style source
      cannot be re-added at all, because the FFI reports only its type,
      volatility, and attribution.
- [x] Images and rendered feature queries are implemented.
- [x] Compose resources load through the runtime resource boundary, and the
      provider no longer blocks the thread it is called on. It queues to a
      worker instead, because `RuntimeHandle.close()` waits on in-flight
      provider callbacks. Spaces, Unicode paths, packaged jars, missing
      resources, cancellation, and shutdown with a read in flight all have tests
      now; they did not before. Measured while doing it: `file:` never reaches
      this provider, because mbgl routes it to its own local file source, so the
      jar case is the one that proves the path end to end.
- [x] Desktop offline APIs are implemented and persistence-tested — genuinely,
      as of `DesktopOfflinePackTest`. Until then this box was ticked against no
      test that created a pack at all. A pack now survives a manager dispose and
      a reopen of the same cache path with its definition and metadata, as does
      a deletion and a completed download's resource count.
- [x] Every known FFI gap has a real implementation or a settled explanation at
      its boundary. The four that blocked the rewrite are resolved: three fixed
      upstream in #441, the fourth declined there with reasons.
      `MAPLIBRE_NATIVE_FFI_FEEDBACK.md` is down to the process-exit lifecycle
      crash — a workaround rather than a gap, and still never filed upstream.

      Three new `TODO(maplibre-native-ffi)` markers appeared afterwards, which is
      the layer round-trip tests doing their job rather than a regression: MapLibre
      Native implements neither `icon-overlap` nor `text-overlap`, and the FFI
      reports too little about a base-style source to re-add one. Those are limits
      in what is underneath us, named where a reader meets them.
- [x] No executable desktop `TODO()` remains in anything the FFI backs.
      `DesktopOrientationProvider` is still a stub and stays one: device
      orientation is not something the FFI provides, so it is out of scope.
- [x] JNI bindings modules, vendored submodules, and native build CI are gone.
- [x] Demo distributions include exactly one correct FFI runtime.
- [x] Every FFI capability the MapLibre Compose public API asks for is
      integrated. Audited by diffing the public surface of `MapHandle`,
      `RuntimeHandle`, and `RenderSessionHandle` against desktop call sites. The
      remainder is unused for a stated reason. The typed `add*Layer` entry
      points, because upstream declined to complete them
      ([#361](https://github.com/maplibre/maplibre-native-ffi/issues/361),
      closed "won't do"): typed adders exist only where typing buys something
      beyond construction, and for the rest they would freeze a snapshot of the
      style spec into the ABI. Creation therefore goes through the generic style
      JSON, while property updates use `setLayerProperty` and the typed source
      setters, the same shape Android uses.

      Sources are the same JSON path, but now by choice rather than necessity:
      `synchronousUpdate` landed in #441, so the JSON path is no longer the only
      one that can express a GeoJSON source. It stays the default because a
      descriptor has to produce its own JSON anyway — for `attributionHtml`, and
      to replay itself after a style change — and one representation beats two.
      The two families the style spec cannot spell at all override it and use
      their typed adder: **custom geometry sources** and image sources carrying
      pixels.

      Also unused: the owned-texture and surface attach modes, because the hosts
      render into borrowed textures; and capabilities with no common API to reach
      them — style light, projection mode, feature-state writes, location
      indicator layers, style transition options, HTTP header transforms, missing
      style images, resource transforms, offline database merge, and still
      images. Wiring any of those means designing a cross-platform API first, so
      they are recorded in `COMMON_API_GAPS.md` rather than done here. Finally
      `createProjection`, whose semantics do not match ours: it returns a snapshot
      of the transform, while `CameraProjection` is a live view.
- [~] Automated tests pass (162 desktop tests across 38 classes, none skipped).
  The machine validation matrix covers Linux x64 and macOS arm64; Windows is
  untested.

      The count grew from 105 in one pass because an audit found the checklist was
      claiming coverage the suite did not have — offline had no test that created
      a pack, resources were tested only as string classification, and six layer
      families and three source families had none at all. Treat a ticked box here
      as a claim to check, not a result; three of them were wrong.

      "None skipped" now means something. Every GPU-backed test used to open with
      `HeadlessMapFixture.createOrNull() ?: return`, and a test that returns
      before asserting is recorded by JUnit as **passed** — so a machine without a
      Vulkan loader ran the whole suite green while executing none of it, and
      nothing in the report distinguished that from real coverage. A working
      loader is a requirement of this suite rather than a nice-to-have, so
      `HeadlessVulkanMapHost.create()` now throws, naming `mise run bootstrap`.
      The nullable factory is gone, which is what stops the pattern coming back.
- [x] Getting-started, contribution, roadmap, and release documentation describe
      the new integration and Java 25 requirement.

## Implementation notes to keep current

Update this section as the branch develops:

- FFI snapshot version/commit used: `0.1.0-SNAPSHOT`; binding
  `0.1.0-20260803.074311-52`. The pin floats, so this record is the only thing
  tying a result to a build — and the float is deliberate: maplibre-native-ffi
  gets tagged only once MapLibre Compose is ready to ship against it.

  Moving from build 40 to build 52 was not free. Three APIs changed shape and
  the desktop target stopped compiling until each was migrated:
  `RuntimeOptions.maximumCacheSize` was removed in favour of a runtime setter
  (#441), `ResourceRequest.url` became `requestedUrl` plus `resolvedUrl` (#467),
  and `RenderBackend` gained `WEBGPU`. That is the cost of the floating pin, and
  it is worth recording that it is a real cost rather than a theoretical one.
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
  `[VULKAN]`; `natives-macos-arm64` does too, over MoltenVK, which is what the
  desktop test suite runs on there. Published but untested here:
  `natives-linux-arm64`, `natives-windows-x64`, `natives-windows-arm64`.
- Desktop tests take the **Vulkan** runtime on every platform, including macOS
  where an application ships Metal, because `HeadlessVulkanMapHost` has no Metal
  equivalent and backend negotiation otherwise declines — leaving every
  GPU-backed test asserting against a map that never rendered, and reporting
  green. macOS has no system Vulkan loader, so `mise run bootstrap` installs
  vulkan-loader and molten-vk; without them the tests skip rather than fail,
  which reads the same way.
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
- FFI gaps remaining: **none in the map API.** `synchronousUpdate`, stretchable
  image content insets, and the ambient cache size setter all landed in
  maplibre-native-ffi #441; the offline tile count limit was declined in the
  same PR, with reasons, which closes it rather than leaving it pending.
  `MAPLIBRE_NATIVE_FFI_FEEDBACK.md` is down to one entry — the process-exit
  lifecycle crash, which has still never been filed upstream. The visible
  region, meters per pixel, and maximum FPS are ours rather than gaps; see "Ours
  to own" below.
- Base-style layer restore: a replaced base layer used to come back stripped of
  its `filter` and `source-layer`, so a filtered layer redrew everything it was
  meant to exclude and one over a vector source came back empty. Restoring the
  whole reported object fixes it. Two things were learned doing so. The claim
  that `Layer` had no protected way to write a root key was stale —
  `setRootProperty` was already protected and `FeatureLayer` already used it —
  so only the filter needed a JSON-taking path alongside the compiled-expression
  one. And `metadata` is genuinely unrestorable, not merely unimplemented:
  probing `styleLayerJson` for a layer whose style JSON declared one shows no
  `metadata` key at all, because mbgl parses it and discards it. There is
  nothing to restore, so nothing pretends to.
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
  macOS arm64 / Metal-to-Metal rendered the demo's full style — every source,
  layer, and painter — at 2.0 display scale,
  `logical=800x572
  physical=1600x1144`, on the two-thread implementation. The
  Metal host bridge had never run on hardware before that.

  Re-run on macOS arm64 against the build-52 snapshot, specifically to exercise
  the retarget-on-resize path, which until then had only run on the headless
  Vulkan host. The window was drag-resized in forty incremental steps, growing
  1137x760 → 1375x872 and shrinking → 855x542, so every intermediate size was
  hit. The map stayed sharp and correct throughout, and
  `Rendered the first map frame` appears **exactly once** in the whole session —
  which is the proof that every one of those sizes retargeted the live session
  rather than attaching a new one. No host, texture, or deadlock diagnostics.
- Suspend/resume note: after the machine slept, the desktop map stopped
  rendering with both the renderer thread and the AWT event thread parked idle
  and no error logged. It was not a code regression — the same commit renders
  after a fresh boot. Losing the GPU contexts underneath the Vulkan-to-OpenGL
  sharing appears to leave the frame loop with nothing to wake it. Surface loss
  and recovery is on the machine matrix; this is the first evidence it needs
  real handling rather than the current "host reports a new generation"
  assumption.
- Frame failure recovery: a frame that throws is now read as a lost device and
  retried. `DesktopMapSurface` runs the surface-loss path against the renderer —
  which drops the render session and its target key — hands the surface back,
  and asks for the frame that retries it, because an idle map publishes no
  update that would ask for one on its own. Three consecutive failures latch the
  surface into `Failed` with the attempt count in the diagnostic; a frame that
  completes resets the budget. A failure the renderer marks fatal, meaning its
  runtime is gone rather than its device, latches immediately: retrying it would
  replace a reported failure with a blank map. This is what makes
  `onSurfaceLost` reachable from the Compose path at all — disposal uses
  `close()`, which subsumes it. What it does **not** cover is the half of the
  suspend/resume report that says nothing was logged: if Skiko or AWT stops
  delivering paint callbacks, or if a lost device draws nothing without
  reporting an error, no frame throws and there is nothing here to notice.
  Distinguishing those needs the machine that slept.
- Style switching: two desktop deviations from the mobile adapters, together,
  made the style selector crash with `Layer ID '...' not found in base style`
  thrown out of the applier. The mechanism that is supposed to prevent it is
  #269's: switching a style unloads the outgoing `SafeStyle`, and `LayerManager`
  skips anchor validation against an unloaded style, so content briefly composed
  into the dying node degrades to no-ops instead of throwing. Desktop never
  reached that state in time. It only reported the style that _loaded_, never
  that the previous one had gone — both mobile adapters call
  `onStyleChanged(this, null)` from `setBaseStyle`, which is what performs the
  unload. And it called `setBaseStyle` from a `LaunchedEffect`, which runs after
  every composition has applied, where `AndroidView`'s `update` block runs
  inside the parent's apply — before the content subcomposition applies its
  inserts. So even once desktop reported the unload, it reported it too late.
  Both are now fixed, and both are needed: the callback does the unloading, the
  `SideEffect` does it early enough. Reproduced against the demo by switching to
  OpenFreeMap Bright twelve seconds in — slowly, nothing racing — and confirmed
  fixed the same way.
- Camera across a map rebuild: `CameraState` re-applies its remembered position
  when the **adapter identity** changes, which is what an Android configuration
  change does. A desktop density change swaps the `MapHandle` inside one
  long-lived session, so Compose never sees it and the new map's first
  `onCameraMoved` overwrote the good value with MapLibre's default. The session
  now keeps its own keyed map configuration — camera, bound limits, debug
  overlays — and replays it onto every map it creates, reading the _live_ camera
  rather than the last requested one, since the user has usually panned since.
  The same record is what defers configuration made before the first map exists.
- Threading: the runtime and map moved off the presenting thread onto
  `DesktopMapRuntimeLoop` once maplibre-native-ffi #399 let a render session
  have a different owner than its map. That deleted the `isIdle` bit, the
  request-a-frame-until-MapLibre-says-stop loop, the repaint seam every style
  mutation had to cross, and the run-inline-when-there-is-no-host branch that
  was a wrong-thread hazard after surface loss. What it did not delete: a
  transition still needs rendered frames, because mbgl advances one from
  `onDidFinishRenderingFrame`.

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
  `RenderSessionHandle`, then `MapHandle`, then `RuntimeHandle`. Each closes on
  the thread that owns it, which for the session is the thread that attached it
  and need not be the map's. A failed close leaves the handle live and must be
  retried; swallowing the exception leaks native memory. The binding registers
  handles with a cleaner that _reports_ a leak rather than reclaiming it —
  correctly, since destruction is owner-thread-bound — so teardown is still
  `try`/`finally` on the owning thread.
- `MapProjectionHandle` is the one handle that is _not_ a child of its parent:
  `MapHandle.close()` succeeds while projections are live and they keep working.
  They must be tracked and closed explicitly. A projection is also a frozen
  snapshot, so a cached one returns stale results after any camera change; use
  `pixelForLatLng` / `latLngForPixel` directly instead.
- `RuntimeHandle.close()` blocks on in-flight resource-provider callbacks
  running on network threads. Provider callbacks must be non-blocking, and
  outstanding requests must be quiesced before close.
- The same rule, harder, for **custom geometry sources**: the binding wraps the
  tile callback in a `CallbackGate` whose `close()` **spin-waits** for active
  callbacks, and it is closed from `removeStyleSource` and `setStyleJson` — both
  on the owner thread. So a `fetchTile` that hops to the owner thread and waits
  deadlocks outright, and one that merely computes there stalls the next style
  change for as long as the computation takes. `ComputedSource` therefore does
  nothing on the callback thread but record the tile id and hand it to a
  single-thread executor of its own.
- A `WakeSource` is its own native handle and outlives the runtime it came from,
  so closing the runtime does not release it.

### Event pump

- `pollEvent()` returns one event per call and `null` when the queue is empty
  _at that instant_. The pump is one `pump()` followed by drain-until-null. An
  empty queue is never a quiescence signal: offline events are enqueued from a
  database thread and can appear mid-drain.
- `pump(timeout)` parks the owner thread and is released by the runtime's wake
  flag, by a queued event, or by `WakeSource.signal()`. A zero timeout drains
  and returns, which is what a caller pumping from a frame callback passes. The
  park clears the flag _before_ it drains, so a host with a task queue of its
  own must look at that queue before it parks, not only after. The drain is not
  bounded to one task, so a single call can run for the length of a style parse
  — and timers or ready sockets set the flag only when they queue owner-thread
  work, so the timeout must be bounded rather than indefinite.
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

- `renderUpdate()` returns false when there was nothing to draw, which is
  ordinary before the style's first update and after an attach until the owner
  thread pumps the new size. Anything it throws is a real failure.
- The render session's owner is the thread that attached it, which need not be
  the map's owner thread. Every session call, close included, reports the
  wrong-thread error from anywhere else.
- **Borrowed-texture sessions still cannot be resized** — `resize()` throws
  `UnsupportedFeatureException`, because a borrowed texture is sized by its
  owner — but since maplibre-native-ffi #485 they can be **retargeted**. A live
  session takes a replacement texture through `set*BorrowedTextureTarget` and
  keeps its renderer, and with it the tile pyramid, the glyph and image atlases,
  symbol placement, and renderer-held feature state. So a size change is:
  allocate the new host texture, build a new descriptor, hand it over. This is
  what `DesktopRenderTarget.generation` signals, and `ensureAttached` follows it
  in place.

  The exception is a **scale factor** change, which must still close and attach:
  a renderer compiles its shaders for one pixel ratio. That case also needs a
  new map, since `MapHandle`'s `pixelRatio` is fixed at creation, so it is
  handled by replacing the runtime loop rather than inside `ensureAttached`.

  This supersedes an earlier note here arguing the close-and-attach path cost
  nothing because the supported resize also called `renderer.reset()`. That was
  true of build 40 and is not true now: every resize was discarding the tile
  pyramid and refetching. `DesktopMapResizeTest` asserts on the session's attach
  and retarget counts, because both paths render the same scene at the same size
  and the difference is otherwise invisible.
- A map allows at most one live render session; attaching a second throws rather
  than replacing. `detach()` releases the parent retention, as does `close()`.
- `RenderTargetExtent` is **logical**, and a borrowed-texture descriptor states
  its physical size separately because not every physical size is reachable from
  a logical extent. MapLibre rejects a pair that does not agree, which is what
  makes one rounding rule — `ceil(logical * scaleFactor)`, in `DesktopMapExtent`
  — load-bearing.
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
- `AnimationOptions.transitionId` stamps a transition, and
  `MAP_CAMERA_TRANSITION_FINISHED` reports that it released the camera — exactly
  once, whether it ran to completion, was superseded, was cancelled, or was an
  instant jump. It carries identity, not an outcome, so telling completion from
  cancellation is still the caller's job. `MAP_CAMERA_DID_CHANGE` cannot do
  this: it fires identically for all four.
- `easeTo` with a null animation is an instant jump; `flyTo` with a null
  animation derives a duration from a default velocity and genuinely animates.
- `cameraForLatLngBounds(bounds, null)` returns padding of zero, so applying the
  result verbatim silently clears the map's edge insets.
- Assigning an all-null `BoundOptions()` is a no-op, not a reset; the field mask
  is the contract. `BoundsConstraint.Unbounded` is the reset, and it is not the
  same as world bounds, which clamp longitude and stop the map panning across
  the antimeridian.
- Option types compare by value, but they are still mutable, so they are
  converted to immutable public snapshots on the owner thread before reaching
  Compose state.
- A transition advances per **rendered frame**, not per pump: mbgl re-enters
  `onUpdate()` from `onDidFinishRenderingFrame` while `transform.inTransition()`
  (`map_impl.cpp:270`). A map that is pumped but never drawn stops after the
  first step.

### Ours to own

Not gaps, and not worth asking for: the core has no such query either, so both
mobile SDKs build these in their own language.

- **The visible region.** `latLngBoundsForCamera` is axis-aligned, so it is
  wrong for a rotated or pitched camera. Project the four viewport corners with
  `latLngsForPixels`, sized from `MapHandle.size`.
- **Meters per pixel.** `mbgl::Projection::getMetersPerPixelAtLatitude` is a
  stateless static that both SDKs forward to; transcribe it, noting the 512px
  tile size.
- **Maximum FPS.** MapLibre produces no frames of its own here, so the call rate
  is the frame rate: rate-limit `renderUpdate()` rather than sleeping the owner
  thread.

Gaps that remain the FFI's are in
[MAPLIBRE_NATIVE_FFI_FEEDBACK.md](./MAPLIBRE_NATIVE_FFI_FEEDBACK.md), each with
a `TODO(maplibre-native-ffi)` at its boundary.
