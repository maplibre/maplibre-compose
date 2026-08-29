# Map API redesign

This document specifies the target map API and the implementation plan. It is
the source of truth for the redesign. The type names below are part of the
design. Small signature changes are acceptable when Kotlin or Compose requires
them, but the ownership and lifetime rules are not optional.

## Objective

The API must separate resources that have different lifetimes:

- A runtime owns application-wide MapLibre resources.
- A map state owns one logical map.
- A presentation connects that map to one UI surface.
- A style composition defines reusable declarative style content.
- A snapshotter owns a separate map that never attaches to UI.
- A presentation host provides window-specific rendering resources.

One logical map can have at most one UI presentation. Native platforms retain
the engine map while the UI is absent. Web destroys its GL JS map when the UI is
absent and restores the desired state into a new map on the next attachment.

The redesign may break every existing public map API. Prefer the smallest
coherent interface. Do not add compatibility shims.

## Domain model

The following terms have precise meanings in this document.

| Term                   | Definition                                                                                                   |
| ---------------------- | ------------------------------------------------------------------------------------------------------------ |
| Runtime                | The application-scoped owner of shared cache, resource, HTTP, and offline services.                          |
| Logical map            | The durable map represented by one `MapState`, independent of a UI surface.                                  |
| Engine map             | The platform object that performs MapLibre operations: a native FFI map or a GL JS map.                      |
| Presentation           | One temporary connection between a logical map and a UI surface, exposed as `MapPresentation`.               |
| Presentation host      | Platform rendering resources that belong to a window or Compose host, such as a desktop GPU context.         |
| Render lease           | An internal opaque identity for one presentation attachment.                                                 |
| Desired state          | Configuration accepted from the caller that the engine has not necessarily applied yet.                      |
| Applied state          | The latest state that the current engine map reports as applied.                                             |
| Base style             | The MapLibre style document that supplies the initial sources, layers, images, and style properties.         |
| Style composition      | Reusable Compose content that declares application-owned sources, layers, and images on top of a base style. |
| Style evaluator        | The internal Compose host that evaluates one style composition for one consumer.                             |
| Desired style revision | One immutable, complete result from evaluating a style composition.                                          |
| Style reconciliation   | The process that changes an applied style to match one complete desired style revision.                      |
| Style identity         | An internal opaque identity for one loaded base-style generation on one engine map.                          |
| Live style handle      | An imperative source or layer handle that is valid only for one style identity.                              |
| Style consumer         | An attached map presentation or an in-progress snapshot capture that needs a style composition evaluated.    |
| Snapshotter            | A runtime-owned object with its own engine map and no UI attachment API.                                     |
| Owner context          | The platform thread or serialized executor on which an engine map may be accessed.                           |
| Delicate API           | An advanced opt-in API whose platform-specific contract requires extra caller care.                          |

The ownership tree is:

```text
MapRuntime
├── MapState
│   ├── engine map: zero or one
│   └── MapPresentation: zero or one
└── MapSnapshotter
    └── engine map: zero or one

StyleComposition ──evaluated independently──> MapState or MapSnapshotter
ComposeMapPresentationHost ──used only by──> MapPresentation
```

`StyleComposition` and immutable layer or source definitions are values, so an
application can reuse them. Runtime children, presentations, engine maps, and
live handles are owned resources and cannot be shared.

## Public API model

The following sketches show the intended shape. They omit secondary options and
exact result types.

```kotlin
fun createMapRuntime(
  options: MapRuntimeOptions,
): MapRuntime

@Composable
fun rememberMapRuntime(): MapRuntime

@Composable
fun rememberMapState(
  runtime: MapRuntime = rememberMapRuntime(),
  initialCameraPosition: CameraPosition = CameraPosition(),
): MapState

@Composable
fun MaplibreMap(
  state: MapState = rememberMapState(),
  styleComposition: StyleComposition = StyleComposition.Empty,
  modifier: Modifier = Modifier,
)

interface MapRuntime {
  val capabilities: MapRuntimeCapabilities

  fun createMapState(
    initialCameraPosition: CameraPosition = CameraPosition(),
  ): MapState

  fun createSnapshotter(
    baseStyle: BaseStyle,
    styleComposition: StyleComposition = StyleComposition.Empty,
  ): MapSnapshotter

  fun close()
  suspend fun awaitClosed()
}

interface MapState {
  val cameraPosition: CameraPosition
  val style: MapStyleState
  val presentation: MapPresentation?
  val isClosed: Boolean

  fun close()
  suspend fun awaitClosed()
}

interface MapStyleState {
  var baseStyle: BaseStyle
  val loadState: StyleLoadState
}

interface MapPresentation {
  val viewport: Viewport
  val isCameraMoving: Boolean

  fun setCameraPosition(position: CameraPosition)
  suspend fun animateCameraPosition(position: CameraPosition, duration: Duration)
  suspend fun queryRenderedFeatures(...): List<Feature<*, *>>
}

interface MapSnapshotter {
  val style: MapStyleState

  suspend fun capture(request: MapSnapshotRequest): ImageBitmap
  fun close()
  suspend fun awaitClosed()
}
```

