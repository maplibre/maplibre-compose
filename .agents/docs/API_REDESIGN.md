# API redesign after the FFI unification

Once Android and iOS share desktop's `maplibre-native-ffi` integration, the
public API can stop being the lowest common denominator of four SDKs. This
document is the staging notes for that redesign: the step that
[COMMON_API_GAPS.md](./COMMON_API_GAPS.md) calls step 3. The shapes below are
the ones the current code and the FFI already point at. A prototype will change
them.

## Why this is the moment

Every non-web platform now talks to MapLibre Native through
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi), and
MapLibre GL JS serves the web. The four separate SDK integrations that preceded
this are why every public type that touches the map was an `expect` with a fat
`actual`.

The FFI split that matters for the public API is already in the C API:

- a **runtime** owns the cache, HTTP, and offline work
- a **map** owns the style, the camera, and the sources
- a **render session** attaches that map to a surface, or to a still image

`MlnFfiMapSession` already uses those three handles. The public API still
pretends they are one object that is born when `MaplibreMap` enters composition
and dies when it leaves.

Web may stay on MapLibre GL JS. The redesign has to tolerate a second backend,
and it has to stay useful if that backend later goes away.

## What is wrong

### Facades that exist only to hide four SDKs

`RenderOptions` and `TileLodOptions` are still `expect` types, because the
backends genuinely disagree on their fields. `Layer`, `Source`, and
`GestureOptions` are ordinary common types: a layer or source is an id plus
style JSON, which both MapLibre Native and MapLibre GL JS accept, and the
engine-specific parts live on the internal `StyleBinding` contract since step 2.

`conversions.kt` still copies FFI geometry and camera types into Compose and
spatialk types. Some of those copies earn their keep: spatialk already owns
`Position`, `Geometry`, `Feature`, and `BoundingBox`, and a typealias to
`LatLng` would fight that. A mutable FFI type that exists to assemble a value is
a builder, and if we expose it it is named for the value it builds
(`CameraPositionBuilder`), not `CameraOptions`. The copies that should go away
are the ones that exist only because a second SDK used a different name for the
same value.

### ~~No way out of the composition~~

