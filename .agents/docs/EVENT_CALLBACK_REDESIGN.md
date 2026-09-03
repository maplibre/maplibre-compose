# Engine event redesign

Staging notes for replacing the map event callbacks with a stream of the events
that the engines emit. The shapes below are representative, not locked. A
prototype will change them.

This is a sibling of [GESTURE_REDESIGN.md](./GESTURE_REDESIGN.md). That document
owns pointer recognition, bindings, and click dispatch. This document owns the
events that MapLibre Native FFI and MapLibre GL JS emit. The two meet at one
point: the gesture token decides whether a camera change is user-driven.

## What the current code is

`MapAdapter.Callbacks` has ten methods that both engine sessions implement, and
`MapLifecycleCallbacks` filters each call through the engine identity, style
identity, or render lease that produced it. The ten methods are three unrelated
things.

**A two-way style handshake.** `onStyleChanged`, `onMapFinishedLoading`,
`onMapFailLoading`, and `onSourceChanged` are a protocol, not events. The
session offers a style binding, `MapState` accepts or rejects it, and the
session branches on the answer: it keeps or invalidates the binding, and it
clears or keeps its own "load unreported" flag.

**Camera state feeding.** `onCameraMoveStarted`, `onCameraMoved`, and
`onCameraMoveEnded` exist to set `viewport`, `isCameraMoving`, and
`cameraMoveReason` on the current attachment. Both sessions run the same
coalescing. Native emits `MAP_CAMERA_WILL_CHANGE`, `MAP_CAMERA_IS_CHANGING`, and
`MAP_CAMERA_DID_CHANGE`; the browser emits `movestart`, `move`, and `moveend`.
Compose drives a drag as a series of jumps, so each pointer move produces its
own did-change or moveend. The session reads its gesture flag and withholds the
end callback until the gesture ends, so that a drag reports as one move. The
session also sets `CameraMoveReason` from that same flag.

**Values that neither engine emits.** `onClick` and `onLongClick` originate in
Compose input. `MapInput` recognizes a tap or long press and calls
`GestureTarget.onPrimaryClick` or `onSecondaryClick`. The session unprojects the
offset and calls back into `MaplibreMap`, which walks the layers. `onFrame(fps)`
is an inter-frame interval that the session measures in its own render loop. The
FFI reports `MAP_RENDER_FRAME_FINISHED` with a payload, and GL JS reports
`render`, and neither reports a rate.

The public surface is `onClick`, `onLongClick`, and `onFrame` on `MaplibreMap`,
plus `onClick` and `onLongClick` on each layer composable. Load state, viewport,
and camera motion already surface as state on `MapState`.

This shape dates from a period when Android `MapView`, iOS `MLNMapView`, and GL
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

GL JS selects types with `on(type)`. The session subscribes to `style.load`,
`styledata`, `sourcedata`, `error`, `movestart`, `move`, and `moveend`.

## The shape

The library drains engine events once, reacts to the ones it needs, and
publishes the same values. Sessions translate; they do not reshape.

```text
engine event  →  identity filter  →  MapState reactions  →  public Flow
(FFI drain,      (lease or style     (load state,           (MapState.events)
 GL JS on)        identity)           viewport, camera)
```

Identity filtering stays. An event from a departed render lease or a superseded
style request is dropped. `Idle` reports the engine's own progress rather than a
style's or a lease's, so it filters on the engine identity and arrives after a
failed style load too. That is ownership, not reshape, and
`MapLifecycleCallbacks` already does it.

### Events

Immutable values in `commonMain`. Each one is an event that both engines emit in
the same terms. Each translation is a rename plus a payload copy.

```kotlin
public sealed interface MapEvent {
  public data object StyleLoaded : MapEvent

  public data class StyleLoadFailed(val reason: String) : MapEvent

  public data object Idle : MapEvent

  public data class CameraMoveStarted(val animated: Boolean?) : MapEvent

  public data object CameraMoved : MapEvent

  public data class CameraMoveEnded(val animated: Boolean?) : MapEvent

  public data class FrameRendered(val stats: RenderStats?) : MapEvent

  public data class StyleImageMissing(val imageId: String) : MapEvent

  public data class EngineError(val message: String, val sourceId: String?) : MapEvent
}
```

