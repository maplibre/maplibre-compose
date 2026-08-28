# Common API gaps

Capabilities that maplibre-native-ffi provides and MapLibre Compose has no
cross-platform API for. This is a staging document: a place to write findings
down while they are fresh, to be converted into issues rather than lived in.

The API redesign shipped: `MapState` and `MaplibreMap(state)` are the public
surface, `MaplibreRuntime` owns the process-scoped work, and every non-web
platform runs on maplibre-native-ffi while the browser runs on MapLibre GL JS.
This document is the remaining capability work.

**What the FFI can do is the target surface.** Each capability is one
implementation on the FFI engine and one on the GL JS engine, and where GL JS
has no equivalent, the web declines the call the way
`MapState.captureStillImage` does.

FFI names below are verified against maplibre-native-ffi 0.202608.3, and GL JS
names against the maplibre-gl 6.2.0 type declarations.

## Imperative style mutation

`LayerHandle.setFilter`, `StyleSources.add`/`remove`, and `MapState.images`
shipped. One gap remains.

**Imperative layer add.** `MapState.layers` reads and mutates the layers the
style already has; it inserts and removes nothing. A typed layer-insert API and
a placement contract against the live draw order (options b and c) were refused.
A future `addFromStyleJson` waits for an evidenced user with a need the style
content's `Anchor` placement cannot serve.

- FFI: `MapHandle.addStyleLayerJson` and `removeStyleLayer` — the calls
  `StyleBinding` already wraps.
- GL JS: `Map.addLayer` and `removeLayer` — likewise.

## Unified event surface

One stream of map events on `MapState`, shaped after the FFI's runtime event
stream. Today each event the library consumes has a bespoke path — `styleErrors`
is a flow, camera transitions resume waiters, the idle and load states are
internal — and `MlnFfiMapCore.handleEvent` drops the events the library does not
consume.

```kotlin
// on MapState
public val events: SharedFlow<MapEvent>

public sealed interface MapEvent {
  public data object StyleLoaded : MapEvent
  public data object MapIdle : MapEvent
  public data class SourceLoaded(val sourceId: String) : MapEvent
  // grown member by member as events prove useful, not transliterated at once
}
```

`styleErrors` folds into the stream as `MapEvent.StyleError`; a second error
channel has no reason to exist. A detached state buffers events with a bounded
replay rather than dropping them, because a ViewModel-owned state's collector
usually subscribes after the events it needs. The FFI event enum is the
candidate vocabulary; each member ships with a GL JS mapping.

The missing-image case folds in here too, with no bespoke resolver: the stream
carries `MapEvent.StyleImageMissing(imageId)`, and the response channel is the
existing `MapState.images` upsert, which both engines accept during the event.

- FFI: the `RuntimeEventType` stream `MlnFfiMapCore.handleEvent` already
  consumes — every branch the core drops today is a candidate member.
- GL JS: `Map.on(type, listener)` per mapped event (`"load"`, `"idle"`,
  `"sourcedata"`, …). GL JS event names and FFI event types do not align one to
  one, so each `MapEvent` member declares its own mapping.

## Attached still capture

`MapState.captureStillImage` fails while a `MaplibreMap` is attached, because
the capture pump owns the engine's render slot. An attached capture would
instead read the pixels of a frame the live session presented.

This is an investigation, not yet a proposal. The render session already exposes
the readback the capture pump uses. The open questions are sequencing the
readback against the session's frame loop without a stall, and whether the
result is limited to the on-screen size. Render-to-completion for a continuous
map, if the FFI grows it, supersedes this path.

- FFI: the render session's `readPremultipliedRgba8`, called after a presented
  frame.
- GL JS: `Map.getCanvas()` read on the `"render"` event, or a map created with
  `preserveDrawingBuffer` — MapLibre's default canvas discards the buffer after
  present.

## Style light

Position, color, intensity, and anchor of the style's light source, which fill
extrusions shade against.

The style has one light, so it is declared state on `MapState` — like
`baseStyle` and the camera, it reapplies on every style load — rather than a
composable whose singleton rule would need enforcing:

```kotlin
public data class Light(
  val anchor: Expression<EnumValue<IlluminationAnchor>> = nil(),
  val position: Expression<VectorValue<Number>> = nil(),
  val color: Expression<ColorValue> = nil(),
  val intensity: Expression<FloatValue> = nil(),
)

// on MapState; null keeps the style's own light
public var light: Light?
```

The properties are expression DSL values compiled to light JSON in `commonMain`;
`IlluminationAnchor` already exists in the expression values for
`HillshadeLayer`.

- FFI: `MapHandle.setStyleLightJson(json)` applies the compiled object;
  `MapHandle.setStyleLightProperty(name, json)` and
  `MapHandle.styleLightProperty(name)` are the per-property forms.
- GL JS: `Map.setLight(light)` and `Map.getLight()`, taking the same
  specification object the compiled JSON deserializes to.

## Projection mode