`rememberMapRuntime()` returns the process-owned default runtime. It does not
create one runtime per call. Applications create an explicit runtime when they
need custom configuration or deterministic closure. There is no
`LocalMapRuntime`; runtime ownership is explicit in `rememberMapState`.

### Runtime

`MapRuntime` has these responsibilities:

- Store runtime options, including cache, resource, HTTP, and offline
  configuration.
- Create and track `MapState` and `MapSnapshotter` children.
- Reject child creation after closure starts.
- Close every child before releasing shared resources.
- Expose platform capabilities when a common operation is not universally
  available.

`close()` commits logical closure synchronously. New work fails after that call.
Physical cleanup runs in a non-cancellable path. `awaitClosed()` waits until
every child and shared resource has finished cleanup. Closing a child does not
close its runtime.

The default runtime is process-owned and is not closed by its children. An
explicit runtime is application-owned.

Web implements the same runtime type. A common operation that GL JS cannot
support throws `UnsupportedOperationException` until the capabilities API is
available. The first such operations are native offline and cache-management
operations; ordinary map creation remains available.

### Map state

One `MapState` represents exactly one logical map. It contains:

- The durable camera position.
- Desired base-style configuration and observable style-load status.
- The current `MapPresentation?`.
- Logical closure state and `awaitClosed()`.
- Internal ownership of zero or one engine map.

The camera position is durable and read-only on `MapState`. The initial value
comes from state creation. An attached engine updates it after accepted camera
events, including direct sets through `MapPresentation`. The next attachment
uses the last accepted value. Camera movement operations do not belong on
`MapState`.

Native engine-map creation is lazy. Attachment, controlled platform access, or a
future operation that requires the engine can create it. After creation, the
native map remains alive until the state or runtime closes.

Web engine-map creation requires a presentation because a GL JS map requires a
rendering target. Detachment destroys that engine map. `MapState` retains the
desired camera, base style, style revision, and other replayable configuration.

### Presentation

`MapPresentation` represents the current render lease. It contains all
operations and values that require a viewport or rendering surface:

- The viewport and visible region.
- Camera set, fit, and animation operations.
- Camera movement state and movement reason.
- Position-to-screen and screen-to-position projection.
- Rendered-feature queries.
- Gesture configuration and gesture events.
- Presentation-specific render settings.

`MapState.presentation` is null while detached. A presentation object becomes
invalid when its lease ends. Calling an operation on a cached, invalid
presentation fails immediately. It never waits for another attachment and never
targets a later presentation.

Attaching a second `MaplibreMap` to the same `MapState` throws before either
logical state or platform state changes. Two UI maps must use two map states.

### Style composition

`StyleComposition` is a reusable value with a composable builder:

```kotlin
val transitStyle = StyleComposition {
  GeoJsonSource(source)
  LineLayer(...)
  SymbolLayer(...)
}
```

The value stores the composable definition, not a running composition and not a
live map reference. Supplying the same value to two consumers creates two
independent Compose compositions. Each composition has its own `remember` values
and effects. Shared application state must be hoisted and passed into the
definition. Replacing the value on an attached `MaplibreMap` replaces the
evaluator content and publishes a new complete revision.

An evaluator converts the composable definition into a complete immutable
`DesiredStyleRevision`. The revision contains the ordered application-owned
sources, layers, images, and their properties. It does not contain an engine
map, a style binding, or mutable source and layer objects.

A map evaluates its style composition only while it has a presentation. On
detachment, it disposes the evaluator and retains the last desired revision. A
retained native engine map keeps the last applied revision. Reattachment creates
a new evaluator and reconciles its complete first revision before presenting the
result.

A snapshotter evaluates its style composition while a capture needs it. It can
dispose the evaluator after the capture and retain the last desired revision for
the next reconciliation.

Base-style selection is desired configuration on the map or snapshotter, not
part of `StyleComposition`. Changing the base style has this order:

