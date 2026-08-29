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
| Engine-map identity    | An internal opaque identity for one platform engine-map instance.                                            |
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
  initialBaseStyle: BaseStyle = BaseStyle.Demo,
): MapState

@Composable
fun MaplibreMap(
  state: MapState = rememberMapState(),
  styleComposition: StyleComposition = StyleComposition.Empty,
  modifier: Modifier = Modifier,
)

interface MapRuntime {
  val capabilities: MapRuntimeCapabilities
  val offlineManager: OfflineManager
  val isClosed: Boolean

  fun createMapState(
    initialCameraPosition: CameraPosition = CameraPosition(),
    initialBaseStyle: BaseStyle = BaseStyle.Demo,
  ): MapState

  fun createSnapshotter(
    baseStyle: BaseStyle,
    styleComposition: StyleComposition = StyleComposition.Empty,
  ): MapSnapshotter

  fun close()
  suspend fun awaitClosed()
}

data class MapRuntimeCapabilities(
  val supportsOfflinePacks: Boolean,
  val supportsAmbientCacheManagement: Boolean,
)

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

  fun source(id: String): SourceHandle?
  fun layer(id: String): LayerHandle?
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

`rememberMapRuntime()` returns one default runtime for the process. It does not
create one runtime per call. `createMapRuntime()` creates a runtime with custom
configuration. Independently configured runtimes can coexist. There is no
`LocalMapRuntime`; `rememberMapState` receives its runtime explicitly.

Every runtime exposes `close()` and `awaitClosed()`. Closing the process default
closes its children and permanently closes that instance. Later
`rememberMapRuntime()` calls return the same closed instance, and new child
creation fails.

### Runtime

`MapRuntime` has these responsibilities:

- Store runtime options, including cache, resource, HTTP, and offline
  configuration.
- Create and track `MapState` and `MapSnapshotter` children.
- Reject child creation after closure starts.
- Close every child before releasing shared resources.
- Expose platform capabilities when a common operation is not universally
  available.

`MapRuntime.close()` commits logical closure synchronously. New work fails after
that call. Physical cleanup runs in a non-cancellable path. `awaitClosed()`
waits until every child and shared resource has finished cleanup. Closing a
child does not close its runtime.

The current process-wide native configuration singleton is an implementation
constraint to remove, not a public invariant to preserve.

Web implements the same runtime type. `MapRuntimeCapabilities` reports
`supportsOfflinePacks` and `supportsAmbientCacheManagement`. Native reports the
features supplied by its runtime. Web reports both as false.

`MapRuntime.offlineManager` owns offline packs and ambient-cache management. Its
pack create, resume, pause, delete, invalidate, and tile-count-limit operations
require `supportsOfflinePacks`. Its ambient-cache invalidate, clear, and
maximum-size operations require `supportsAmbientCacheManagement`. An unsupported
operation throws `UnsupportedOperationException`; Web exposes an empty pack set.
Ordinary Web map and snapshotter creation remains available.

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

`rememberMapState()` owns the state it creates and closes it when that call
leaves composition. A state returned by `MapRuntime.createMapState()` is owned
and closed by its caller. Saveable restoration creates a new logical map and
restores only the camera position. The caller's initial base style is applied to
the new state, and its style composition is evaluated again when presented.

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

`MaplibreMap` publishes a presentation after its platform host and viewport are
usable. It may therefore be attached while its style is still loading. The map
surface remains hidden behind the composable placeholder until the requested
base style has loaded and the first complete style-composition revision has
reconciled. A style failure leaves the presentation attached, publishes the
failure through `MapStyleState.loadState`, and keeps the placeholder visible.

### Map composable inputs

The public `MaplibreMap` surface maps existing inputs to their owners as
follows:

