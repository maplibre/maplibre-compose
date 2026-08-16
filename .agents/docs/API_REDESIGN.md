# API redesign after the FFI unification

Once Android and iOS share desktop's `maplibre-native-ffi` integration, the
public API can stop being the lowest common denominator of four SDKs. This
document is the staging notes for that redesign: the step that
[COMMON_API_GAPS.md](./COMMON_API_GAPS.md) calls step 3. The shapes below are
the ones the current code and the FFI already point at. A prototype will change
them.

## Why this is the moment

Desktop already talks to MapLibre Native through
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi).
Android and iOS still wrap the classic Java and Objective-C SDKs. Those three
native integrations, plus MapLibre GL JS on the web, are why every public type
that touches the map is an `expect` with a fat `actual`.

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

`Source`, `Layer`, `Style`, `GestureOptions`, and `RenderOptions` are `expect`
types. Android's `Layer` wraps `org.maplibre.android.style.layers.Layer`. The
FFI path has no such object: a layer is an id plus style JSON on a `MapHandle`.
`nextCommonMain` already implements `Layer` that way, which is why desktop and
the browser share one class.

`conversions.kt` still copies `LatLng`, `LatLngBounds`, `ScreenPoint`,
`EdgeInsets`, and `CameraOptions` into Compose and spatialk types. Some of those
copies earn their keep. `CameraOptions` is a mutable builder, so
`CameraPosition` as an immutable snapshot is the right public type. spatialk
already owns `Position`, `Geometry`, `Feature`, and `BoundingBox`. A typealias
to `LatLng` would fight that. The copies that should go away are the ones that
exist only because a second SDK used a different name for the same value.

### No way out of the composition

The only public handle on a live map is `CameraState`, and it exposes
projection, queries, and camera animation. Style, images, sources, and the
native map stay internal. Changing a base-style layer's visibility still means
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

### Style wiring that each platform rediscovers

`rememberStyleComposition` hosts content in a subcomposition. `LayerManager`
applies layer inserts in `onEndChanges`. `SourceReferenceEffect` runs later, as
a `DisposableEffect`. A layer therefore names a source that does not exist yet.
The mobile SDKs tolerate that; the C API rejects it. Desktop attaches the source
from the layer first and makes `Source.attach` idempotent.

Switching a style has to mark the outgoing one unloaded before the content
subcomposition applies inserts, or `LayerManager` validates anchors against the
wrong style. Android gets that timing from `AndroidView`'s update block. The
contract is unstated. `SafeStyle` exists to survive the window.

### Snapshots need a style without a surface