The pinned FFI's projection mode is the axonometric projection:
`ProjectionModeOptions` carries `axonometric`, `xSkew`, and `ySkew`. It has no
globe. GL JS has the opposite: `Style.setProjection` and `Style.getProjection`
take a `ProjectionSpecification` whose type is `mercator`, `globe`, or
`vertical-perspective`, and GL JS has no axonometric projection. A globe API
waits for the FFI to expose globe; an axonometric API is native-only. Neither
half is worth a common API until one side closes the gap, so this entry stays a
watch item.

- FFI: `MapHandle.setProjectionMode(options)` and
  `MapHandle.getProjectionMode()`.
- GL JS: `Style.setProjection(projection)` and `Style.getProjection()`.

## Style transition options

The style's global transition duration and delay, and whether symbol placement
cross-fades. Every paint property's animation takes its default from this, so it
is the one setting that changes how the whole map feels when data updates.

Map-owned state on `MapState`, following the snapshot-read, suspend-write
convention:

```kotlin
public data class StyleTransitionOptions(
  val duration: Duration? = null,
  val delay: Duration? = null,
  val enablePlacementTransitions: Boolean? = null,
)

// on MapState
public val styleTransitionOptions: StyleTransitionOptions
public suspend fun setStyleTransitionOptions(options: StyleTransitionOptions)
```

- FFI: `MapHandle.setStyleTransitionOptions(options)` and
  `MapHandle.styleTransitionOptions()`; the FFI type carries `durationMs`,
  `delayMs`, and `enablePlacementTransitions`.
- GL JS: `Style.getTransition()` reads the value, but GL JS has no runtime
  setter — the style JSON's `transition` property is the only input, so the web
  declines the write.

## HTTP header transforms

A hook to add or rewrite request headers for every resource the map fetches —
the usual home for an `Authorization` header or an API key that does not belong
in a URL.

Runtime-scoped, because it outlives any one map. `MaplibreRuntime` is a
native-only object today, so this entry either moves it into `commonMain` or
adds a common facade for the members both engines implement:

```kotlin
// on MaplibreRuntime
public fun setHttpHeaderTransform(transform: ((url: String, headers: Map<String, String>) -> Map<String, String>)?)
```

Design this together with the resource transform below: one rewrites the URL,
the other the headers, and an application adding credentials needs whichever the
server expects. The FFI reports the header transform as unsupported on
OpenHarmony, whose HTTP client cannot intercept redirects, so the common API has
to tolerate a platform declining it.

- FFI: `RuntimeHandle.setHttpHeaderTransform(callback)` and
  `RuntimeHandle.clearHttpHeaderTransform()`; the callback maps an
  `HttpHeaderTransformRequest` to a `List<HttpHeader>`.
- GL JS: `Map.setTransformRequest(fn)`, whose function returns
  `RequestParameters` with `headers` and `credentials`. The hook is per map, so
  the web engine installs the runtime's transform on each map it creates.

## Resource transform

A hook to rewrite every resource URL before it is requested — how applications
add API keys, route through a proxy, or redirect to a local mirror.

Runtime-scoped, beside the header transform above:

```kotlin
// on MaplibreRuntime
public fun setResourceTransform(transform: ((url: String) -> String)?)
```

The FFI engine already has a broader mechanism in `MlnFfiResourceProvider`,
which serves resources rather than only rewriting their URLs, and the FFI
exposes both (`setResourceProvider` / `clearResourceProvider`). Decide which of
the two is the public API rather than shipping both.

- FFI: `RuntimeHandle.setResourceTransform(callback)` and
  `RuntimeHandle.clearResourceTransform()`; the callback maps a
  `ResourceTransformRequest` to a URL string.
- GL JS: the same `Map.setTransformRequest(fn)` hook as the header transform —
  `RequestParameters.url` is the rewritten URL.

## Offline database merge

Merging a side-loaded offline database into the running one, which is how an
application ships pre-downloaded regions rather than making every user download
them.

One suspending member on `MaplibreRuntime`, beside `createOfflinePack`:

```kotlin
// on MaplibreRuntime
public suspend fun mergeOfflineDatabase(path: String): List<OfflinePack>
```

The result lists the packs the merge added, which also land in `offlinePacks`.

- FFI: `RuntimeHandle.startMergeOfflineRegionsDatabase(path)` returns an
  `OfflineOperationHandle`, and
  `RuntimeHandle.takeMergeOfflineRegionsDatabaseResult(handle)` returns the
  `List<OfflineRegionInfo>`.
- GL JS: web declines — GL JS has no offline API.

## Still capture on every packaged runtime

`MapState.captureStillImage` needs a snapshot target for the packaged runtime's
render backend, and two supported configurations have none: the Vulkan runtime
on Android and the OpenGL runtime on Desktop. Both throw
`UnsupportedOperationException`. An Android Vulkan target and a Desktop OpenGL
target close the gap; the desktop Vulkan targets in `MlnFfiSnapshotTarget` are
the model.

- FFI: the render session over an offscreen target per backend, as the desktop
  `VulkanImageTarget` builds it.
- GL JS: not applicable — the web declines still capture entirely.