| Existing concern                                                                   | Target owner or input                                                                                     |
| ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Base style                                                                         | `MapState.style.baseStyle`, initialized by `rememberMapState` or `createMapState`                         |
| Camera position                                                                    | Durable value on `MapState`; viewport-bound mutations and observations on `MapPresentation`               |
| Camera padding, constraints, render options, gesture options, and tile-LOD options | One immutable `MapPresentationOptions` value passed to `MaplibreMap` and applied to its current lease     |
| Map click, long-click, and frame callbacks                                         | One `MapPresentationCallbacks` value passed to `MaplibreMap`; layer callbacks remain in style composition |
| Load success or failure                                                            | Observable `MapStyleState.loadState`, not duplicate composable callbacks                                  |
| Logger                                                                             | `MapRuntimeOptions`, not presentation state                                                               |
| Style content                                                                      | `StyleComposition`                                                                                        |
| Overlay and content window insets                                                  | UI-only `MaplibreMap` inputs                                                                              |

Changing presentation options while attached updates only the current render
lease. A later attachment receives the values supplied by that new `MaplibreMap`
call. Presentation options are not durable logical-map state.

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
a new evaluator and reconciles its complete first revision before revealing the
surface.

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

Each accepted base-style change receives an internal monotonically increasing
request identity. Assigning an equal `BaseStyle` is a no-op. A newer request
supersedes an older request at once, and callbacks from the older request cannot
change public state. `MapStyleState.loadState` has these public states:

- `Pending` means the desired base style is recorded but no engine can currently
  load it, as on a detached Web map.
- `Loading` means the current engine is loading the latest desired base style or
  reconciling the first complete desired revision.
- `Ready` means that base style and revision are both applied to the current
  engine and style identity.
- `Failed` identifies whether base-style loading or revision reconciliation
  failed and exposes the failure reason for the latest request.

A failure does not roll desired configuration back to an older style. Handles
for the outgoing style remain invalid, any previous engine content is not
considered current, and the surface remains hidden. Assigning a new base style
or publishing a new revision starts a new request as applicable. Native can
continue a current load while detached. Web returns to `Pending` when its engine
is destroyed and starts a new request on attachment.

Imperative style mutations target the current style identity. They do not
survive a base-style reload. Application content that must survive reloads
belongs in `StyleComposition`.

Layer and source definitions are immutable reusable values. A live style handle
combines a resource ID with one opaque style identity. A stale handle fails
clearly; it does not silently target a replacement style or another map.

The style model maps current behavior to the new seam as follows:

| Current behavior                                                                                       | Target behavior                                                                                                       |
| ------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| Declarative source data and layer properties                                                           | Inputs to immutable definitions; a changed input publishes a complete new desired revision                            |
| Imperative source data, feature state, source queries, cluster queries, and custom-source invalidation | Typed live source handles acquired by ID from `MapStyleState`                                                         |
| Imperative layer property access                                                                       | A typed live layer handle acquired by ID from `MapStyleState`                                                         |
| Sources and layers from the base style                                                                 | Generation-bound handles acquired by ID after that base style is loaded                                               |
| Layer anchors and ordering                                                                             | Immutable definition values in the ordered desired revision                                                           |
| Custom tile providers                                                                                  | Stable callback references in source definitions; replacing a provider publishes a new revision                       |
| Painter-backed images                                                                                  | Resolved by the evaluator into an engine-neutral immutable image payload for the current density and layout direction |

Definition identity is the resource kind plus its public ID. Duplicate IDs of
the same kind in one desired revision fail evaluation. Layer order is the order
recorded in the complete revision. Reconciliation diffs the current and desired
definitions by identity and value. A revision owns defensive copies of mutable
input payloads such as byte arrays and collections; it never retains mutable
source or layer objects, a painter, an engine map, or a live handle.

Live handles are acquired only from a ready `MapStyleState`. Their methods
marshal engine work to the owner context. Persistent changes use declarative
inputs; an imperative mutation is intentionally lost when the style identity
ends.

`SourceHandle` is a sealed family with type-specific handles such as
`GeoJsonSourceHandle`, `VectorSourceHandle`, and custom-source handles.
`MapStyleState.source(id)` returns the actual subtype for either a composed or
base-style source. Callers check that subtype before invoking its supported
operations. `LayerHandle` exposes imperative layer-property access for either a
composed or base-style layer. A missing ID returns null; an ID whose resource
kind or source subtype differs from the caller's expectation fails at the typed
operation rather than being coerced.

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

