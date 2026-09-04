# Engine event redesign

Design notes for the stream of engine events that `MapState.events` publishes,
and for the internal sink that feeds it. The shapes below are the ones in the
tree.

This is a sibling of [GESTURE_REDESIGN.md](./GESTURE_REDESIGN.md). That document
owns pointer recognition, bindings, and click dispatch. This document owns the
events that MapLibre Native FFI and MapLibre GL JS emit. The two meet at one
point: the gesture token decides whether a camera change is user-driven.

## What the code was

`MapAdapter.Callbacks` had ten methods that both engine sessions implemented,
and `MapLifecycleCallbacks` filtered each call through the engine identity,
style identity, or render lease that produced it. The ten methods were three
unrelated things.

**A two-way style handshake.** `onStyleChanged`, `onMapFinishedLoading`,
`onMapFailLoading`, and `onSourceChanged` were a protocol, not events. The
session offered a style binding, `MapState` accepted or rejected it, and the
session branched on the answer. The handshake survives under protocol names.

**Camera state feeding.** `onCameraMoveStarted`, `onCameraMoved`, and
`onCameraMoveEnded` existed to set `viewport`, `isCameraMoving`, and
`cameraMoveReason` on the current attachment. Both sessions ran the same
coalescing. Compose drives a drag as a series of jumps, so each pointer move
produced its own did-change or moveend, and the session withheld the end
callback until its gesture flag cleared so that a drag reported as one move. The
session also set `CameraMoveReason` from that flag. The three callbacks are
gone, the sessions post the engine's own camera events, and `MapState` derives
the state.

**Values that neither engine emits.** `onClick` and `onLongClick` originated in
Compose input, took a session round trip to unproject the offset, and came back
into `MaplibreMap`, which walked the layers. `onFrame(fps)` was an inter-frame
interval that the session measured in its own render loop. Clicks now dispatch
from Compose input, and the frame signal is the engine's own frame event.

That shape dated from a period when Android `MapView`, iOS `MLNMapView`, and GL
JS each had a different observer API, clicks arrived from the engine, and there
was no durable `MapState` to hold load or camera state. The portable surface was
the subset every SDK could fake.

## What the engines emit

FFI map events, from `RuntimeEventType` in maplibre-native-ffi 0.202608.3. The
session selects types with a mask, and an unselected type is never queued.

| Event                                 | Payload                                       |
| ------------------------------------- | --------------------------------------------- |
| `MAP_CAMERA_WILL_CHANGE`              | `CameraChangeMode`: immediate or animated     |
| `MAP_CAMERA_IS_CHANGING`              | none                                          |
| `MAP_CAMERA_DID_CHANGE`               | `CameraChangeMode`: immediate or animated     |
| `MAP_CAMERA_TRANSITION_FINISHED`      | transition id                                 |
| `MAP_STYLE_LOADED`                    | none                                          |
| `MAP_LOADING_STARTED`                 | none                                          |
| `MAP_LOADING_FINISHED`                | none                                          |
| `MAP_LOADING_FAILED`                  | failure text                                  |
| `MAP_IDLE`                            | none                                          |
| `MAP_RENDER_UPDATE_AVAILABLE`         | none                                          |
| `MAP_RENDER_ERROR`                    | error text                                    |
| `MAP_RENDER_FRAME_STARTED`            | none                                          |
| `MAP_RENDER_FRAME_FINISHED`           | mode, needs-repaint, placement-changed, stats |
| `MAP_RENDER_MAP_STARTED`              | none                                          |
| `MAP_RENDER_MAP_FINISHED`             | mode                                          |
| `MAP_STYLE_IMAGE_MISSING`             | image id                                      |
| `MAP_TILE_ACTION`                     | source id, tile operation, tile id            |
| `MAP_STILL_IMAGE_FINISHED` / `FAILED` | snapshotter only                              |
| `OFFLINE_*`                           | offline manager only                          |

The FFI has no pointer events. `RuntimeEventType` is an open domain, so a newer
native library can queue a type that this build has no name for.

GL JS `MapEventType` in maplibre-gl 6.6.0, grouped:

