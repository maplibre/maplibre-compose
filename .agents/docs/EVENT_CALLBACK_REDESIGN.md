# Engine event redesign

Staging notes for replacing the map event callback bag. The shapes below are
representative, not locked. A prototype will change them.

This is a sibling of [GESTURE_REDESIGN.md](./GESTURE_REDESIGN.md). That document
owns pointer recognition, bindings, and the tap-family dispatch chain. This
document owns the events that MapLibre Native FFI and MapLibre GL JS emit. The
two surfaces meet only at camera movement state: a gesture token can mark a
camera change as user-driven, and an engine camera event can update the
viewport.

The design sits on the ownership model in
[the map API redesign spec](../scratch/api-redesign/spec.md): durable style
events belong to `MapState`, and camera, render, and surface events belong to a
render lease.

## What the current code is

Three layers reshape engine output into a lowest-common-denominator callback
list.

**The public bag** is `MapPresentationCallbacks`: `onClick`, `onLongClick`, and
`onFrame(fps)`. Layer composables add `onClick` and `onLongClick` that receive
queried features and return `ClickResult`. Load success and failure already live
on `MapStyleState.loadState`. Camera motion already lives on `MapPresentation`
as `viewport`, `isCameraMoving`, and `cameraMoveReason`.

**The internal adapter** is `MapAdapter.Callbacks`. Both engine sessions must
implement ten methods:

```text
onStyleChanged
onMapFinishedLoading
onSourceChanged
onMapFailLoading
onCameraMoveStarted(reason)
onCameraMoved
onCameraMoveEnded
onClick
onLongClick
onFrame(fps)
```

**Each session then invents the rest.** Native FFI emits
`MAP_CAMERA_WILL_CHANGE`, `MAP_CAMERA_IS_CHANGING`, `MAP_CAMERA_DID_CHANGE`, and
`MAP_CAMERA_TRANSITION_FINISHED`. The session collapses those into start / moved
/ ended, and sets `CameraMoveReason.GESTURE` or `PROGRAMMATIC` from an
`isGestureInProgress` flag. A drag is a stream of did-change events; the session
treats the gesture, not the jump, as the move. Web does the same coalesce from
`movestart` / `move` / `moveend`.

`onFrame` is an inter-frame elapsed-time measurement. Neither engine reports
frames per second. FFI reports `MAP_RENDER_FRAME_FINISHED` with encode time,
draw calls, render mode, and a needs-repaint flag. GL JS reports `render`.

`onSourceChanged` is not an FFI event. Native polls source attribution on idle
and notifies from style-binding mutations. Web forwards `sourcedata` only when
`sourceDataType == "metadata"`.

`onClick` and `onLongClick` are not engine events on either path we run. Compose
`MapInput` recognizes a tap or long press and calls
`GestureTarget.onPrimaryClick` / `onSecondaryClick`. The session then projects
the offset and invokes the adapter callbacks so `MaplibreMap` can walk layers.
GL JS has `click`, `dblclick`, and `contextmenu`. We do not subscribe to them.

Style load is a fusion of several engine events plus library bookkeeping. FFI
emits `MAP_STYLE_LOADED`, `MAP_LOADING_FINISHED`, `MAP_IDLE`, and
`MAP_LOADING_FAILED`. GL JS emits `style.load`, `styledata`, `sourcedata`,
`error`, and `idle`. The session collapses those into `onStyleChanged`,
`onMapFinishedLoading`, and `onMapFailLoading`, then `MapStyleState.loadState`.

This shape dates from a period when Android MapView, iOS `MGLMapView`, and GL JS
each had a different observer API, clicks arrived from the engine, and there was
no durable `MapState` to hold load or camera state. The portable surface was the
subset every SDK could fake.

## What the engines emit

FFI map events, from `mln_runtime_event_type` in maplibre-native-ffi 0.202608.3:

| Event                                 | Payload                                       |
| ------------------------------------- | --------------------------------------------- |
| `MAP_CAMERA_WILL_CHANGE`              | `camera_change_mode`: immediate or animated   |
| `MAP_CAMERA_IS_CHANGING`              | none                                          |
| `MAP_CAMERA_DID_CHANGE`               | `camera_change_mode`: immediate or animated   |
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