A snapshotter accepts captures into a FIFO queue. Each request snapshots its
immutable capture values when submitted. When execution begins, the snapshotter
creates or resumes its independent evaluator with that request's density and
layout direction and waits for the evaluator's complete first revision. That
revision observes current external Compose state and becomes the revision for
the capture. A retained prior revision may optimize reconciliation, but it
cannot replace this evaluation. Later desired-style changes do not alter an
active capture.

Cancelling a queued request removes it. Cancelling an active request, including
through a caller timeout, abandons its result and requests platform cancellation
when supported. The snapshotter does not start the next request until the engine
reports that the active request has ended and non-cancellable cleanup is
complete. Closing the snapshotter refuses new captures, cancels queued requests,
abandons the active result, and performs the same cleanup before closure
completes.

Native snapshotters select an offscreen backend from runtime configuration and
internal platform support; they do not borrow a presentation host. A Web
snapshotter owns a private non-visible DOM rendering target for the lifetime of
its engine map and removes it during cleanup.

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

The implementation runs the lambda on the owner context. The scope exposes a raw
platform handle as a borrowed value. Kotlin cannot prevent a caller from
retaining that value, so the delicate contract requires callers to use it only
during the lambda. Native access can create the lazy engine map and works while
detached. Web access requires a current presentation because no GL JS map exists
while detached.

Each invocation binds to an engine-map identity. Native validates that identity
immediately before invoking the callback. Web validates both the engine-map
identity and current render lease. If replacement, detachment on Web, or closure
wins before callback execution, the invocation fails without running the
callback. Once the non-suspending callback starts, lifecycle transitions queue
behind it on the serialized owner context. They continue after it returns. A
long-running callback therefore blocks map progress and violates the delicate
API contract.

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
    │                    │ failure            │ detach
    │                    v                    v
    └────────────── OpenDetached <──── Detaching(lease)

