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

### No way out of the composition

The only public handle on a live map is `CameraState`, and it exposes
projection, queries, and camera animation. Style, images, sources, and the
platform map stay internal. Changing a base-style layer's visibility still means
`Anchor.Replace` and a hand-copied layer, or editing the style JSON before load
([#18](https://github.com/maplibre/maplibre-compose/issues/18)). Reaching a
missing API means asking us to wrap it, or asking for the platform map
([#538](https://github.com/maplibre/maplibre-compose/issues/538)).

Exposing `MapAdapter` would publish four different objects. Exposing `MapHandle`
on the FFI platforms, and the GL JS `Map` on the browser, is one object per
backend.

### The map dies with the composable

`ComposableMapView` creates the platform map. `CameraState.map` is set from the
view's update block and cleared in `onReset`. `rememberStyleComposition` is a
`LaunchedEffect` on the loaded style, so the content tree is torn down when the
composable leaves the tree.

A ViewModel can hold `CameraState` and `StyleState`. It cannot hold the map,
keep a style loaded off-screen, or snapshot a map that is not currently
composed. The offline manager already ran into this: composition-scoped disposal
interrupts downloads, so the FFI integration caches runtimes for the process
lifetime instead. That cache is a workaround for a missing application-scoped
owner.

### Style wiring that leans on Compose effect ordering

`rememberStyleComposition` hosts content in a subcomposition that inherits the
UI recomposer. Sources attach from `DisposableEffect`s; layer inserts apply from
a generation-keyed `SideEffect`, which Compose runs after remembered observers
in the same apply pass. That ordering is correct, and nothing states it: it
holds because of Compose effect-ordering trivia, and `Source.attach` stays
idempotent as defense.

Switching a style has to mark the outgoing one unloaded before the content
subcomposition applies inserts, or `LayerManager` validates anchors against the
wrong style. Each engine session unloads its outgoing `StyleBinding` before
reporting the switch, and the binding drops writes after unload; the timing
contract between session and subcomposition is still unstated.

### Snapshots need a style without a surface

`maplibre-native-ffi` can render a map to a CPU image. The style API that would
fill that image lives on `MaplibreMap`'s content lambda. Snapshots stay blocked
on decoupling those two
([#28](https://github.com/maplibre/maplibre-compose/issues/28)).

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

`OfflineManager` as a remembered type goes away. Offline work is runtime work.
If the offline API wants its own namespace it can be a child of the runtime; it
is not a separate owner, and `rememberOfflineManager` is not how it is acquired.

On FFI platforms this is a thin owner around `RuntimeHandle`. The handle itself
is available as an escape hatch. On GL JS (A or B in [Web](#web)) there is no
runtime object. C uses the FFI runtime.

### MapState

The style, the camera, and the sources. No surface. `close` releases it. Compose
names a hoistable object `*State` (`PagerState`, `LazyListState`). The
composable stays `MaplibreMap`. Both being "map" is a collision; renaming the
composable to `MapView` would lean on the Android view system, which Compose
left.

```kotlin
class MapState : AutoCloseable {
  var baseStyle: BaseStyle
  val camera: CameraPosition

  val sources: StyleSources
  val layers: StyleLayers

  suspend fun setCamera(to: CameraPosition)
  suspend fun animateCamera(to: CameraPosition, duration: Duration)
  suspend fun queryRenderedFeatures(...): List<Feature<Geometry, JsonObject?>>

  suspend fun snapshot(width: Int, height: Int): ImageBitmap

  fun setStyleContent(content: @Composable @MaplibreComposable () -> Unit)

  @DelicateMapApi
  val platform: PlatformMap
}
```

The sketch marks writes and queries `suspend` and leaves unkeyed reads as
properties. Exactly which members are `suspend` follows the FFI form of each
call, and that assignment is still moving.

`PlatformMap` is a typealias to `MapHandle` on FFI platforms and to the GL JS
`Map` on the browser. Common code does not touch `platform`. Platform code that
is blocked on a missing wrapper does. It is a property, not a lambda:
[#631](https://github.com/maplibre/maplibre-native-ffi/pull/631) puts the
runtime thread in native, so the handle is not confined to a hop this library
owns.

Camera lives on `MapState`. `rememberMapState` can take a first position and
save it across recreation. Animation and projection methods live on `MapState`.
`CameraState` goes away unless saving `MapState` proves hard and saveables need
to come out piecemeal, or the camera wants its own namespace. Overlay controls
read `state.camera`. `CameraProjection` as a type that is null until attach goes
away: the state can answer those queries as soon as it exists. A query that
needs a viewport size waits until a session has attached one, or takes an
explicit size for a snapshot.

`StyleState` goes away. `MapState.sources` and `MapState.layers` are the
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

```kotlin
@Composable
fun rememberMapState(
  baseStyle: BaseStyle = BaseStyle.Demo,
  firstCamera: CameraPosition = CameraPosition(),
  styleContent: (@Composable @MaplibreComposable () -> Unit)? = null,
): MapState

@Composable
fun MaplibreMap(
  state: MapState,
  modifier: Modifier = Modifier,
  gestureOptions: GestureOptions = GestureOptions.Standard,
  overlay: @Composable MapOverlayScope.() -> Unit = { DefaultOverlay() },
)
```

`rememberMapState` constructs the state. A non-null `styleContent` calls
`setStyleContent`. `MaplibreMap` does not take a base style, a camera, or style
content. It attaches a session, applies gestures, and draws the overlay.
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

The web `MapState` is a later choice:

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

A or B if this redesign ships before that Wasm build. C if it ships after. A and
B need no GL JS change. A live `maplibregl.Map` that outlives the context that
constructed it is outside A and B.

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
  val mapState = runtime.createMapState().apply {
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
val image = vm.mapState.snapshot(width = 800, height = 600)
```

## Sequence

1. ~~Land FFI on Android and iOS
   ([#572](https://github.com/maplibre/maplibre-compose/issues/572)). No public
   API change. Desktop remains the proof.~~ Done in v0.14.
2. ~~Make the JSON-shaped layer and source descriptors the only
   implementation.~~ Done: layer and source descriptors are common classes over
   the internal `StyleBinding` contract.
3. Split `MapState` from the composable internally: the session attaches and
   detaches; the state survives recomposition. Still no public change.
4. Publish `Runtime`, `MapState`, `rememberMapState`, and `MaplibreMap(state)`.
   Lift camera onto the state. Delete `StyleState` and `OfflineManager`.
5. Publish `platform` as a delicate API. Close
   [#538](https://github.com/maplibre/maplibre-compose/issues/538) by pointing
   at it.
6. Let `setStyleContent` run on a map that has no session. Implement still
   images ([#28](https://github.com/maplibre/maplibre-compose/issues/28)).
7. Fill the capabilities in [COMMON_API_GAPS.md](./COMMON_API_GAPS.md). Each is
   one implementation, or two if the browser stays on GL JS.

[#631](https://github.com/maplibre/maplibre-native-ffi/pull/631) can land
anywhere in this sequence. The public API is `suspend` before it and after it.

## Open questions

**Which members are `suspend`?** Snapshots stay sync. Commands and operations do
not. The FFI assignment of each call is still moving, so the sketch above is a
bias, not a list.

**Which web `MapState`?** A, B, or C in [Web](#web). A or B before the mln-ffi
Kotlin/Wasm build; C after.