FFI has no pointer events. A host selects types with a mask. An unselected type
is never queued.

GL JS `MapEventType` (v6) splits into four families:

- Camera: `movestart`, `move`, `moveend`, plus `zoom*`, `rotate*`, `pitch*`,
  `roll*`, `drag*`, `boxzoom*`
- Style and data: `style.load`, `load`, `idle`, `error`, `styledata*`,
  `sourcedata*`, `data*`, `styleimagemissing`
- Render and surface: `render`, `resize`, `webglcontextlost`,
  `webglcontextrestored`, `terrain`, `projectiontransition`, `remove`
- Pointer: `click`, `dblclick`, `contextmenu`, `mouse*`, `touch*`, `wheel`

GL JS selects types with `on(type)`. Pointer events stay with Compose. A caller
that wants the raw GL JS map uses `MapState.withPlatformMap`.

## Three concerns that share one callback list

**Engine events** are what this document redesigns. The library already drains
them. The public API should name those events, not a reshape of them.

**Pointer events** are Compose input.
[GESTURE_REDESIGN.md](./GESTURE_REDESIGN.md) already treats tap, double tap,
long press, hover, and drag as gesture events with a dispatch chain. They do not
belong on an engine event type.

**Derived state** is the current value of something the map already knows:
`loadState`, `viewport`, `isCameraMoving`, `sources`. Most current callbacks
exist only to copy those values into Compose state. The API redesign already
moved load success and failure onto `MapStyleState.loadState`. Camera motion
already has the same home on `MapPresentation`. A caller that needs "the
viewport just changed" reads `presentation.viewport` or collects a snapshot
flow. A caller that needs the engine event itself subscribes to the event
stream.

## The shape

The library drains engine events once. It applies the reactions it needs, then
publishes the same values. There is no second callback interface that both
sessions must implement.

```text
engine event  →  identity filter  →  internal reactions  →  public Flow
(FFI drain /     (lease / style      (load tracker,         (typed MapEvent,
 GL JS on)        request)            viewport, render,      selected by mask)
                                      transition waiters)
```

Identity filtering stays. A departed render lease or a superseded style request
must not publish. That is ownership, not reshape.

Internal reactions stay. Style load tracking, viewport snapshots, render
scheduling, camera-transition waiters, and attribution refresh still consume
events. They become private handlers of the same typed values, not methods on
`MapAdapter.Callbacks`.

### Events

Immutable values in `commonMain`. The common set is the intersection of what
both engines emit. A platform extra is a subtype that the other platform never
constructs.

```kotlin
sealed interface MapEvent

// Intersection
class CameraWillChange(val mode: CameraChangeMode?) : MapEvent
object CameraIsChanging : MapEvent
class CameraDidChange(val mode: CameraChangeMode?) : MapEvent
object StyleLoaded : MapEvent
object LoadingStarted : MapEvent
object LoadingFinished : MapEvent
class LoadingFailed(val reason: String) : MapEvent
object Idle : MapEvent
class RenderFrameFinished(val stats: RenderFrameStats?) : MapEvent
class StyleImageMissing(val imageId: String) : MapEvent
class RenderError(val message: String) : MapEvent

// Native extras. Web never emits these.
class CameraTransitionFinished(val transitionId: Long) : MapEvent
object RenderUpdateAvailable : MapEvent
class TileAction(
  val sourceId: String,
  val operation: TileOperation,
  val tile: TileId,
) : MapEvent

// Web extras. Native never emits these.
class SourceData(val sourceId: String, val dataType: SourceDataType) : MapEvent
object StyleData : MapEvent
object Resized : MapEvent

enum class CameraChangeMode { Immediate, Animated }
```

`CameraChangeMode` is the FFI `mln_camera_change_mode` value: immediate or
animated. It is not gesture versus programmatic. Gesture versus programmatic
stays a fact on the presentation, derived from the gesture token the UI already
holds. Web has no change-mode field; `mode` is null, or a later prototype maps
`isCameraEasing()` to `Animated`.

`RenderFrameStats` is the FFI render-frame payload when present: encode time,
draw calls, render mode, needs-repaint, placement-changed. A GL JS `render`
event constructs `RenderFrameFinished(stats = null)`.