| Event               | maplibre-native-ffi         | maplibre-gl-js        |
| ------------------- | --------------------------- | --------------------- |
| `StyleLoaded`       | `MAP_STYLE_LOADED`          | `style.load`          |
| `StyleLoadFailed`   | `MAP_LOADING_FAILED`        | `error`, terminal     |
| `Idle`              | `MAP_IDLE`                  | `idle`                |
| `CameraMoveStarted` | `MAP_CAMERA_WILL_CHANGE`    | `movestart`           |
| `CameraMoved`       | `MAP_CAMERA_IS_CHANGING`    | `move`                |
| `CameraMoveEnded`   | `MAP_CAMERA_DID_CHANGE`     | `moveend`             |
| `FrameRendered`     | `MAP_RENDER_FRAME_FINISHED` | `render`              |
| `StyleImageMissing` | `MAP_STYLE_IMAGE_MISSING`   | `styleimagemissing`   |
| `EngineError`       | `MAP_RENDER_ERROR`          | `error`, non-terminal |

`animated` is the FFI `CameraChangeMode`. GL JS has no such field, so the
browser session passes null.

`RenderStats` is the FFI frame payload: render mode, needs-repaint,
placement-changed, encoding time, rendering time, frame count, and draw call
counts. GL JS `render` has no payload, so the browser session passes null.

The camera triple is per engine change, not per gesture. A Compose-driven drag
emits one started and ended pair per pointer move on both engines, because that
is what the engines emit. `isCameraMoving` stays a derived state, true while a
gesture token is held or a camera transition is in flight. `CameraMoveReason`
leaves the event path and stays a presentation fact that the gesture token sets.

`StyleLoaded` reports that the engine parsed the base style. The composition
applies after that, so `loadState` reaches `Ready` later. A caller that wants
the composed style ready reads `loadState`.

A terminal browser `error` is one that ends the base style request. The session
already separates that from a tile or sprite error with
`isTerminalStyleLoadFailure`.

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

The backing flow is a `SharedFlow` with a bounded buffer and drop-oldest. The
owner thread emits and must never block on a slow collector. Frames are the only
high-rate type, and the library already handles every frame through `onFrame`,
so a fixed engine mask covering the catalog above costs nothing new.
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
      is MapEvent.StyleImageMissing -> state.style.images.add(event.imageId, fallback)
      is MapEvent.StyleLoadFailed -> logger.e { event.reason }
      else -> Unit
    }
  }
}
```

A completed-frame signal is `FrameRendered`. [#1043] asks for exactly that: a
caller arms a token after a state change and consumes it on the next frame. The
demo frame counter already counts frames itself, and the benchmark timestamps
`FrameRendered` to recover intervals.

[#1043]: https://github.com/maplibre/maplibre-compose/issues/1043

### Internal sink

The style handshake stays a protocol, because a one-way stream cannot answer
"did you accept this binding". It shrinks to what it is: offer a loaded binding,
report the composition ready, report the load failed. Everything else is a
one-way fact:

```kotlin
internal interface MapAdapter.Callbacks {
  fun onStyleChanged(map: MapAdapter, style: StyleBinding?)
  fun onStyleReady(map: MapAdapter)
  fun onStyleFailed(map: MapAdapter, reason: String?)

