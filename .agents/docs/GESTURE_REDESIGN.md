# Gesture redesign for 0.16

Staging notes for the
[0.16.0 gestures milestone](https://github.com/maplibre/maplibre-compose/milestone/16):
[#230](https://github.com/maplibre/maplibre-compose/issues/230) (reimplement
camera gestures with full configurability),
[#951](https://github.com/maplibre/maplibre-compose/issues/951) (pointer-move
callback), and [#952](https://github.com/maplibre/maplibre-compose/issues/952)
(double-click callback). The API examples are provisional and will be tested
with prototypes.

This ships against the ownership API: `MaplibreMap(state)` with the camera on
`MapState`. `GestureOptions` is deleted, with no compatibility layer.

Engine events such as style load, idle, and frames are available through
`MapState.events`. Click dispatch runs in Compose; this gesture chain replaces
the click handlers.

Focus and key engagement are already in the tree, in the key handler in
`MapInput.kt` and the `isEngaged` property on `MapState`. A focused map consumes
direction keys only while engaged: Enter, D-pad center, or a pointer press
engages it, Escape releases it, and Back releases it when a key engaged it. That
work constrains the `keys` block here in three ways:

- The bindings determine focusability as well as which recognizers run. No key
  and no rotary binding means the map stays out of focus traversal.
- Engagement is a layer above the bindings and gates whether they fire. The
  engage and disengage keys, fixed today, become members of the `keys` builder.
- The `keys` block broadens to focused input, so rotary bindings for Wear join
  it. Rotary events reach the focused node the same way keys do.

## Current limitations

`MapInput.kt` combines three concerns in one state machine:

- **Recognition** turns raw pointer and key events into decisions: this motion
  is a pan, this pointer pair is a pinch, this pair of taps is a double tap. The
  slops, the two-finger classification, the tap pairing with bounce rejection,
  and pressure filtering belong to recognition.
- **Binding** maps input conditions to gestures. These decisions are inline
  conditionals: secondary button or Ctrl means rotate and tilt, quick zoom
  requires a touch pointer, Shift plus arrows means rotate.
- **Response** is the effect of a recognized gesture: camera deltas through
  `GestureTarget` with gesture tokens, fling continuations through
  `GestureContinuation`, or a click callback.

`GestureOptions` mixes enable flags and scalars for all three concerns. It
cannot express a binding change ("Alt-drag rotates, right-drag does nothing") or
a response change ("deliver the double click to me instead of zooming"), which
is most of what the milestone asks for.

## Proposed design

Callers configure bindings. Recognition and competition between gestures stay
internal.

```
raw input  →  arena + recognizers  →  gesture events  →  bindings  →  actions
              (internal)               (public values)    (public)     camera,
                                                                       dispatch,
                                                                       user code
```

### Arena and recognizers

One event loop per map, using one `pointerInput` and one key handler, runs
recognizers and resolves competition between them. Recognition identifies
motion: a down and up within slop and time is a `Tap`, two paired taps are a
`DoubleTap`, sustained single-pointer motion is a drag, diverging pointers are a
pinch. Buttons, modifier keys, and pointer type are not separate gestures; they
are facts carried on the event.

The bindings determine which recognizers run. `awaitsSecondTap()` in the current
code shows why this feedback exists: a touch tap is delayed for the double-tap
window only when something binds a second tap, because otherwise the delay is
unnecessary. The recognizers and their filters derive from the bindings.

Recognition math stays pure and unit-tested, as `GestureMath` and
`TapPairingTest` already are. Gesture competition stays internal. See
[No recognizer SPI](#no-recognizer-spi).

### Events

Immutable values in `commonMain`. Every event carries the screen offset and the
projected geographic position; continuous events carry deltas and velocity.

```kotlin
sealed interface GestureEvent {
  val screenOffset: DpOffset
  val position: Position
  val pointerType: PointerType
  val button: PointerButton
  val modifierKeys: ModifierKeys
}

class TapEvent(...) : GestureEvent
class DoubleTapEvent(...) : GestureEvent
class LongPressEvent(...) : GestureEvent
class HoverEvent(...) : GestureEvent

sealed interface DragEvent : GestureEvent {
  class Start(...) : DragEvent
  class Delta(val delta: DpOffset, ...) : DragEvent
  class End(val velocity: Velocity, ...) : DragEvent
}
// PinchEvent, TwoFingerRotateEvent, ShoveEvent, ScrollEvent: same pattern.
```

The projected position comes through a projection function injected at attach
time, so these types reference no map state type and the release order inside
0.16 does not matter.

### Bindings

A binding combines a filter and an action. Filters replace the current inline
conditions:

```kotlin
class PointerFilter(
  val pointerTypes: Set<PointerType>? = null, // null: any
  val button: PointerButton = PointerButton.Primary,
  val modifierKeys: ModifierKeys = ModifierKeys.None,
)
```

`MapGestures` is the ordered collection of bindings, built with a builder DSL
and immutable once built. It is not a data class: a public constructor, `copy`,
and `componentN` make every added option a source and binary break, and this API
grows with each binding and scalar. New optional builder members preserve
existing call sites.

`MapGestures { }` yields the standard defaults; the lambda edits a builder
pre-populated with them, the way `Json { }` edits a default configuration.
`MapGestures.None` is the empty value, and `MapGestures(from = other) { }`
starts from another value.

```kotlin
val gestures = MapGestures {
  // The defaults are present. Blocks edit them; adders and removers change the list.
  dragRotateTilt { filter = PointerFilter(button = Primary, modifierKeys = Alt) }
  scrollZoom { modifierKeys = Ctrl } // a map embedded in a scrollable page
  doubleTap {
    onEvent { event -> ClickResult.Consume } // consuming suppresses the zoom
    zoom = null                              // or remove the camera fallthrough
  }
  quickZoom { direction = QuickZoomDirection.UpZoomsIn }
  keys {
    rotate(Shift + LeftRight)
    clearZoom()
  }
  drag(PointerFilter(button = Primary, modifierKeys = Shift)) { onEvent(boxZoomTool) }
}
```

Every configurability item from #230 is one builder edit: Apple-style Alt-drag
rotate, Ctrl-gated scroll, touch-only or mouse-only gestures per binding,
quick-zoom direction, key assignments, decay per gesture. The current presets
return as named values built with the DSL.

Scalars move onto the binding they configure. `isRotateVelocityEnabled` becomes
`continuation = null` on the rotate binding; the decay constants in
`GestureMath` become a `Fling` value on the pan action.

The built value has content-based equality, because it keys the arena's
`pointerInput`: a recomposition that rebuilds an equal configuration must not
restart an in-flight gesture. Handler lambdas may change identity during
recomposition, so the arena compares configuration data separately and reads the
latest handlers through `rememberUpdatedState`. A fresh lambda updates in place;
only a data change restarts the arena.

### Actions

Continuous gestures (drag, pinch, rotate, shove, scroll) act on the camera
directly, or run a user lambda:

```kotlin
sealed interface DragAction {
  class Pan(val fling: Fling? = Fling.Standard) : DragAction
  class RotateTilt(val bearingPerDp: Double = 0.8, val pitchPerDp: Double = -0.5) : DragAction
  class Zoom(...) : DragAction
  class Custom(val onEvent: (DragEvent) -> Unit) : DragAction
}
```

`Custom` receives the start, delta, and end events with positions attached.
Box-zoom selection, lasso selection, and measuring tools are a `Custom` drag
action; none of them needs a new recognizer.

A drag binding can also take a start predicate, which runs on press and claims
or declines the drag before the camera binding wins it. Dragging a feature is
the motivating case: hit-test on press, claim the drag over pan when the press
is on a draggable feature.

### Dispatch chains for tap-family events

Tap, double tap, long press, and two-finger tap are contended: one event has a
map-level handler, layer handlers, and possibly a camera action as candidate
consumers. Their action is a chain, not a single response:

```
DoubleTapEvent
  → binding-level handler (optional)                 Consume?
  → map-level handler (today's onMapClick analog)    Consume?
  → layer walk: queryRenderedFeatures topmost-first,
    each layer's onDoubleClick                       Consume?
  → camera fallthrough: ZoomIn(anchor)
```

Double-tap zoom is the unconsumed fallthrough of the double-tap chain. A layer
or map-level handler that consumes the event suppresses the zoom, with the
`ClickResult.Consume` pattern that map and layer clicks already use. Removing
the fallthrough disables the camera response without a flag. This is the answer
to #952.

The chain is suspend-aware: the layer walk queries rendered features, so the
camera fallthrough waits for the consumption decision. This adds a feature query
to double-tap zoom latency. Continuous gestures never enter a chain; they go
straight to the camera.

Layer handlers stay declared as parameters on the layer composables in style
content. The chain discovers them at delivery time by walking the composition's
layer nodes. The binding system does not know layers exist.

Hover (#951) is the same chain without a camera fallthrough. The map-level hover
event costs only a projection and is always available. Per-layer hover enter and
exit are opt-in per layer, query only the layers that declared a handler, and
are throttled to frame rate, because a feature query per move event is a real
cost.

### Camera path

The internal `GestureTarget` and `GestureContinuation` become a public
gesture-camera API on `MapState`, provisionally named `state.gestureCamera`. The
state's main camera API remains separate. Per-frame gesture deltas are async
camera commands with no result. Discrete eased gestures are the awaiting
variants.

The gesture-token and continuation lifecycle stays owned by the gesture node in
the UI, because gestures attach and detach with the render session. The
operations that lifecycle drives live on the state.

Callers can replace default input handling with `MapGestures.None` and their own
input handling through `state.gestureCamera`.

### No recognizer SPI

0.16 ships built-in recognizers only. Box zoom, lasso, measuring, drawing, and
feature dragging use taps and drags with custom responses. Filters, `Custom`
actions, and the drag start predicate cover these cases. A public recognizer
interface would freeze the arena's internals (event passes, claim resolution,
token handoff) exactly when the trackpad work will force arena changes, because
trackpad pinch and rotate arrive as different raw event types from pointer
pairs.

A gesture that cooperates with the map only through Compose's ordinary
consumption rules needs no support from this design: the map's gestures attach
as a modifier, and the caller's own `pointerInput` composes with them under
Compose's existing ordering.

## Trackpad primitives in Compose

The pinned Compose Multiplatform provides two routes to trackpad gestures,
verified against the 1.11.1 artifacts.

**Scroll.** A two-finger trackpad scroll and a mouse wheel are the same
`PointerEventType.Scroll` event with `PointerType.Mouse`, with no source flag.
The data differs: a trackpad reports fractional deltas on both axes in high-rate
bursts, and a wheel reports whole detents on one axis. `ScrollNotches.kt`
already normalizes each host's delta to notch units. Classifying a scroll as
continuous (trackpad) or discrete (wheel) is a heuristic on those values. The
prior art is `ScrollZoomHandler.wheel()` in MapLibre GL JS, inherited from
Mapbox GL JS: a nonzero `deltaY` that is an exact multiple of Chromium's wheel
increment, 4.000244140625, is a wheel. A magnitude under 4 is a trackpad, and an
ambiguous delta classifies by inter-event timing, with a 40 ms deferral for a
lone event. Compose's own web target added a similar Chrome heuristic in 1.12.
`ScrollEvent` carries the classification, and scroll bindings filter on it:
continuous scroll pans, discrete scroll zooms, and a user who disables the pan
falls back to zoom for both.

**Platform-recognized gestures.** `PointerEventType.ScaleStart`, `ScaleChange`,
`ScaleEnd`, `PanStart`, `PanMove`, and `PanEnd` exist in `commonMain`, with
`PointerInputChange.scaleFactor` and `PointerInputChange.panOffset`, behind
`ComposeUiFlags.isTrackpadGestureHandlingEnabled`. Emission is per platform:

- **Android** emits them for platform-recognized trackpad gestures on API 34 and
  later, and reports trackpad pointers as `PointerType.Mouse`.
- **iOS** emits them: the `ui-iosarm64` artifact wires
  `UIPinchGestureRecognizer` and `UIPanGestureRecognizer` into the `Scale` and
  `Pan` events for indirect (trackpad) input. Precise indirect pinch on iPadOS
  requires the `UIApplicationSupportsIndirectInputEvents` Info.plist key;
  without it, UIKit drives the recognizers with simulated touches, which arrive
  as two touch pointers and hit the two-finger recognizer instead.
- **Desktop AWT** emits none of them: no AWT bridge class touches `scaleFactor`
  or `panOffset`, AWT surfaces no magnify events, and JetBrains tracks native
  trackpad support as CMP-1610. An alternative host such as Nucleus can
  synthesize them, because the scene layer accepts these event types from any
  host.
- **Web** emits none of them, but browsers report trackpad pinch as a wheel
  event with the Ctrl modifier. A scroll binding filtered on Ctrl and mapped to
  zoom is trackpad pinch on web.
- **Trackpad rotate** has no primitive on any platform.

The arena maps `Scale` and `Pan` events into the same pinch and drag event
streams as two-pointer recognition, so a binding never distinguishes a pinch of
two touch pointers from a platform-recognized trackpad pinch, and a platform
that adds these events later needs no public API change.

## Attachment

`MaplibreMap(state)` takes `gestures: MapGestures = MapGestures.Standard` in
place of a `gestureOptions` parameter. The parameter is the only public
attachment in 0.16. A public `Modifier.mapGestures(state, gestures)` can be
extracted later without breaking the parameter form, so it waits for demand.

## Milestone coverage

- **#230**: bindings cover key assignments, mouse conventions (Mapbox, Google,
  and Apple styles are each one builder edit), per-gesture pointer-type filters,
  quick-zoom direction, decay. The device gaps (trackpad scroll-pan, trackpad
  pinch, tilt velocity, Shift-drag box zoom) become new recognizers and built-in
  bindings in the new model, within the platform limits in
  [Trackpad primitives in Compose](#trackpad-primitives-in-compose).
- **#951**: the hover event on the map, plus opt-in per-layer hover.
- **#952**: the double-tap chain, with the camera zoom as its fallthrough.

## Implementation sequence

These phases target 0.16 and use the ownership API.

1. **Value model.** Events, filters, bindings, actions, `MapGestures`, in
   `commonMain`, referencing no map state type.
2. **Arena rewrite.** `MapPointerGesture` reads bindings instead of
   `GestureOptions` and inline conditionals. Behavior-identical at `Standard`,
   verified against the existing `commonTest` and `liveMapTest` suites,
   including the token-ordering tests. `GestureOptions` is deleted. Still
   against the internal `GestureTarget` interface.
3. **New events.** Hover, double tap, long press as events with user actions
   (#951, #952), before layer dispatch is added.
4. **Wiring.** The dispatch chain moves the layer walk out of `MaplibreMap.kt`
   into delivery against the state-owned composition host; `state.gestureCamera`
   becomes public; `MaplibreMap` takes `gestures`.
5. **Device work.** Trackpad scroll-pan through the continuous-scroll heuristic,
   trackpad pinch through Ctrl-scroll on web and the `Scale` events where a
   platform emits them, tilt velocity, and Shift-drag box zoom, as recognizers
   and bindings in the new model. Trackpad rotate waits for a platform
   primitive. The demo iOS app gains `UIApplicationSupportsIndirectInputEvents`
   in its Info.plist, which it does not set today.

## Open questions

### Drag start predicates and asynchronous hit tests

A press-time hit test suspends, and the slop window is time-sensitive. The
predicate may need to be synchronous against pre-queried state, or the arena may
hold the contended drag until the query answers. A prototype decides.

### Naming

`MapGestures`, binding and action class names, and `state.gestureCamera` are
placeholders. The final names follow the `MapState` naming in the ownership API.

### Dispatch latency bounds

The double-tap fallthrough waits on one `queryRenderedFeatures`. If a style
makes that query slow, the zoom lags. A timeout that fires the fallthrough and
delivers a late consume as a no-op is the likely answer; a prototype measures
whether it is needed.