The type list is the engine catalog with pointer events removed and offline and
still-image events left on the objects that already own them (`OfflineManager`,
`MapSnapshotter`).

### Subscription

Both engines already select by type. FFI uses a mask. GL JS uses `on(type)`. A
`SharedFlow` of every event would force the library to subscribe to tile actions
and every frame. The public API is a cold flow over a mask:

```kotlin
interface MapState {
  fun events(mask: MapEventMask = MapEventMask.Standard): Flow<MapEvent>
}

interface MapPresentation {
  fun events(mask: MapEventMask = MapEventMask.Presentation): Flow<MapEvent>
}
```

`MapEventMask.Standard` is style loaded, loading started / finished / failed,
idle, style image missing, and render error. `MapEventMask.Presentation` is
camera will / is / did change plus render-frame-finished. High-frequency types
(`TileAction`, `RenderUpdateAvailable`, `CameraIsChanging`) join the mask only
when a caller names them.

The session's own mask is the union of those defaults and the types the library
needs: render-update-available, camera-transition-finished, and idle for
attribution. User subscriptions add bits. Removing the last subscriber of an
optional type can drop the bit.

Durable events (`StyleLoaded`, `LoadingFailed`, `StyleImageMissing`,
`TileAction`) come from `MapState.events`. They remain valid on a detached
native engine. Presentation events (`Camera*`, `RenderFrameFinished`, `Idle`,
`Resized`) come from `MapPresentation.events` and follow the current render
lease. One stream on `MapState` that is silent for presentation types while
detached is an acceptable prototype variant if two methods prove clumsy.

### What a caller writes

Load and camera stay state:

```kotlin
val load = state.style.loadState
val viewport = state.presentation?.viewport
val moving = state.presentation?.isCameraMoving == true
```

Transient engine facts become a collection:

```kotlin
LaunchedEffect(state) {
  state.events(MapEventMask.Standard).collect { event ->
    when (event) {
      is MapEvent.StyleImageMissing ->
        state.style.addImage(event.imageId, fallback)
      is MapEvent.LoadingFailed ->
        logger.e { event.reason }
      else -> Unit
    }
  }
}
```

A frame clock that today's `onFrame` approximated is a derived value, not an
engine callback:

```kotlin
val frameRate = state.presentation?.frameRate
```

`frameRate` updates from `RenderFrameFinished` timestamps. The demo benchmark
that records `callbacks.onFrame` reads this value, or collects
`RenderFrameFinished` and computes its own interval.

A missing-image **resolver** is a command, not a listener. GL JS states that a
`styleimagemissing` listener cannot supply the image for the request that raised
the event; the resolver API can. The event still publishes so a caller can log
or prefetch. The resolver, if we add one, is a parameter on style state or
runtime options.

### Deleted APIs

- `MapPresentationCallbacks` as a public bag.
- `MapClickHandler`, `FeaturesClickHandler`, and `ClickResult` on the engine
  callback path. They move to the gesture dispatch chain.
- `MapAdapter.Callbacks` as a ten-method reshape interface.
- `CameraMoveReason` as a field on an engine event. The presentation can still
  expose `cameraMoveReason` from the gesture token.
- `onFrame(fps)` as an engine callback.
- `onSourceChanged` as a unified callback. Native uses `TileAction` plus the
  style binding's own mutation notice. Web uses `SourceData`. Attribution
  refresh subscribes to `Idle` and those two types.

`MaplibreMap` keeps no event-callback parameter. Style composition keeps no
engine-event parameters. Layer `onClick` / `onLongClick` remain only until the
gesture redesign replaces them with the tap-family chain.

### Platform extras

`MapState.withPlatformMap` remains the escape hatch for types this catalog
omits: GL JS `zoomstart`, `webglcontextlost`, `cooperativegestureprevented`, and
the raw FFI `RuntimeEvent`. The common API does not invent a portable stand-in
for a type that one engine lacks.

### Internal sink

```kotlin
internal fun interface EngineEventSink {
  fun onEvent(event: MapEvent)
}
```