  /** Returns false when the lifecycle rejected the event. */
  fun onEvent(map: MapAdapter, event: MapEvent): Boolean
}
```

Each session translates one FFI `RuntimeEvent` or one GL JS listener call into
one `MapEvent` and posts it. The translation does not coalesce a drag, compute a
rate, or unproject a click. `MapState` reacts inside `onEvent`: it snapshots the
viewport on the camera triple, sets `isCameraMoving` from the gesture token and
transition state, refreshes attribution on native `Idle`, and then publishes to
`events`.

A session may consume engine-only events for library reactions without those
events being common. The browser session keeps its `sourcedata` metadata
subscription for attribution refresh, and the native session keeps
`MAP_RENDER_UPDATE_AVAILABLE` for render scheduling and
`MAP_CAMERA_TRANSITION_FINISHED` for transition waiters.

### Clicks

Clicks leave the adapter now, ahead of the gesture redesign.
`MapState.positionFromScreenLocation` is already public, so `MapInput` can
unproject and dispatch to the map and layer handlers without the session round
trip. The gesture redesign later replaces the handlers with its tap-family
chain.

### Deleted APIs

- `onFrame` on `MaplibreMap`.
- `MapAdapter.Callbacks.onCameraMoveStarted`, `onCameraMoved`,
  `onCameraMoveEnded`, `onClick`, `onLongClick`, `onFrame`, and
  `onSourceChanged`.
- `CameraMoveReason` as a value that a session computes. It stays on `MapState`
  as a value that the gesture token sets.

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

| Caller need                 | Today                                   | After                                    |
| --------------------------- | --------------------------------------- | ---------------------------------------- |
| Style ready or failed       | `loadState` plus hidden callbacks       | `loadState`                              |
| Viewport                    | `viewport` plus `onCameraMoved`         | `viewport`                               |
| Camera is moving            | `isCameraMoving` plus start and end     | `isCameraMoving`                         |
| Gesture versus programmatic | `CameraMoveReason` from a session flag  | `cameraMoveReason` from the token        |
| Frame completed             | `onFrame(fps)`                          | `FrameRendered`                          |
| Frame rate                  | `onFrame(fps)`                          | Timestamps of `FrameRendered`            |
| Missing sprite              | Logged and dropped                      | `StyleImageMissing`                      |
| Engine error                | Logged                                  | `EngineError`                            |
| Map or layer click          | Session round trip and layer parameters | Compose dispatch, then the gesture chain |
| Tile progress, GL JS extras | Unavailable in common                   | `withPlatformMap`                        |

## Sequence

Each step lands on its own. The first two conflict with nothing in flight.

1. **Clicks out of the adapter.** `MapInput` unprojects through `MapState` and
   dispatches directly. `onClick` and `onLongClick` leave
   `MapAdapter.Callbacks`. Behavior is identical.
2. **Value model and translation.** `MapEvent` and `RenderStats` in
   `commonMain`. Both sessions post events through `onEvent` beside the existing
   callbacks. Tests assert that each handled FFI type and each subscribed GL JS
   type produces one `MapEvent` with the right payload.
3. **Reactions move.** Viewport snapshots, `isCameraMoving`, and attribution
   refresh read `MapEvent`. The camera trio and `onSourceChanged` leave the
   adapter. A resize changes the projection without a camera event, so both
   sessions keep a viewport snapshot of their own beside the event path.
   Existing presentation tests pin the token ordering.
4. **Public flow.** `MapState.events` publishes. `onFrame` leaves `MaplibreMap`,
   and the demo frame counter and benchmark move to `FrameRendered`.
5. **Handshake rename.** The three style methods take their protocol names.

## Open questions

**Missing images on the browser.** GL JS 6.6 fires `styleimagemissing` only
after its `setMissingStyleImageResolver` callback has had a chance to supply the
image, and a listener cannot resolve the current request. On native the event
drains asynchronously, and mbgl re-checks the image set after a later
`setStyleImage`. The event is enough to stop dropping the FFI event. Whether an
add from a collector satisfies the pending request on both engines needs a live
test. If the browser needs the resolver, that is a separate command-style API,
tracked in [COMMON_API_GAPS.md](./COMMON_API_GAPS.md).

**Frame source on native.** The session's own render loop knows when
`renderUpdate` drew a frame, and the FFI queues `MAP_RENDER_FRAME_FINISHED` from
the mbgl observer inside that same render. The event is the engine's contract,
so `FrameRendered` comes from the drain. A prototype confirms that the drain
cadence keeps the event close to the frame it describes.

**Browser camera mode.** Null is honest. Mapping `isEasing()` is a guess. The
first version passes null.

**Error on the browser.** GL JS `error` carries tile, sprite, glyph, and source
failures with a `sourceId`. FFI `MAP_RENDER_ERROR` is a render failure, and tile
errors arrive as `MAP_TILE_ACTION` with the error operation. `EngineError` is
the lowest-value row in the catalog, and a prototype may drop it rather than
publish two different things under one name.