Fixed in step 4. The public `MapState` is the handle on a live map: the camera,
the queries and projections, and the observable `layers` and `sources`
collections, with imperative writes on map-owned ids
([#18](https://github.com/maplibre/maplibre-compose/issues/18)). The platform
map ([#538](https://github.com/maplibre/maplibre-compose/issues/538)) remains
step 5: exposing `MapHandle` on the FFI platforms, and the GL JS `Map` on the
browser, is one object per backend.

### ~~The map dies with the composable~~

Fixed in step 4. `MapState` owns the map's platform lifetime through
`MapEngine`: on the FFI platforms the loaded map survives the composable leaving
the composition and re-attaches to the next `MaplibreMap`, and on Web the state
replays the selected style into the next session. A ViewModel can construct a
`MapState` and call `close`; `rememberMapState` closes the states it creates.
Snapshots of a map that is not composed shipped in step 6 as
`MapState.snapshot`.

### ~~Style wiring that leans on Compose effect ordering~~

Fixed in step 3. The style composition runs in a state-owned
`StyleCompositionHost`; `StyleNode.applyChanges` syncs desired state in one
stated order, sources before layers; `Source.attach` idempotency survives only
as defense against a mid-switch unload, not as an ordering crutch; and one
persistent composition per map replaces the per-style teardown.

### ~~Snapshots need a style without a surface~~

Fixed in step 6. `MapState.snapshot(width, height, timeout)` renders a detached
state's loaded style, applied content, and recorded camera into a session-owned
texture and reads it back as an `ImageBitmap`
([#28](https://github.com/maplibre/maplibre-compose/issues/28)). The pinned FFI
has no synchronous render-to-completion for a continuous map, so the snapshot
pumps `renderUpdate` on a thread of its own until the map reports itself fully
loaded. On Web the call throws `UnsupportedOperationException`, because MapLibre
GL JS has no still-image API.

## What to keep

Declarative style composition is the right way to add sources and layers the
application owns. The current wiring is the part that has to change.

The expression DSL is the right way to write paint and layout values. Compose
types (`Color`, `Dp`, `DpOffset`) and a typed AST beat raw style scalars. That
DSL already compiles to style JSON in `commonMain`, which is what both backends
accept.

## The shape

Three objects, matching the FFI. Compose binds the third; it does not create the
first two.

```
Runtime  →  Map  →  Render session
  cache       style     Compose surface
  HTTP        camera    or still image
  offline     sources
```

A ViewModel, a `remember`, or the process can own a `Runtime` and a `MapState`.
`MaplibreMap` receives that state and attaches a session to the composition's
surface. Leaving the composition detaches the session. The state and its style
stay loaded.

`MaplibreMap` always takes a `MapState`. `rememberMapState` is how a composable
gets one. The state holds configuration, camera, and style content. The
composable is the session and the overlay: gestures, insets, and Compose UI. Its
trailing lambda is the overlay.

### Execution

[`maplibre-native-ffi#631`](https://github.com/maplibre/maplibre-native-ffi/pull/631)
moves the owner thread into the native core. After that lands, mln-ffi owns the
runtime thread, and every map call is one of:

- a **snapshot**: sync, returns now. Frequent reads that are not keyed on input,
  such as the current camera.
- a **command**: async, no result.
- an **operation**: async, result later.

Which call is which can still change. That PR is in revision, and this redesign
does not wait for it.

The public Kotlin API follows that split: snapshots can be properties or
ordinary functions, and commands and operations are `suspend`. Until #631 lands,
this library still hops onto the owner thread, and that hop is also a `suspend`.
The call sites stay the same either way.

A still image of the map is an operation, not this kind of snapshot. The method
can keep the name `snapshot`; the form is `suspend`.

### Runtime

Application-scoped. One per cache and resource-provider configuration. Offline
packs, HTTP header transforms, and resource URL rewrites belong here, because
they outlive any one map.

Step 4 shipped this as `MaplibreRuntime`, because `Runtime` collides with
`java.lang.Runtime`. The final audit made it an `object`: the process has one
runtime, and the first member access installs the platform default configuration
when none is set. The `OfflineManager` interface is gone — offline work is
runtime work, so its members live on `MaplibreRuntime` directly (`offlinePacks`,
`createOfflinePack`, `resume`, `pause`, `delete`, `invalidate`, and the
ambient-cache controls), delegating to the internal engine.

On FFI platforms this is a thin owner around `RuntimeHandle`. Publishing the
handle as an escape hatch is step-5 work. On GL JS (A in [Web](#web), the
shipped outcome) there is no runtime object. C uses the FFI runtime.

### MapState

The style, the camera, and the sources. No surface. `close` releases it. Compose
names a hoistable object `*State` (`PagerState`, `LazyListState`). The
composable stays `MaplibreMap`. Both being "map" is a collision; renaming the
composable to `MapView` would lean on the Android view system, which Compose
left.

The shipped step-4 surface:

```kotlin
class MapState : AutoCloseable {
  constructor(cameraPosition: CameraPosition = CameraPosition())

  var baseStyle: BaseStyle
  val camera: CameraPosition
  val viewport: Viewport?
  val isCameraMoving: Boolean
  val cameraMoveReason: CameraMoveReason

  val sources: StyleSources
  val layers: StyleLayers
  val styleErrors: SharedFlow<StyleError>

  suspend fun setCamera(position: CameraPosition)
  suspend fun fitCamera(boundingBox: BoundingBox, bearing, tilt, padding)
  suspend fun animateCamera(position: CameraPosition, duration: Duration)
  suspend fun animateCameraToFit(boundingBox: BoundingBox, bearing, tilt, padding, duration)

  fun screenLocationFromPosition(position: Position): DpOffset?
  fun screenOffsetFromPosition(position: Position): Offset?
  fun positionFromScreenLocation(offset: DpOffset): Position?    // and an Offset overload

  suspend fun queryRenderedFeatures(offset: DpOffset, layerIds, predicate): List<Feature<Geometry, JsonObject?>>
  suspend fun queryRenderedFeatures(rect: DpRect, layerIds, predicate): List<Feature<Geometry, JsonObject?>>

  fun setStyleContent(content: @Composable @MaplibreComposable () -> Unit)
  suspend fun snapshot(width: Dp, height: Dp, timeout: Duration = 30.seconds): ImageBitmap
  override fun close()

  // Step 5, an extension in commonMain:
  // @DelicateMapApi suspend fun <T> MapState.withPlatformMap(block: (PlatformMap) -> T): T
}
```

Writes and queries are `suspend`, and unkeyed reads are properties. The
projections stayed sync: they answer from the last rendered viewport and return
null before one exists. The bounding-box camera forms carry their live-viewport
requirement in the name (`fitCamera`, `animateCameraToFit`): both suspend until
a session attaches and fail when the state closes first. The public constructor
takes only a camera position; a detached state rasterizes painters at a density
of 1 and left-to-right layout until a `MaplibreMap` supplies the composition's
values.

`PlatformMap` is an `expect class` with an `actual typealias` to `MapHandle` on
FFI platforms and to the GL JS `Map` on the browser (both dependencies became
`api` / public for it). Common code does not touch it. Platform code that is
blocked on a missing wrapper does. It shipped as a `suspend` lambda hop onto the
map's owner thread, because the handle is confined there today; once
[#631](https://github.com/maplibre/maplibre-native-ffi/pull/631) puts the
runtime thread in native, a property can replace the lambda.

Camera lives on `MapState`. `rememberMapState` takes a first position and saves
it across recreation. `CameraState` is gone entirely: the saveable rides
`CameraPosition`, and a recreated composition constructs the state at the
restored position — the piecemeal-saveable escape clause above held. Overlay
controls read `state.camera`. `CameraProjection` as a type that is null until
attach went away: the state answers those queries as soon as a session has
rendered a viewport.

`StyleState` went away. `MapState.sources` and `MapState.layers` are the
observable collections.

### Ownership of style objects

A given id is mutable in one way.

- **Map-owned:** everything in the base style, and everything added through
  `Map.sources` / `Map.layers`. Imperative writes are allowed. The composition
  does not reconcile these ids.
- **Composition-owned:** everything the style content inserted. The composition
  reconciles them. Imperative writes throw.

That is the `List` / `MutableList` split from
[#18](https://github.com/maplibre/maplibre-compose/issues/18). It is also what
makes an escape hatch safe: toggling `state.layers["water"].visible` cannot
fight a `LineLayer("water", ...)` in the content lambda, because one of those
two owners refused the id.

`Anchor.Replace` stays as the declarative way to take over a base-style layer.
An imperative write is the way to change one property of a layer the application
does not want to redeclare.

### Style composition

The map starts the style composition, the same way a window starts a UI
composition.

```kotlin
state.setStyleContent {
  val route = rememberGeoJsonSource(data)
  LineLayer(id = "route", source = route, color = const(Color.Blue))
}
```

`setStyleContent` owns one Compose `Composition` and a recomposer. It does not
have to be called from a UI composable. Snapshot state that the content reads
invalidates it.

A map has one style composition. A second `setStyleContent` replaces the content
of that composition, the same way a second `setContent` replaces a window's UI
tree. The outgoing content leaves the style; the incoming content is what
remains. Two trees are not inserted side by side. That would be two appliers on
one style, which is the wiring this redesign deletes.

The shipped entry points:

```kotlin
@Composable
fun rememberMapState(
  cameraPosition: CameraPosition = CameraPosition(),
  baseStyle: BaseStyle = BaseStyle.Demo,
  styleContent: (@Composable @MaplibreComposable () -> Unit)? = null,
): MapState

@Composable
fun MaplibreMap(
  state: MapState,
  modifier: Modifier = Modifier,
  // camera constraints: cameraPadding, zoomRange, pitchRange, boundingBox
  // callbacks: onMapClick, onMapLongClick, onFrame, onMapLoadFailed, onMapLoadFinished
  options: MapOptions = MapOptions(),  // gesture, render, and tile LOD options
  logger: Logger? = ...,
  contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
  overlay: @Composable MapOverlayScope.() -> Unit = { include(MapOverlay.Default) },
)
```

`rememberMapState` constructs the state. A non-null `styleContent` calls
`setStyleContent`. `MaplibreMap` does not take a base style, a camera, or style
content. It attaches a session, applies per-session options such as gestures and
camera constraints, and draws the overlay.
`MaplibreMap(state) { CompassButton() }` is UI on state that already has its
style. `MapState` and `setStyleContent` use the Compose runtime; `MaplibreMap`,
overlays, and gestures are Compose UI — one module now, with that line drawn for
a later split.

The applier applies sources before layers. The layer-attaches-its-source
workaround becomes unnecessary. Unloading a style is the common layer's job: the
outgoing binding is marked unloaded, then the new style is published, then
content runs. `SafeStyle` is already gone; the unstated adapter contract goes
away here.

`StyleBinding` stays internal. It is the hop to the platform map, plus the
unloaded state. It is not a public type.

A prototype (2026-08) verified the detached style composition and settled four
mechanics:

- The host owns a `Recomposer`, a `BroadcastFrameClock` pumped on demand, and a
  `Snapshot.registerGlobalWriteObserver` stand-in for the UI's global snapshot
  manager, all on a dedicated single-threaded dispatcher.
- The content reads `LocalDensity` and `LocalLayoutDirection`, so `MapState`
  must supply a density and layout direction — it cannot inherit them from a UI
  tree it may not have.
- The generation-keyed `SideEffect` goes away: the host calls `applyChanges`
  after `setContent` and after each frame, which preserves the source-to-layer
  order explicitly and halves the frame cost of a structural change.
- The blocking owner-thread hop is acceptable from the host thread.
  `setStyleContent` marshals the initial composition onto the host dispatcher,
  and an owner-thread callback never blocks on the composition.
  [#631](https://github.com/maplibre/maplibre-native-ffi/pull/631) improves
  this; nothing waits on it.

The prototype also found a name collision: each internal layer descriptor class
had the name of its public layer composable, so
`RasterLayer(id = ..., source = ...)` in a friend module or a test resolved to
the class constructor and did nothing. Every descriptor class now has a
`Descriptor` suffix, such as `RasterLayerDescriptor`.

### Expressions

No redesign. The DSL compiles to style JSON; both backends set properties from
that JSON. New layer properties are new DSL entries, not new `expect` setters.

### Facades

When an FFI type is the public type, re-export or typealias it. When Compose or
spatialk already has the better shape, keep that. Sources and layers stay as
descriptor classes: FFI has no objects for them. A mutable FFI type that exists
to assemble a value is a builder, named for the value it builds. Which types
fall where is decided against the FFI surface we ship, not listed here.

Types that exist only to hide four SDKs go away with those SDKs: `MapAdapter`
and `CameraProjection` as a type that exists only after attach. `SafeStyle` and
the per-platform `actual` `Layer` / `Source` wrappers are already gone.

## Web

MapLibre GL JS 6.2.0 has no Runtime / Map / RenderSession split. The public
`maplibregl.Map` fuses all three: it requires a `container` and creates a WebGL2
context in `_setupPainter`. `remove()` destroys the painter, the style, and the
context. Live `Style` is `constructor(map)`.

What looks like a runtime is scattered: a process-wide `WorkerPool`
(`setWorkerUrl`, `setWorkerCount`, `prewarm`, `addProtocol`) plus per-map
`RequestManager` / `transformRequest` and tile-cache sizes. There is no
offline-pack API and no still-image API.

The web `MapState` was a choice among three options:

- **A.** A Kotlin holder for style, camera, and content. The live
  `maplibregl.Map` exists only while `MaplibreMap` is in the composition.
- **B.** The `MapState` constructor is per backend. `rememberMapState` is
  `expect` / `actual` so the web actual can run in the composition and take the
  WebGL context that `maplibregl.Map` needs at construct time. The live map is
  created against that context and disposed with the remember.
- **C.** The browser uses mln-ffi's Kotlin/Wasm Emscripten build. `MapState` is
  the same object as desktop. That build is in progress and queued behind the
  executor overhaul
  ([#631](https://github.com/maplibre/maplibre-native-ffi/pull/631)).

Step 4 shipped A: `GlJsMapEngine` reports `retainsStyleAcrossDetach = false`,
the live `maplibregl.Map` exists only while a `MaplibreMap` is composed, and the
state replays the selected style into the next session. C remains the possible
future once the mln-ffi Kotlin/Wasm build lands.

## Default call site

```kotlin
@Composable
fun Screen() {
  val state =
    rememberMapState(
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
      styleContent = {
        val route = rememberGeoJsonSource(data)
        LineLayer(id = "route", source = route, color = const(Color.Blue), width = const(4.dp))
      },
    )
  MaplibreMap(state) { CompassButton() }
}
```

`rememberMapState` creates the state and, when given `styleContent`, calls
`setStyleContent`. `MaplibreMap` attaches the session and draws the overlay.

```kotlin
class RouteViewModel : ViewModel() {
  val mapState = MapState().apply {
    baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    layers["poi-label"]?.visible = false
    setStyleContent {
      val route = rememberGeoJsonSource(routeData)
      LineLayer(id = "route", source = route, color = const(Color.Blue))
    }
  }

  override fun onCleared() = mapState.close()
}

@Composable
fun RouteScreen(vm: RouteViewModel) {
  MaplibreMap(vm.mapState) { CompassButton() }
}
```

A still image of the same map does not need a composable:

```kotlin
val image = vm.mapState.snapshot(width = 800.dp, height = 600.dp)
```

## Sequence

1. ~~Land FFI on Android and iOS
   ([#572](https://github.com/maplibre/maplibre-compose/issues/572)). No public
   API change. Desktop remains the proof.~~ Done in v0.14.
2. ~~Make the JSON-shaped layer and source descriptors the only
   implementation.~~ Done: layer and source descriptors are common classes over
   the internal `StyleBinding` contract.
3. ~~Split `MapState` from the composable internally: the session attaches and
   detaches; the state survives recomposition. Still no public change.~~ Done,
   beyond the promise: the style mutation stack collapsed onto `StyleBinding`,
   the style composition runs in a state-owned host, the apply machinery is a
   desired-state sync with one persistent composition per map, an internal
   `MapState` owns the callbacks and the attach/detach seam, `MlnFfiMapSession`
   split into the hoistable `MlnFfiMapCore` and a composition-scoped render
   session, and one application-scoped `MlnFfiRuntime` owns the offline thread's
   work.
4. ~~Publish `Runtime`, `MapState`, `rememberMapState`, and
   `MaplibreMap(state)`. Lift camera onto the state. Delete `StyleState` and
   `OfflineManager`.~~ Done, with one correction: the runtime shipped as
   `MaplibreRuntime`, because `Runtime` collides with `java.lang.Runtime`. The
   `OfflineManager` interface is deleted and its members live on
   `MaplibreRuntime`.
5. ~~Publish `platform` as a delicate API. Close
   [#538](https://github.com/maplibre/maplibre-compose/issues/538) by pointing
   at it.~~ Done, as `withPlatformMap` rather than a property: the native handle
   is confined to the map's owner thread until
   [#631](https://github.com/maplibre/maplibre-native-ffi/pull/631), so the
   escape hatch is a scoped `suspend` hop. The test-pinned core passthroughs
   (`readMap`, `styleImageInfo`, `currentStyleLayerIds`, `imageStretches`) are
   deleted; tests go through the escape hatch or the fixture's `withMap`.
6. ~~Implement still images
   ([#28](https://github.com/maplibre/maplibre-compose/issues/28)). The other
   half of this step — `setStyleContent` on a map that has no session — shipped
   in step 4 with tests.~~ Done: `MapState.snapshot` renders a detached state
   over a session-owned FFI texture; a state with an attached `MaplibreMap`
   refuses the call, because the map has one live render session.
7. Fill the capabilities in [COMMON_API_GAPS.md](./COMMON_API_GAPS.md). Each is
   one implementation, or two if the browser stays on GL JS.

[#631](https://github.com/maplibre/maplibre-native-ffi/pull/631) can land
anywhere in this sequence. The public API is `suspend` before it and after it.

## Deferred into step 4

The step-3 close-out audit and the external review of the stack raised these;
each was deferred deliberately, because step 4 restructures the code it lives
in.

Landed in step 4:

- ~~**`MapState` owns style selection.** `MaplibreMap` still passes the base
  style to the view, whose only use is a `SideEffect` calling `setBaseStyle`.~~
  Done, with one correction: the push lives in the `MapState.baseStyle` setter
  and in `attachSession`, not in `applyOptions`, and the `style` parameter is
  gone from the `ComposableMapView` expect and its actuals.
- ~~**An error channel for style failures.** The host logs ordinary composition
  and apply failures and rethrows only fatal errors; an application cannot
  observe them.~~ Done: `MapState.styleErrors` is the observable path, and the
  log remains the always-on record.
- ~~**Hoisting prerequisites the `MlnFfiMapCore` split left.**~~ Done: the state
  owns the engine and its platform lifetime, the view attaches and detaches
  render sessions on a core that outlives them, and input still binds through
  the session.
- ~~**Documentation debts.** Style content composes on a dedicated style
  dispatcher, so effects inside it leave the UI context; GeoJSON and anchor
  validation surface at attach and apply rather than at construction and
  insert.~~ Done: both are in the step-4 KDoc and on the site's composition
  page, along with `styleErrors`.

Still deferred:

- **Shared adapter mechanics.** `MlnFfiMapCore` and `GlJsMapSession` duplicate
  the pending-action queue, the requested-style and requested-camera fallbacks,
  and stranded-transition resume. Extract with a gate predicate; the native side
  is lock-guarded and the JS side single-threaded, so the shared type must be
  thread-agnostic.
- **One owner-thread loop.** `MlnFfiRuntime` and `MlnFfiMapRuntimeLoop`
  duplicate the task deque, accept gate, wake source, and teardown. A shared
  base is the most delicate concurrency change in the module; take it only with
  the existing loop tests as proof.
- **A common session-host composable.** `MlnFfiMapView` and `JsMapView` share
  the session remember and effect wiring; the native side adds the load
  placeholder and pre-viewport gesture suppression.
- **`LayerNode`'s `isLoaded` gate.** The binding drops writes after unload and
  the descriptor buffers properties, so the gate only delays; removing it
  changes when click handlers become visible. Test with `MlnFfiStyleSwitchTest`
  and `LayerClickOrderTest`.
- **One base-style snapshot.** `StyleNode` and `SourceManager` memoize the base
  layer ids and base sources separately on the same binding identity. The
  snapshot must stay lazy: `getBaseSource` runs before the first sync.

## Open questions

**Which members are `suspend`?** Settled for the shipped surface: the sketch
above is the list. `snapshot` shipped in step 6 as a `suspend` operation, and
step 5 shipped `withPlatformMap` as one.

~~**Which web `MapState`?** A, B, or C in [Web](#web). A or B before the mln-ffi
Kotlin/Wasm build; C after.~~ Answered in step 4: A shipped, and C remains the
possible future.