- Camera: `movestart`, `move`, `moveend`, plus the `zoom`, `rotate`, `pitch`,
  `roll`, `drag`, and `boxzoom` start, progress, and end triples
- Style and data: `style.load`, `load`, `idle`, `error`, `styledata`,
  `styledataloading`, `sourcedata`, `sourcedataloading`, `sourcedataabort`,
  `data`, `dataloading`, `dataabort`, `styleimagemissing`
- Render and surface: `render`, `resize`, `webglcontextlost`,
  `webglcontextrestored`, `terrain`, `projectiontransition`, `remove`
- Pointer: `click`, `dblclick`, `contextmenu`, `mousemove` and the other mouse
  events, the touch events, `wheel`, `cooperativegestureprevented`

GL JS selects types with `on(type)`.

## The shape

The library drains engine events once, reacts to the ones it needs, and
publishes the same values. Sessions translate; they do not reshape.

```text
engine event  →  identity filter  →  MapState reactions  →  public Flow
(FFI drain,      (lease, style, or   (viewport, camera     (MapState.events)
 GL JS on)        engine identity)    change)
```

Identity filtering stays, and `MapLifecycleCallbacks` does it. Each event
filters on the identity that produced it:

| Identity      | Events                                                                 |
| ------------- | ---------------------------------------------------------------------- |
| Render lease  | `CameraMoveStarted`, `CameraMoved`, `CameraMoveEnded`, `FrameRendered` |
| Style         | `StyleLoaded`                                                          |
| Style request | `StyleLoadFailed`                                                      |
| Engine        | `Idle`                                                                 |

An event from a departed render lease or a superseded style request is dropped.
`Idle` reports the engine's own progress rather than a style's or a lease's, so
it filters on the engine identity and arrives after a failed style load too.
That is ownership, not reshape.

### Events

Immutable values in `commonMain`. Each one is an event that both engines emit in
the same terms, and each translation is a rename plus a payload copy.

```kotlin
public sealed interface MapEvent {
  public data object StyleLoaded : MapEvent

  public data class StyleLoadFailed(val reason: String) : MapEvent

  public data object Idle : MapEvent

  public data class CameraMoveStarted(val animated: Boolean?) : MapEvent

  public data object CameraMoved : MapEvent

  public data class CameraMoveEnded(val animated: Boolean?) : MapEvent

  public data class FrameRendered(val stats: RenderStats?) : MapEvent
}
```

| Event               | maplibre-native-ffi         | maplibre-gl-js    |
| ------------------- | --------------------------- | ----------------- |
| `StyleLoaded`       | `MAP_STYLE_LOADED`          | `style.load`      |
| `StyleLoadFailed`   | `MAP_LOADING_FAILED`        | `error`, terminal |
| `Idle`              | `MAP_IDLE`                  | `idle`            |
| `CameraMoveStarted` | `MAP_CAMERA_WILL_CHANGE`    | `movestart`       |
| `CameraMoved`       | `MAP_CAMERA_IS_CHANGING`    | `move`            |
| `CameraMoveEnded`   | `MAP_CAMERA_DID_CHANGE`     | `moveend`         |
| `FrameRendered`     | `MAP_RENDER_FRAME_FINISHED` | `render`          |

`MlnFfiMapEvents.kt` holds the native translation as
`RuntimeEvent.toMapEvent()`, and `GlJsMapEvents.kt` holds the browser
translation as two maps from GL JS type name to translation, one for the engine
identity and one for the presentation identity. The browser reports the one
style-identity event, `StyleLoaded`, from its style load tracking rather than
from a translation map.

`animated` is true when the FFI `CameraChangeMode` is `ANIMATED`. GL JS has no
such field, so the browser session passes null.

`RenderStats` is the FFI frame payload: `mode`, `needsRepaint`,
`placementChanged`, `encodingTime`, `renderingTime`, `frameCount`,
`drawCallCount`, and `totalDrawCallCount`. The times are `Duration` values
converted from the FFI's seconds. `mode` is a nested `RenderStats.Mode` of
`Partial` or `Full`, and it is null for a `RenderMode` that this build does not
name, because `RenderMode` is an open domain like `RuntimeEventType`. GL JS
`render` has no payload, so the browser session passes null.