1. Accept the new desired base style and report loading state.
2. Invalidate live handles for the outgoing style identity.
3. Load the new base style.
4. Create a new opaque style identity.
5. Reconcile the complete latest desired style revision.
6. Report the applied style as ready.

Imperative style mutations target the current style identity. They do not
survive a base-style reload. Application content that must survive reloads
belongs in `StyleComposition`.

Layer and source definitions are immutable reusable values. A live style handle
combines a resource ID with one opaque style identity. A stale handle fails
clearly; it does not silently target a replacement style or another map.

### Snapshotter

`MapSnapshotter` owns one non-UI logical map and has no presentation attachment
method. Create it through a runtime with its base style and style composition.

Each capture receives an immutable request that includes:

- Pixel width and height.
- Camera position.
- Density or pixel ratio.
- Layout direction.
- Any output format or transparency options.

Capture configuration is not mutable snapshotter state. Reusing a snapshotter
reuses its engine resources, not the previous request.

A snapshotter serializes concurrent capture requests. Cancellation can remove a
queued request or stop an in-progress request when the platform supports it.
Resource cleanup after a capture starts remains non-cancellable.

Snapshotting never attaches the snapshotter to `MaplibreMap`, and it never uses
the engine map inside a `MapState`. Future same-map capture is a separate
feature. It is acceptable only in texture mode and only if it does not retarget,
pause, or interrupt the presentation.

### Presentation host

A presentation host supplies resources that belong to a Compose window or
rendering host. On desktop this includes the GPU context currently exposed
through `LocalComposeMapHost`. Rename that type and local to
`ComposeMapPresentationHost` and `LocalComposeMapPresentationHost`.

The presentation host does not own cache configuration, offline services, a
logical map, or the application runtime. Replacing a host can replace a
presentation, but it cannot replace or close the runtime or map state.

Platforms that need no public host use an internal default implementation. Do
not add a runtime composition local.

### Platform access

Controlled access to the platform engine is a delicate suspending API:

```kotlin
suspend fun <T> MapState.withPlatformMap(
  block: PlatformMapScope.() -> T,
): T
```

The implementation runs the lambda on the owner context. The platform handle
cannot escape the lambda or remain usable after it returns. Native access can
create the lazy engine map and works while detached. Web access requires a
current presentation because no GL JS map exists while detached.

This escape hatch is lower priority than the common API. Do not expose a raw
handle property.

## Lifecycle and concurrency

One internal lifecycle authority owns engine creation, attachment, detachment,
closure, and event acceptance. A platform adapter performs commands for that
authority; it does not keep a second authoritative attachment state.

The logical states are:

```text
OpenDetached(engine absent or present)
    │
    ├── attach ──> Attaching(lease) ──> Attached(lease)
    │                    │                    │
    │                    └── failure ─────────┤
    │                                         │ detach
    └─────────────────────────────────────────┘

Any open state ── close ──> Closing ── cleanup complete ──> Closed
```

Native detachment returns to `OpenDetached` with the engine present. Web
detachment returns with the engine absent after it records replayable state and
destroys the GL JS map.

Attachment creates a new opaque render lease. Every platform callback,
presentation operation, and detach request identifies its lease. The lifecycle
authority accepts work only for the current lease. A delayed event from a
departed lease has no effect.

The transition to `Closing` is the closure commit point. After it:

- Public operations fail.
- Desired configuration writes fail.
- New attachments fail.
- Platform callbacks are ignored.
- Cleanup continues despite caller cancellation.

All suspending public engine operations can be called from any coroutine
dispatcher. The lifecycle authority marshals them to the owner context. Callers
do not need platform-thread knowledge.

Observable public values use Compose snapshot state. Synchronous setters are
limited to desired configuration and obey normal Compose snapshot rules. A
setter records intent and schedules reconciliation; it does not claim that the
engine has applied the value. Separate status exposes loading or reconciliation
progress.

Use cancellation arbitration only where replacement is valid domain behavior.
Camera animation can use `MutatorMutex`, because a new animation may replace an
old one. Global lifecycle work must not use one cancellation mutex.

## Platform behavior

| Behavior                            | Android, iOS, Desktop                       | Web                                            |
| ----------------------------------- | ------------------------------------------- | ---------------------------------------------- |
| Engine implementation               | MapLibre Native through the FFI             | MapLibre GL JS                                 |
| Engine map while detached           | Retained after lazy creation                | Does not exist                                 |
| Reattachment                        | Attach retained map to a new presentation   | Create map and replay desired state            |
| Platform access while detached      | Available after lazy creation               | Unsupported                                    |
| Style composition                   | Independent evaluator per consumer          | Independent evaluator per consumer             |
| Snapshotter                         | Separate engine map                         | Separate non-UI GL JS map and rendering target |
| Native offline and cache operations | Supported according to runtime capabilities | Throw until a capability-specific API exists   |