Each session translates one FFI `RuntimeEvent` or one GL JS listener into one
`MapEvent` and posts it. The translation is a rename plus payload copy. It does
not coalesce a drag into a single camera move, compute frames per second, or
project a click.

`isCameraMoving` becomes true on `CameraWillChange` and false on
`CameraDidChange`, or true while a gesture token is held or a transition id is
live. A prototype picks one rule and tests it against the existing token-order
tests. The current "a drag is one move" coalesce is a library convenience; if a
caller wants a gesture session, that caller reads the gesture system.

## Mapping

| Caller need                  | Today                                        | After                                             |
| ---------------------------- | -------------------------------------------- | ------------------------------------------------- |
| Style ready or failed        | `loadState` plus hidden callbacks            | `loadState` only                                  |
| Viewport                     | `presentation.viewport` plus `onCameraMoved` | `presentation.viewport`                           |
| Camera is moving             | `isCameraMoving` plus start/end callbacks    | `isCameraMoving`                                  |
| Gesture versus programmatic  | Invented `CameraMoveReason` on start         | Presentation field from the gesture token         |
| Frame rate                   | `onFrame(fps)`                               | `presentation.frameRate` or `RenderFrameFinished` |
| Missing sprite               | Log only                                     | `StyleImageMissing` plus an optional resolver     |
| Source attribution arrived   | Invented `onSourceChanged`                   | `Idle` + `TileAction` / `SourceData`              |
| Tile fetch progress          | Unavailable                                  | `TileAction` (native) or `SourceData` (web)       |
| Map or layer click           | `MapPresentationCallbacks` + layer params    | Gesture bindings in GESTURE_REDESIGN.md           |
| GL JS `zoom` / WebGL context | Unavailable in common                        | `withPlatformMap`                                 |

## Sequence

This can start in parallel with the gesture redesign. Deleting click callbacks
waits for the gesture chain. Introducing the event stream does not.

1. **Value model.** `MapEvent`, `MapEventMask`, `CameraChangeMode`, and the flow
   APIs in `commonMain`. No session wiring yet. Conflicts with nothing in
   flight.
2. **Session translation.** Both sessions emit `MapEvent` beside the existing
   `MapAdapter.Callbacks` calls. Behavior-identical. Tests assert that each
   handled FFI type and each subscribed GL JS type produces one `MapEvent`.
3. **Internal reactions move.** Style tracker, viewport snapshot, render
   request, and transition waiters read `MapEvent`. `MapAdapter.Callbacks`
   shrinks to clicks until the gesture work lands.
4. **Public flow.** `MapState.events` and `MapPresentation.events` publish.
   `onFrame` leaves `MapPresentationCallbacks`. The demo benchmark reads
   `frameRate` or `RenderFrameFinished`.
5. **Delete the bag.** After the gesture redesign takes clicks,
   `MapPresentationCallbacks` and `MapAdapter.Callbacks` go. Layer click
   parameters go with that PR, not this one.

## Open questions

**One stream or two.** Two methods match the identity table in the API redesign
spec. One method on `MapState` is simpler to teach. A prototype should try two,
because a detached native map still emits style events and must not emit camera
events from a departed lease.

**Web camera mode.** Null is honest. Mapping `isCameraEasing()` is a guess. The
first version leaves `mode` null on web.

**Render stats in common.** FFI has a structured payload. GL JS `render` does
not. `stats: RenderFrameStats?` keeps the common type honest. Promoting every
stat field to a required common field would invent zeros on web.

**Missing-image resolver.** The event is enough to stop logging and dropping
`MAP_STYLE_IMAGE_MISSING`. A resolver that can satisfy the in-flight request is
a separate API and can follow. See [COMMON_API_GAPS.md](./COMMON_API_GAPS.md).

**Dynamic masks.** Changing the native mask while a map is live is allowed; FFI
says narrowing keeps already-queued events. A prototype should confirm that
adding `TileAction` after attach is cheap enough that we do not subscribe to it
by default.

**Idle ownership.** FFI idle can fire without a presentation. GL JS idle dies
with the map. Treat idle as a presentation event on web and as a durable engine
event on native, or always require a lease. The identity table already groups
idle with presentation events; start there.