Any open state ── close ──> Closing ── cleanup complete ──> Closed
```

`Attaching`, `Attached`, and `Detaching` all reserve the map against a rival
attachment. A rival fails immediately and never waits for cleanup. Detach or
close invalidates the render lease synchronously before starting physical
cleanup. Attach failure cleans any partial surface and returns to
`OpenDetached`. Detach requested while attaching follows the same invalidation
and cleanup path. No new attachment can begin until detachment cleanup reaches
`OpenDetached`.

Repeated closure is idempotent. A child-close and runtime-close race joins one
cleanup operation. Cleanup attempts every owned resource even after a failure;
`awaitClosed()` completes only after those attempts and reports the collected
cleanup failure.

Native detachment returns to `OpenDetached` with the engine present. Web
detachment returns with the engine absent after it records replayable state and
destroys the GL JS map.

Engine creation assigns an opaque engine-map identity. Native keeps that
identity across detachment. Web creates a new identity with each GL JS map. If a
new native presentation is incompatible with the retained engine's render
backend or scale factor, the lifecycle authority replaces the internal engine,
assigns a new identity, and replays durable desired state before revealing the
surface. This is the only presentation-change exception to native engine
retention. `MapState` remains the same logical map throughout the replacement.
Attachment assigns a separate render lease. The lifecycle authority validates
each event against the identity for the resource that produced it:

| Event family                                                           | Required identity                                        | Acceptance rule                                                                                                                         |
| ---------------------------------------------------------------------- | -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Engine creation and cleanup                                            | Engine-map identity                                      | Accept only the current engine-map instance.                                                                                            |
| Base-style load, failure, and source state                             | Engine-map identity and style request or style identity  | Accept only the current engine and current style request or loaded style. These events can remain valid while a native map is detached. |
| Style-revision reconciliation                                          | Engine-map identity, style identity, and revision number | Accept only the latest claimed revision for the current loaded style.                                                                   |
| Viewport, camera movement, gestures, clicks, frames, and surface state | Engine-map identity and render lease                     | Accept only the current engine and attached presentation.                                                                               |
| Snapshot capture                                                       | Engine-map identity and capture request identity         | Accept only the active request on the snapshotter engine.                                                                               |

Every presentation operation and detach request identifies its render lease. A
delayed presentation event from a departed lease has no effect. Durable native
style events do not require a presentation lease.

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

| Behavior                            | Android, iOS, Desktop                                    | Web                                            |
| ----------------------------------- | -------------------------------------------------------- | ---------------------------------------------- |
| Engine implementation               | MapLibre Native through the FFI                          | MapLibre GL JS                                 |
| Engine map while detached           | Retained after lazy creation                             | Does not exist                                 |
| Reattachment                        | Retain a compatible engine; otherwise replace and replay | Create map and replay desired state            |
| Platform access while detached      | Available after lazy creation                            | Unsupported                                    |
| Style composition                   | Independent evaluator per consumer                       | Independent evaluator per consumer             |
| Snapshotter                         | Separate engine map                                      | Separate non-UI GL JS map and rendering target |
| Native offline and cache operations | Supported according to runtime capabilities              | Report unsupported and throw when called       |

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
  application-facing configuration and resource lifetime into independently
  configurable owned runtimes.
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
- Owned-runtime closure closes children before shared resources.
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
- Presentation events and detach requests from stale leases have no effect.
- Durable engine events use the current engine-map and style identities.
- Native reattachment to a compatible host uses the same engine map.
- An incompatible native host replaces the engine identity and replays durable
  state without replacing `MapState`.
- Web reattachment uses a new GL JS map with restored desired state.
- Detachment makes `MapPresentation` unavailable before later events can
  publish.

### Stage 4: Publish the new map and style-composition API

Expand the public API with `MapState`, `rememberMapState`, `MapPresentation`,
and `StyleComposition`. Change the new `MaplibreMap` path into a presentation
attachment. Keep the old path only while callers migrate in independently green
changes. The final contract change deletes every superseded signature and state
type.

Move camera, query, projection, gesture, and render responsibilities to the
owners specified above. Migrate library callers separately from demos,
documentation, and platform tests. Temporary coexistence is an
expand-migrate-contract technique, not a compatibility requirement. The final
API contains no adapter or shim for the old model.

Add the consumer-driven style evaluator. Its only output is an immutable
complete desired style revision. Reconcile that revision through the lifecycle
authority. Publish the generation-bound source and layer handles needed to
replace existing imperative operations before migrating their callers.

Acceptance criteria:

- One `MapState` represents one logical map and exposes at most one
  presentation.
- Cached presentation operations fail after detachment.
- Desired camera position survives detachment and Web recreation.
- Viewport-dependent work exists only on `MapPresentation`.
- Evaluator disposal does not remove the last applied native style revision.
- Reattachment evaluates current external state and reconciles the complete
  revision.
- Typed imperative handles cover the supported transient source and layer
  operations without restoring mutable definitions.
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

Add runtime capability reporting and the delicate platform-access lambda. Keep
each addition in a focused change.

Acceptance criteria:

- Capability checks distinguish unsupported Web runtime operations.
- Platform access runs on the owner context.
- Platform access is explicitly documented as borrowed and callback-scoped.
- Native detached access and Web attached-only access follow the platform table.

## Test strategy

Tests are executable contracts, not a historical archive. Every ticket reviews
the tests for the behavior that it changes and classifies each test:

- Retain a test that covers a distinct supported public behavior or platform
  boundary.
- Rewrite a test when its behavior remains valid but its API or test seam
  changes.
- Consolidate tests that cover the same contract at several internal or platform
  layers.
- Delete a test that covers a superseded compatibility path, an implementation
  shape that no longer exists, or a state that the new model cannot represent.

An explicitly refused operation remains part of the public contract. Keep tests
for rival attachment, stale presentation use, stale events, and operations after
closure. These scenarios are reachable attempts with defined failures, not
unrepresentable internal states.

Prefer one common test for shared lifecycle semantics. Add a native or browser
test only when it verifies a distinct engine boundary. When a new test replaces
an old test's contract, remove or rewrite the old test in the same change. Each
pull request reports the tests that it added, rewrote, consolidated, and
deleted. Test count is not a completion metric.

Common tests use a fake platform adapter that records commands and emits each
event with the identities required by the event-family table. Test behavior
through the lifecycle authority or public API, not through locks, queues,
callback fields, or generation counters.

The common suite must cover:

- Every lifecycle transition and refused transition.
- Rival attachment with no state change.
- Stale event, stale detach, and stale presentation rejection.
- Durable engine and style events accepted for the current native engine while
  detached, without a render lease.
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