Platform lifetime differences are intentional. Public desired-state behavior
must remain consistent.

## Existing implementation entry points

A fresh implementation agent should start with these files:

- `lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/map/MaplibreMap.kt`
  currently combines composition, camera wiring, style wiring, callbacks, and
  presentation creation. Its target responsibility is a thin UI attachment to
  `MapState`.
- `lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/map/MapAdapter.kt`
  currently mixes durable map commands with presentation-only commands. Split
  those responsibilities according to `MapState` and `MapPresentation`.
- `lib/maplibre-compose/src/maplibreNativeMain/kotlin/org/maplibre/compose/map/MlnFfiMapSession.kt`
  and
  `lib/maplibre-compose/src/jsMain/kotlin/org/maplibre/compose/map/GlJsMapSession.kt`
  are the native and Web engine-session implementations. They become platform
  adapters controlled by the lifecycle authority.
- `lib/maplibre-compose/src/commonMain/kotlin/org/maplibre/compose/style/StyleBinding.kt`
  is the broad live-engine interface used by sources and layers. It currently
  combines style liveness, engine operations, and resource lookup. Replace those
  roles with immutable definitions, an internal applied-style port, and
  style-identity-bound live handles.
- `lib/maplibre-compose/src/maplibreNativeMain/kotlin/org/maplibre/compose/mlnffi/MlnFfiRuntimeOptions.kt`
  contains the process-wide native configuration owner. Move its
  application-facing configuration and resource lifetime into `MapRuntime`.
- `lib/maplibre-compose/src/jvmMain/kotlin/org/maplibre/compose/desktop/LocalComposeMapHost.kt`
  contains the window-scoped desktop GPU host. Rename it as the presentation
  host and keep it separate from the runtime.

These are navigation points, not required module boundaries. New internal types
should follow the ownership model in this specification.

## Implementation plan

Each stage must leave the repository buildable and must establish a complete
invariant. Do not merge placeholder APIs that require a later stage for
correctness.

### Stage 1: Separate style definitions from a loaded style

Replace the current mutable binding relationship with three explicit internal
concepts:

1. Immutable layer, source, and image definitions.
2. An internal applied-style port that performs engine operations for one loaded
   style.
3. An opaque style identity that marks the lifetime of that loaded style.

The existing `StyleBinding` name may disappear. The required result is that a
definition contains no live map reference, and liveness belongs to one loaded
style identity. Collapse the current `StyleBinding`, `MlnFfiStyleBinding`, and
native session binding layers into one loaded-style port contract. Native and
Web provide implementations of that contract rather than additional interface
layers.

Acceptance criteria:

- The same definition can be evaluated for two maps without shared mutable
  binding state.
- A base-style reload invalidates every handle from the previous identity.
- A stale handle cannot mutate the next style or another map.
- Existing style-spec parity checks remain green.

### Stage 2: Add runtime ownership and the lifecycle authority

Introduce `MapRuntime`, runtime options, child tracking, and logical closure.
Add one platform-independent lifecycle authority with a fake platform adapter.
Move native process resources behind the runtime.

This stage can keep the existing public map composable, but its internal engine
creation and closure must go through the new authority.

Acceptance criteria:

- One authority decides engine creation, attachment, detachment, and closure.
- Runtime closure closes children before shared resources.
- Child closure does not close the runtime.
- Logical closure rejects new work immediately.
- `awaitClosed()` observes completed physical cleanup.
- Common tests cover every valid and refused lifecycle transition.

### Stage 3: Add render leases and platform retention policies

Give every attachment an opaque render lease. Split platform work into durable
engine-map operations and lease-bound presentation operations. Implement native
retention and Web recreation with desired-state replay.

Acceptance criteria:

- A rival attachment fails before platform state changes.
- Events and detach requests from stale leases have no effect.
- Native reattachment uses the same engine map.
- Web reattachment uses a new GL JS map with restored desired state.
- Detachment makes `MapPresentation` unavailable before later events can
  publish.

### Stage 4: Publish the new map and style-composition API

Publish `MapState`, `rememberMapState`, `MapPresentation`, and
`StyleComposition`. Change `MaplibreMap` into a presentation attachment. Move
camera, query, projection, gesture, and render responsibilities to the owners
specified above. Delete superseded public state types and migrate the library,
tests, demo, and documentation directly.