The camera triple is per engine change, not per gesture. A Compose-driven drag
emits one started and ended pair per pointer move on both engines, because that
is what the engines emit.

`StyleLoaded` reports that the engine parsed the base style. The composition
applies after that, so `loadState` reaches `Ready` later. A caller that wants
the composed style ready reads `loadState`.

A terminal browser `error` is one that ends the base style request. The session
separates that from a tile or sprite error with `isTerminalStyleLoadFailure`.

### Publication

One stream on `MapState`:

```kotlin
public class MapState {
  public val events: Flow<MapEvent>
}
```

`MapState` is the durable logical map, so style events from a retained native
engine flow while the map is detached, and camera and frame events stop while no
lease is current. This matches `viewport`, which is null while detached.

The backing flow is a `MutableSharedFlow` with no replay, an extra buffer of 64
events, and drop-oldest overflow. `MapState.onEvent` calls `tryEmit` after its
reactions, so a collector reads the values that the event produced. The owner
thread emits and never blocks on a slow collector; 64 is about half a second of
frames at 120 Hz. A collector on an undispatched context runs on the thread that
reported the event, under the lock that serializes the map lifecycle, so the
KDoc on `events` tells callers to collect on a dispatcher before calling a map
command.

Frames are the only high-rate type, and the library handled every frame before
the redesign, so a fixed engine mask covering the catalog costs nothing new.
Subscription-counted masks, which both engines can express, wait until a
high-rate optional type such as tile actions joins the catalog.

### What a caller writes

Load and camera stay state:

```kotlin
val load = state.style.loadState
val viewport = state.viewport
val moving = state.isCameraMoving
```

Transient engine facts become a collection:

```kotlin
LaunchedEffect(state) {
  state.events.collect { event ->
    when (event) {
      is MapEvent.StyleLoadFailed -> logger.e { event.reason }
      else -> Unit
    }
  }
}
```