`maplibre-native-ffi` can render a map to a CPU image. The style API that would
fill that image lives on `MaplibreMap`'s content lambda. Snapshots stay blocked
on decoupling those two
([#28](https://github.com/maplibre/maplibre-compose/issues/28)).

## What to keep

Declarative style composition is the right way to add sources and layers the
application owns. The pain is the implementation, not the abstraction.

The expression DSL is the right way to write paint and layout values. Compose
types (`Color`, `Dp`, `DpOffset`) and a typed AST beat raw style scalars. That
DSL already compiles to style JSON in `nextCommonMain`, which is what both
backends accept.

## The shape

Three objects, matching the FFI. Compose binds the third; it does not create the
first two.

```
Runtime  →  Map  →  Render session
  cache       style     Compose surface
  HTTP        camera    or still image
  offline     sources
```

A ViewModel, a `remember`, or the process can own a `Runtime` and a `Map`.
`MaplibreMap` receives a `Map` and attaches a session to the composition's
surface. Leaving the composition detaches the session. The map and its style
stay loaded.

The default call site stays a single composable. `MaplibreMap { ... }` creates a
remembered map when the caller does not pass one.

### Runtime

Application-scoped. One per cache and resource-provider configuration. Offline
packs, HTTP header transforms, and resource URL rewrites belong here, because
they outlive any one map.

On FFI platforms this is a thin owner around `RuntimeHandle`: it hops work onto
the owner thread and closes the handle. A typealias to `RuntimeHandle` would
leak that thread. The handle itself is available as an escape hatch.

On GL JS this is a small adapter, or a no-op if the browser map needs no
process-wide owner.

`rememberOfflineManager` becomes a function of a `Runtime` the caller already
holds. The global cache in `MlnFfiOfflineManager` goes away.

### Map

The style, the camera, and the sources. No surface. `close` releases it.

```kotlin
class Map : AutoCloseable {
  var baseStyle: BaseStyle
  var camera: CameraPosition

  val sources: StyleSources
  val layers: StyleLayers

  suspend fun animateCamera(to: CameraPosition, duration: Duration)
  fun queryRenderedFeatures(...): List<Feature<Geometry, JsonObject?>>

  suspend fun snapshot(width: Int, height: Int): ImageBitmap

  @DelicateMapApi
  fun <T> withNative(block: (NativeMap) -> T): T
}
```

`NativeMap` is a typealias to `MapHandle` on FFI platforms and to the GL JS
`Map` on the browser. Common code does not call `withNative`. Platform code that
is blocked on a missing wrapper does.

Thread hopping stays inside `Map`. The `MapHandle` that `withNative` yields is
valid only for the duration of `block`, on the owner thread. That is the same
rule `MlnFfiStyleBinding.readMap` already enforces.

`CameraState` becomes a Compose mirror of `Map.camera`, including saveable state
when the map itself is remembered in composition. Animation and projection
methods move onto `Map`. `CameraProjection` as a separate type that is null
until attach goes away: the map can answer those queries as soon as it exists. A
query that needs a viewport size waits until a session has attached one, or
takes an explicit size for a snapshot.

`StyleState` goes away. `Map.sources` and `Map.layers` are the observable
collections.

### Ownership of style objects

A given id is mutable in one way.

- **Map-owned:** everything in the base style, and everything added through
  `Map.sources` / `Map.layers`. Imperative writes are allowed. The composition
  does not reconcile these ids.
- **Composition-owned:** everything the style content inserted. The composition
  reconciles them. Imperative writes throw.

That is the `List` / `MutableList` split from
[#18](https://github.com/maplibre/maplibre-compose/issues/18). It is also what
makes an escape hatch safe: toggling `map.layers["water"].visible` cannot fight
a `LineLayer("water", ...)` in the content lambda, because one of those two
owners refused the id.

`Anchor.Replace` stays as the declarative way to take over a base-style layer.
An imperative write is the way to change one property of a layer the application
does not want to redeclare.

### Style composition as a helper

The content lambda is a function of a `Map`, not of `MaplibreMap`.

```kotlin
@Composable
fun StyleContent(map: Map, content: @Composable @MaplibreComposable () -> Unit)

@Composable
fun MaplibreMap(
  map: Map = rememberMap(),
  cameraState: CameraState = rememberCameraState(map),
  // ...
  content: @Composable @MaplibreComposable () -> Unit = {},
)
```

`StyleContent` can run against a map that has never been shown. A snapshot then
uses the same layers as the interactive map. The composition still needs a
Compose runtime, so a ViewModel that wants declarative style either exposes
state that a composable applies, or uses the imperative collections. A second,
non-Compose style builder would duplicate the composition we already have.

The applier applies sources before layers. The layer-attaches-its-source
workaround becomes unnecessary. Unloading a style is the common layer's job: the
outgoing binding is marked unloaded, then the new style is published, then
content runs. `SafeStyle` and the unstated adapter contract go away.

`StyleBinding` stays internal. It is the hop to `MapHandle` or to GL JS, plus
the unloaded state. It is not a public type.

### Expressions

No redesign. The DSL compiles to style JSON; both backends set properties from
that JSON. New layer properties are new DSL entries, not new `expect` setters.

### What becomes a typealias, and what does not

**Re-export or typealias** when the FFI type is the public type:

- `MapHandle` / the GL JS `Map`, as `NativeMap`
- `RuntimeHandle`, as the runtime escape hatch
- offline region status and download-state enums, where they already match
- style-spec enums that FFI already names the same way (`SourceType`, layer
  type)

**Keep a Compose or spatialk type** when that type is the better public shape:

- `CameraPosition` — immutable; `CameraOptions` is a builder the next call can
  mutate
- spatialk `Position`, `Geometry`, `Feature`, `BoundingBox`
- the expression AST, and Compose `Color` / `Dp` / `DpOffset` in that AST
- `GestureOptions` as the common data class `nextCommonMain` already has, not as
  an `expect`

**Keep a descriptor class, not a typealias**, for sources and layers. FFI has no
`GeoJsonSource` object. The common `GeoJsonSource` that holds options and talks
JSON to a `Map` is the API. The four `actual` class bodies go away.

**Delete:**

- `MapAdapter` and `MapAdapter.Callbacks`
- `SafeStyle`
- per-platform `actual` `Layer` / `Source` wrappers around the classic SDKs
- `CameraProjection` as a type that exists only after attach

`conversions.kt` shrinks to spatialk and Compose unit boundaries. It does not
disappear.

## Web

`StyleBinding` plus style JSON is the shared language. GL JS already implements
that path in `nextCommonMain`. The public `Map` on the browser is an adapter
over the GL JS map. `withNative` yields that map.

If `maplibre-native-ffi` lands on Kotlin/Wasm, the adapter goes away and the
browser uses the same `Map` as desktop. Until then, features that FFI has and GL
JS does not stay FFI-only, including snapshots.

## Default call site

```kotlin
@Composable
fun Screen() {
  MaplibreMap(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
    val route = rememberGeoJsonSource(data)
    LineLayer(id = "route", source = route, color = const(Color.Blue), width = const(4.dp))
  }
}
```

That still creates a map, attaches a session, and composes style. The difference
is that the map is a remembered object the caller can lift.

```kotlin
class RouteViewModel : ViewModel() {
  val map = runtime.createMap().apply {
    baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    layers["poi-label"]?.visible = false
  }

  override fun onCleared() = map.close()
}

@Composable
fun RouteScreen(vm: RouteViewModel) {
  MaplibreMap(map = vm.map) {
    val route = rememberGeoJsonSource(vm.routeData)
    LineLayer(id = "route", source = route, color = const(Color.Blue))
  }
}
```

A snapshot of the same map does not need a composable:

```kotlin
val image = vm.map.snapshot(width = 800, height = 600)
```

## Sequence

1. Land FFI on Android and iOS
   ([#572](https://github.com/maplibre/maplibre-compose/issues/572)). No public
   API change. Desktop remains the proof.
2. Make the `nextCommonMain` layer and source descriptors the only
   implementation. Delete the classic-SDK `actual` bodies.
3. Split `Map` from the composable internally: the session attaches and
   detaches; the map survives recomposition. Still no public change.
4. Publish `Runtime`, `Map`, `rememberMap`, and `MaplibreMap(map)`. Lift
   `CameraState` onto the map. Delete `StyleState`.
5. Publish `withNative` as a delicate API. Close
   [#538](https://github.com/maplibre/maplibre-compose/issues/538) by pointing
   at it.
6. Run `StyleContent` against a map that has no session. Implement snapshots
   ([#28](https://github.com/maplibre/maplibre-compose/issues/28)).
7. Fill the capabilities in [COMMON_API_GAPS.md](./COMMON_API_GAPS.md). Each is
   one implementation, or two if the browser stays on GL JS.

Steps 2 and 3 can start as soon as Android and iOS compile against the FFI, even
while those platforms are still catching up on features. Step 4 is the break. It
belongs on the road to v1.0, not in a minor release that still supports the
classic SDKs.

## Open questions

**Does `Map` hop threads, or does the caller?** A wrapper that hops is the only
way a ViewModel on the main thread can call `map.camera = ...` without learning
the owner thread. `withNative` is the opt-out. That is the current bias.

**Does style composition run without Compose?** The recommendation above is no:
declarative style needs a composition, and a ViewModel that wants it exposes
state to a composable. A headless `Composition` for snapshots is an
implementation detail of `StyleContent`, not a public API.

**Is `Map` in this artifact, or in a Compose-free one?** A ViewModel on Android
that snapshots a map without pulling in Compose UI would want the latter. The
first cut can live here. A split is easier after `Map` exists than before.

**How much of `CameraState` stays?** Saveable camera state and `isCameraMoving`
are Compose concerns. They can remain as a thin type bound to a `Map`. The map
is the source of truth for the position.

**What does GL JS do for `Runtime`?** If the browser map needs no process-wide
owner, `rememberMap()` never shows one. The type still exists so common code
that opens a runtime on native compiles on JS, even if it is a no-op.

**What is `Map` called?** The sketches use `Map` because that is the FFI object.
In Kotlin it collides with `kotlin.collections.Map`, and next to `MaplibreMap`
it is easy to misread. `MapHandle` is the native type the delicate API already
exposes. Candidates: `MapLibre`, `MapInstance`, `MapController`. The composable
keeps `MaplibreMap`.

## What this document is not

It does not schedule the Android or iOS FFI ports. Those are
[#572](https://github.com/maplibre/maplibre-compose/issues/572).

It does not list the style APIs to add after the redesign. Those are
[COMMON_API_GAPS.md](./COMMON_API_GAPS.md).

It does not propose exposing `MapAdapter`, `MapNode`, or the current style
applier. Those are the wiring this redesign deletes.