Add the consumer-driven style evaluator. Its only output is an immutable
complete desired style revision. Reconcile that revision through the lifecycle
authority.

Acceptance criteria:

- One `MapState` represents one logical map and exposes at most one
  presentation.
- Cached presentation operations fail after detachment.
- Desired camera position survives detachment and Web recreation.
- Viewport-dependent work exists only on `MapPresentation`.
- Evaluator disposal does not remove the last applied native style revision.
- Reattachment evaluates current external state and reconciles the complete
  revision.
- No compatibility wrapper preserves the superseded API.

### Stage 5: Add the independent snapshotter

Publish `MapSnapshotter` and immutable capture requests. Give the snapshotter
its own engine map and style evaluator. Reuse `StyleComposition` definitions,
not running compositions or live handles.

Acceptance criteria:

- Repeated captures reuse one snapshotter without retaining request state.
- A map and snapshotter can evaluate the same style composition independently.
- Snapshot capture does not attach, detach, retarget, or pause a `MapState`.
- Concurrent captures follow the documented serialization rule.
- Timeout, cancellation, closure, and runtime closure release resources.
- Native and browser integration tests render a representative composed style.

### Stage 6: Add advanced engine access

Add generation-bound imperative handles, runtime capability reporting, and the
delicate platform-access lambda. Keep each addition in a focused change.

Acceptance criteria:

- Imperative mutations apply only to the current style identity.
- Capability checks distinguish unsupported Web runtime operations.
- Platform access runs on the owner context.
- A platform handle cannot be used after the lambda returns.
- Native detached access and Web attached-only access follow the platform table.

## Test strategy

Common tests use a fake platform adapter that records commands and emits events
with render leases. Test behavior through the lifecycle authority or public API,
not through locks, queues, callback fields, or generation counters.

The common suite must cover:

- Every lifecycle transition and refused transition.
- Rival attachment with no state change.
- Stale event, stale detach, and stale presentation rejection.
- Immediate logical closure and completed physical cleanup.
- Cancellation after each lifecycle commit point.
- Durable desired camera and base-style state.
- Complete style reconciliation and style-identity invalidation.
- Runtime child tracking and closure order.
- Serialized snapshot captures.

Live-map tests verify platform boundaries:

- Native tests prove engine-map identity across detachment and reattachment.
- Browser tests prove GL JS destruction, recreation, and desired-state replay.
- Snapshotter tests render a representative base style plus composed sources and
  layers.
- Desktop tests prove that presentation-host replacement does not replace the
  runtime or logical map.
- Platform-access tests prove owner-context execution and handle confinement.

Place real-engine tests in the existing `liveMapTest`, `jsTest`, and other
platform source sets described in `AGENTS.md`. Keep lifecycle tests in common
code with fakes.

A regression test must fail when its production invariant is deliberately
bypassed. This sensitivity check prevents tests that pass without exercising the
behavior under test.

Every stage runs `mise run check` and the focused tests for its changed source
sets. Style work also runs `mise run style-spec:parity --check`. Before the
redesign is complete, run the full supported matrix:

- `mise run test:android`
- `mise run test:android:device`
- `mise run test:ios`
- `mise run test:desktop`
- `mise run test:js`

Run browser tests under `caffeinate -dimsu` on macOS, as required by
`AGENTS.md`. Record unavailable platform evidence as unavailable; a passing
common test does not substitute for a required live-engine test.

## Out of scope

- Compatibility with superseded map APIs.
- Two simultaneous UI presentations for one `MapState`.
- Waiting for a future presentation when a presentation operation is called
  while detached.
- Continuous style evaluation while a map has no presentation.
- Sharing one running Compose composition between consumers.
- Snapshot capture through `MapState`.
- Retargeting an interactive engine map for a snapshot.
- Persistent imperative mutations across a base-style reload.
- A raw platform handle property.
- Combining the application runtime with a window-scoped presentation host.
- Identical engine-resource lifetimes on native and Web.
- Native offline behavior on GL JS.

## Completion criteria

The redesign is complete when:

- The public API follows the ownership model in this document.
- One lifecycle authority controls every map transition and callback.
- One map state cannot acquire two presentations.
- Native maps survive presentation loss and Web maps replay desired state.
- Style compositions are reusable definitions with independent evaluators.
- Imperative handles are confined to one loaded style identity.
- Snapshotters use independent maps.
- Runtime and presentation-host lifetimes remain separate.
- All common, native, browser, desktop, snapshotter, and static checks pass.