A missing style image is a request rather than a fact, so it is a resolver on
`MapState` rather than an event, under
[Resolved questions](#resolved-questions).

A completed-frame signal is `FrameRendered`. [#1043] asks for exactly that: a
caller arms a token after a state change and consumes it on the next frame. The
demo frame counter counts `FrameRendered`, and the benchmark timestamps each one
to recover intervals.

[#1043]: https://github.com/maplibre/maplibre-compose/issues/1043

### Internal sink

The style handshake stays a protocol, because a one-way stream cannot answer
"did you accept this binding". The sink names it: offer a loaded binding, report
the composition ready, report the load failed, report a source change. The rest
are one-way facts, plus one more question:

```kotlin
internal interface MapAdapter.Callbacks {
  fun onStyleChanged(map: MapAdapter, style: StyleBinding?)
  fun onStyleReady(map: MapAdapter)
  fun onStyleFailed(map: MapAdapter, reason: String?)
  fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?)

  fun onEvent(map: MapAdapter, event: MapEvent)
  fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>?
  fun onGestureActive(map: MapAdapter, active: Boolean)
  fun onViewportChanged(map: MapAdapter)
}
```

`resolveMissingImage` is the second question in the sink, and the only one that
returns a value to the session: the browser returns that promise to MapLibre.

`onEvent` returns `Unit`. `MapLifecycleCallbacks` answers whether the identity
that produced a call is still current, and the session branches on that answer,
so the sink does not carry a second one. `MapState` re-checks the adapter before
it reacts, as `synchronizeCamera` does.

`onStyleSourcesChanged` survives the redesign. The native session finds newly
arrived TileJSON attribution by polling `styleSourceInfo` on its owner thread
and reports each source once, and its style binding reports its own adds and
removes; neither is an engine event. Refreshing sources on every `Idle` instead
would rewrite the `style.sources` snapshot on each idle and recompose every
reader.

`onGestureActive` and `onViewportChanged` are presentation facts that no engine
emits. The gesture token lives in the session, and on native the gesture ends on
the owner thread only after the queued camera commands and their events have
drained (`finishPendingGesture`), so the session reports it in the right order.
A native resize adopts a new viewport without emitting a camera event, and
publishing a synthetic `CameraMoved` for it would reshape rather than translate.

Each session translates one FFI `RuntimeEvent` or one GL JS listener call into
one `MapEvent` and posts it. The translation does not coalesce a drag, compute a
rate, or unproject a click. The native session refreshes its viewport mirror
inside the same acceptance as each camera event, through the `beforeDelegate`
parameter of the presentation-scoped `MapLifecycleCallbacks.onEvent`, so
`MapState` reads a fresh viewport during a drag.

`MapState.onEvent` reacts, then publishes. On each of the camera triple it calls
`synchronizeCamera`, which reads the adapter's camera position and viewport and
publishes both. `CameraMoveStarted` also marks a camera change in flight on the
attachment, and `CameraMoveEnded` clears it. `FrameRendered` and the rest carry
no reaction.

`isCameraMoving` is derived on `MapAttachment`: a gesture holds the camera, or
an engine camera change is in flight. `cameraMoveReason` becomes `GESTURE` when
`onGestureActive(true)` arrives, and `PROGRAMMATIC` when a `CameraMoveStarted`
arrives with no gesture active. A gesture sets the reason even while an engine
change is in flight, because the token decides whether a change belongs to the
user. `MapStateEventReactionTest` pins these rules in `commonTest`, and
`CameraMoveReportingTest` and `MlnFfiGestureTokenOrderingTest` pin them against
live maps.

A session may consume engine-only events for library reactions without those
events being common. The browser session keeps its `styledata` and `sourcedata`
subscriptions for load reporting and attribution refresh, and resumes eased
transitions on an accepted `CameraMoveEnded`. The native session keeps
`MAP_RENDER_UPDATE_AVAILABLE` for render scheduling, `MAP_LOADING_FINISHED` as a
second route to reporting a loaded style, and `MAP_CAMERA_TRANSITION_FINISHED`
for transition waiters. Both engines log their non-terminal errors: native
`MAP_RENDER_ERROR`, and browser `error` outside a style load.

### Clicks

Clicks left the adapter ahead of the gesture redesign. `MapInput` reports a tap
or long press to a `MapClickTarget`, and `MapClickDispatcher` in `MaplibreMap`
unprojects through `MapState.positionFromScreenLocation` and dispatches to the
map and layer handlers. A click that arrives while the map has no viewport is
dropped without a log line. The gesture redesign replaces the handlers with its
tap-family chain.

### Deleted APIs

- `onFrame` on `MaplibreMap`.
- `MapAdapter.Callbacks.onCameraMoveStarted`, `onCameraMoved`,
  `onCameraMoveEnded`, `onClick`, `onLongClick`, and `onFrame`.
- `GestureTarget.onPrimaryClick` and `onSecondaryClick`, moved to
  `MapClickTarget`.
- `EngineError`, which appeared in the first draft of the catalog. Native
  `MAP_RENDER_ERROR` is a render failure, and browser `error` is a tile, sprite,
  glyph, or source failure with a source id. Both engines log those, and nothing
  in the tree consumed one event that meant two different things.
- `CameraMoveReason` as a value that a session computes. It stays on `MapState`
  as a value that the gesture token and the camera events set.
- `CameraMoveReason.UNKNOWN` as a reported value. The enum keeps the constant,
  and its KDoc states that the library never reports it.
- `MapEvent.StyleImageMissing`, and the `styleimagemissing` subscription and the
  `GlJsMapEvent.id` field that fed it. `MapState.missingImageResolver` replaces
  it, under [Resolved questions](#resolved-questions).

### Deferred from the common catalog

Each of these is an event that only one engine emits, or that the two engines
emit in different terms. `MapState.withPlatformMap` remains the route to them.
Sealed subclasses cannot live in platform source sets, so a platform-only
subtype in `commonMain` is a constructor that the other engine never calls, and
the common type does not gain one.

- Tile actions. FFI `MAP_TILE_ACTION` carries an operation and a tile id. GL JS
  `sourcedata` carries a source data type and an optional tile. FFI has no
  metadata-loaded event, which is why native polls attribution on idle.
- `MAP_LOADING_STARTED` and `MAP_LOADING_FINISHED`. GL JS `load` fires once per
  map, and `idle` is the closer match for finished.
- `resize`. Compose already knows the map's size.
- `terrain`, `projectiontransition`, `webglcontextlost`, and the `zoom`,
  `rotate`, `pitch`, and `roll` triples. Browser only.

## Mapping

| Caller need                 | Before                                  | After                                    |
| --------------------------- | --------------------------------------- | ---------------------------------------- |
| Style ready or failed       | `loadState` plus hidden callbacks       | `loadState`                              |
| Viewport                    | `viewport` plus `onCameraMoved`         | `viewport`                               |
| Camera is moving            | `isCameraMoving` plus start and end     | `isCameraMoving`                         |
| Gesture versus programmatic | `CameraMoveReason` from a session flag  | `cameraMoveReason` from the token        |
| Frame completed             | `onFrame(fps)`                          | `FrameRendered`                          |
| Frame rate                  | `onFrame(fps)`                          | Timestamps of `FrameRendered`            |
| Missing sprite              | Logged and dropped                      | `MapState.missingImageResolver`          |
| Engine error                | Logged                                  | Logged                                   |
| Map or layer click          | Session round trip and layer parameters | Compose dispatch, then the gesture chain |
| Tile progress, GL JS extras | Unavailable in common                   | `withPlatformMap`                        |

## What landed

The redesign landed as five commits, each on its own: clicks out of the adapter,
the value model and translation, reactions moved to `MapState`, the public flow
with `onFrame` removed, and the handshake rename. `EngineEventTest` asserts each
event on a live map on both engines, and `MlnFfiMapEventsTest` and
`GlJsMapEventsTest` assert the translations.

## Resolved questions

**Missing images are a resolver, not an event.** The catalog carried
`StyleImageMissing`, and it was removed. GL JS 6.6 fires `styleimagemissing`
only after its `setMissingStyleImageResolver` callback has declined, so a
listener on the browser can never satisfy the request that reported the event;
it can only supply the id for later requests. The event also made every caller
write the same bookkeeping: a set of ids already supplied, a wait for
`StyleLoadState.Ready`, and a clear on each style load, all from a collector
that suspends inside a drop-oldest buffer and so can lose the event it is
waiting on.

`MapState.missingImageResolver` replaces it: a suspending function from image id
to `ResolvedStyleImage?`, null for unresolved. The options that the resolved
image needs, `sdf` and `stretch`, belong to the result rather than to the call,
because they describe the image that the resolver chose rather than the request.
`MapState` owns the bookkeeping: it resolves each id at most once per loaded
style and adds the image to the style that asked for it. A new base style, or a
different resolver, lets the next engine request for that id reach the resolver
again.

The add cannot wait for `StyleLoadState.Ready`, and cannot assert it afterward
either. On the browser a style counts as loaded only once every in-view tile has
parsed, and a tile does not finish parsing until the resolver's promise settles,
so a wait for ready is a cycle that leaves the map blank. The add waits for the
style command in progress instead, and afterward checks only that the style it
added to is still the loaded one.

The reservation the add holds is its own, separate from the one an imperative
style command takes. An app cannot anticipate a resolution, so a command it
issues must not fail on one: `requireNoActiveStyleMutation` reads only the
command's reservation, while a new style revision waits for either.

The browser session registers `setMissingStyleImageResolver` and returns the
resolution as a promise, which MapLibre awaits before it treats the image as
missing, so the resolved image satisfies the request that asked for it. The
native session keeps `MAP_STYLE_IMAGE_MISSING` in its event mask and calls the
resolver from the drain; mbgl re-checks its image set at the next placement
after `setStyleImage`, so an asynchronous answer reaches a later frame, not the
one that raised the miss. `MissingImageResolverTest` asserts on both engines
that a resolved image reaches the style, that the resolver runs once per loaded
style, that a reload asks again, and that a replacement resolver answers an id
the first declined.

**Frame source on native.** `FrameRendered` comes from the drain, because the
event is the engine's contract. The runtime loop parks in `pump` and a queued
event wakes it, so the event follows the frame it describes without waiting for
the next pump. The session's own `reportFrameRate` measurement is gone.

**Browser camera mode.** The browser passes null. Mapping `isEasing()` would be
a guess.

**Error on the browser.** Dropped from the catalog, as the
[Deleted APIs](#deleted-apis) entry states.
