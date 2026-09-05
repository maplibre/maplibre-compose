# Gesture redesign for 0.16

Implementation specification for the
[gestures milestone](https://github.com/maplibre/maplibre-compose/milestone/16):
[#230](https://github.com/maplibre/maplibre-compose/issues/230),
[#951](https://github.com/maplibre/maplibre-compose/issues/951),
[#952](https://github.com/maplibre/maplibre-compose/issues/952), and
[#1201](https://github.com/maplibre/maplibre-compose/issues/1201).

This design uses the ownership API: `MaplibreMap(state)` and the camera
operations on `MapState`. `MapGestures` replaces `GestureOptions` without a
compatibility layer. Names and contracts below are implementation decisions, not
alternatives awaiting a spike. The code fragments show the target API; they do
not describe APIs already shipped.

## Scope and architecture

Callers configure bindings, responses, observation, and supported thresholds.
Recognizers and competition remain internal. There is no recognizer SPI, public
arena, host-input SPI, or public middleware system in 0.16.

```text
configuration -----------------------> candidates and competition
raw input -> host normalization -> arena selects binding -> action delivery
                                                          1. observer
                                                          2. response
```

One attached input node owns pointer recognition, key/rotary handling, pending
click delivery, hover membership, and gesture-camera lifetimes. It uses one
`pointerInput` event loop plus key and rotary handlers. Recognition math is
pure; Compose event passes and map ownership stay in the node. Several
simultaneous components of one two-pointer transform share a camera session.

`MapState.events` remains the engine-event stream. Input observation is
delivered synchronously through the selected binding, so an application can stop
camera following before the first pan command. It does not require collecting
engine events or forwarding camera commands from application code.

## Public configuration and attachment

`MaplibreMap` takes `gestures: MapGestures = MapGestures.Standard`. This is the
only public built-in attachment in 0.16. A public `Modifier.mapGestures` is
deferred. The public modifier on `MaplibreMap` remains an ancestor of the
internal input node and can cooperate using normal Compose input passes.

`MapGestures` is an immutable builder-created class, not a data class.
`MapGestures { }` edits Standard. `MapGestures.None` has no pointer, click,
hover, key, or rotary handling. `MapGestures(from = value) { }` edits a supplied
value. Layer handlers do not override an explicit None or a disabled event
family. The existing PositionLocked, RotationLocked, and ZoomOnly presets return
as named values; AllDisabled becomes None. Presets lock camera responses, while
None turns off all input handling.

The builder has named slots: `dragPan`, `dragRotateTilt`, `pinchZoom`,
`twoFingerRotate`, `twoFingerTilt`, `quickZoom`, `scrollPan`, `scrollZoom`,
`ctrlScrollZoom`, `tap`, `doubleTap`, `longPress`, `twoFingerTap`, `hover`,
`boxZoom`, and `keys`. A slot exposes its filter, action, observers, and
applicable recognition/response scalars. Set `enabled = false` to remove its
participation. Setting a tap-family `cameraAction = null` removes only its
camera fallthrough. The `dragPan` slot handles both one-pointer pan and the pan
component of two-pointer input, including platform Pan events. Scroll pan has
its own slot. Each slot has `filters: List<PointerFilter>` with OR semantics.
The `filter` convenience setter replaces the entire list with one filter; it
does not append to Standard's alternatives. An empty filter list disables the
slot. Standard rotate/tilt uses two filters, not a recognizer exception.
Map-level handler parameters default to null, so capabilities distinguish no
handler from a handler that deliberately returns Pass. An enabled family with no
handler, observer, or camera response has no recognizer demand.

Examples of the chosen builder surface:

```kotlin
val gestures = MapGestures {
  dragPan {
    startSlop = 5.dp
    onStart { controller.stopFollowing() }
  }
  dragRotateTilt {
    filter = PointerFilter(
      button = PointerButton.Primary,
      modifiers = ModifierFilter.Containing(KeyModifier.Alt),
    )
  }
  doubleTap {
    onEvent { event -> handleDoubleClick(event) }
    cameraAction = null
  }
  quickZoom { direction = QuickZoomDirection.UpZoomsIn }
  keys { clearZoom() }
  drag(id = "selected-handle", filter = PointerFilter()) {
    canStart { press -> selectedHandleContains(press.screenOffset) }
    action = DragAction.Custom { event -> editSelectedHandle(event) }
  }
}
```

Custom bindings require a stable caller-supplied string ID. Built-in slot IDs
are reserved. Duplicate custom IDs fail construction. Adding a custom binding
prepends it, so the last declared custom binding has highest priority. Editing a
named slot changes it in place. Disabling and later enabling a named slot
restores its original priority. Lists, filters, and scalar values are copied at
build time; no caller-owned mutable collection is retained.

### Filters, precedence, and transitions

`PointerFilter` has `pointerTypes: Set<PointerType>?`, `button: PointerButton?`,
and `modifiers: ModifierFilter`. Null pointerTypes or button means no
restriction. The defaults are any reported pointer type, primary contact, and
any modifiers. For matching only, a touch/stylus contact counts as primary;
event metadata still reports no physical mouse button. A button restriction on a
scroll binding requires that physical button to be pressed; scroll slots default
to null. A buttonless wheel does not match an explicit primary-only scroll
filter.

`ModifierFilter.Any`, `Exactly(...)`, and `Containing(...)` have distinct
semantics. Exactly requires the complete modifier set; Containing requires its
members and permits additional modifiers. Key chords use exact modifier
matching. Pointer-type filters apply to all contacts participating in that
candidate. They describe the type reported by Compose, not guaranteed physical
device identity.

Within a recognizer family the first eligible binding wins. Eligibility includes
its filter and a successful synchronous `canStart`, where present. A selected
drag candidate reserves that family's response while waiting for its own slop; a
lower-priority drag cannot win merely by having a smaller slop. Reservation does
not consume motion before recognition. Tap, long press, quick zoom, and
multi-pointer recognition still compete according to the rules below.

Standard single-pointer priority is quick zoom for a paired touch press, mouse
secondary/Ctrl rotate-tilt, mouse Shift box zoom, then pan. Rotate-tilt uses
Containing(Ctrl) for primary mouse input or any modifiers for secondary mouse
input. Box zoom uses Containing(Shift). Thus Ctrl+Shift retains rotate-tilt. Pan
accepts remaining modifiers. Custom drag bindings precede these defaults; a
successful custom reservation disables quick-zoom and camera-drag candidates for
that contact, while tap/long press can still win before motion crosses slop.

Re-evaluate filters on a mouse button/modifier change. If selection changes,
Cancel the old binding without momentum, discard its tap eligibility, and select
a new candidate at the current position. Re-evaluate canStart at that position.
The new candidate must cross its slop from there before Start. This preserves
Ctrl-release-to-pan with an intentional fresh slop rather than transferring a
rotate delta into pan. Custom tools receive Cancel, not End, when this happens.
Touch pointer-count changes follow the component rules below.

### Recomposition and identity

A structural configuration key contains binding IDs/order, enabled families,
filters, scalar values, action kinds, and explicitly configured handler
presence. It excludes callback and predicate instances and dynamic layer
subscriber capabilities. Snapshot dynamic capabilities at the first press for
that contact sequence; subscriber changes never restart an in-progress arena.
Changing that key cancels the current arena and its sessions, then starts a new
one. A new arena waits for contacts already down to lift; it does not interpret
a mid-drag Move as a fresh press.

Callbacks and predicates are held separately in updated state and addressed by
binding ID. Callback-only recomposition updates subsequent delivery without
restarting input. A predicate update affects the next selection, not an already
reserved drag. Public MapGestures equality includes callback identity; it is
never the handler-blind pointerInput key. Do not place an object whose equality
ignores handlers inside rememberUpdatedState and expect handler replacement.

Each event uses one captured callback set for that delivery. Later events use
the latest callbacks, including terminal events. Applications retain any gesture
resource across recomposition in remembered state keyed by the event's ID.

## Events and action observation

Public events are immutable values in commonMain with internal constructors.
`GestureEvent` supplies a per-node monotonically increasing `gestureId: Long`
and `uptimeMillis: Long`. The ID identifies an action's input lifetime, not a
camera token. Pointer events additionally supply map-local
`screenOffset: DpOffset`, optional `position: Position?`, reported
`pointerTypes`, physical `buttons`, and `modifierKeys`. Key and rotary events
have their own input metadata; they do not fabricate pointer coordinates or
geographic positions.

Offsets and linear deltas are in dp relative to the full map viewport's top-left
corner, before camera padding. Multi-pointer anchors are the selected contacts'
centroid. Projection uses the available viewport snapshot before that event's
camera response. It is not a guarantee of the eventual asynchronously rendered
camera. Missing geographic projection does not suppress otherwise valid input;
existing viewport readiness still gates camera commands.

Discrete events are `TapEvent`, `DoubleTapEvent`, `LongPressEvent`, and
`TwoFingerTapEvent`. Continuous `DragEvent`, `PinchEvent`, `RotateEvent`,
`ShoveEvent`, and `ScrollEvent` each have Start, Delta, End, and Cancel
variants. Start includes the original press/anchor and current location. The
first Delta contains displacement beyond slop; slop is not applied as a camera
jump. Subsequent deltas are incremental. Pinch deltas are positive
multiplicative scale factors, rotation deltas are degrees, shove deltas are
vertical dp, and linear velocity uses
`ScreenVelocity(xDpPerSecond, yDpPerSecond)`. Angular velocity is
degrees/second; zoom velocity is zoom levels/second. End carries the last valid
location and release velocity. Cancel carries the last sample and a reason,
without velocity.

Every started binding receives exactly one End or Cancel. A candidate cancelled
before recognition emits neither. End means the binding's input component
completed, including losing required contacts when other contacts remain; camera
momentum may still run. Cancel never commits a custom drag or launches momentum.
Cancellation reasons are input consumption, input cancellation, binding change,
configuration change, camera takeover, and detachment. Loss of a custom drag's
single-contact requirement is a binding change. Replacing a binding cancels it;
normal release of a transform component ends it. Callback failures clean up the
session and propagate through the existing coroutine error handling; they do not
convert failure to Pass or trigger a second terminal callback.

A continuous binding has `onStart`, `onDelta`, `onEnd`, and `onCancel`
observers, all non-suspending Unit callbacks. Delivery invokes the observer
before its response, then rechecks session validity. Observers cannot consume or
replace the response. `DragAction.Custom` replaces camera behavior and receives
the same lifecycle. Built-in actions are Pan, RotateTilt, Zoom, and BoxZoom;
transform bindings use their corresponding camera action.

A pan observer fires only when a selected pan component actually crosses pan
slop, not when anchored zoom changes the camera target. For two-pointer pan,
measure centroid displacement, not individual finger displacement. A pure
symmetric pinch therefore never starts pan. If pan starts and input later
becomes a shove, the earlier pan was real; it is cancelled on the transition to
tilt. Observers are binding-local; a map-wide input event bus is outside this
release.

## Recognition and Compose consumption

Compose propagates consumed changes to other nodes. Our arena must explicitly
honor consumption; this is not supplied by pointerInput itself. Built-in Compose
detectors implement the same cooperative protocol. See the
[Compose consumption contract](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures#event-consumption).

The arena processes Main, rejects already-consumed relevant changes, and checks
Final for external consumption that occurred later in Main while it was still
waiting to claim motion. Track IDs consumed by the arena in that event so its
own consumption is not mistaken for external takeover. Internal simultaneous
recognizers share one arena decision; they do not cancel each other by reading
their own consumed changes.

A consumed down cannot start map input or engage focus. A consumed tracked move
or up cancels its affected candidates/actions and pending tap/long-press work.
Do not restart a cancelled contact until it lifts. Consuming a tracked contact
in a two-pointer group cancels that transform group. Unrelated hover, key,
rotary, and later scroll streams remain usable. A coroutine/input cancellation
or attachment disposal invokes the same cleanup. When a new input actually takes
camera authority, the old camera actions are cancelled as specified below.

Claim touch/stylus down when the enabled tap/long-press family accepts it, as
Compose click detectors do, to prevent parent clicks and long clicks. Down
consumption by the map does not pre-claim all later movement: unclaimed motion
still participates in Main/Final competition with a scrollable parent. A
drag-only configuration claims only after slop. None and disabled/unmatched
families claim nothing. A map click's later ClickResult.Pass does not unconsume
its Compose press/up; feature dispatch and Compose propagation are separate
contracts.

Ordinary Compose consumption is supported. Nested-scroll delta sharing is not
part of 0.16. An ancestor can deliberately intercept in Initial; descendants and
ancestors otherwise compete using normal Main/Final ordering.

### Touch competition and thresholds

Retain the existing two-finger scale/rotate/shove classification and pressure
filtering equations in GestureMath, except for centroid-based pan onset above.
The built-in competition policy stays private; there is no scale/rotation
interlock option. Recognition tuning is per selected binding, not taken from an
arbitrary first binding sharing a recognizer.

Expose `startSlop` for pan (4 dp), `startSpanSlop` for pinch (7 dp),
`startAngle` for rotation (3 degrees), and `startSlop` for shove (16 dp). Mouse
drag slop is 3 dp. Each accepts finite nonnegative values. Defaults for minimum
two-finger span, pressure stability, shove angle, and rotation/scale speed
rejection remain the current GestureMath values, without new public knobs. Touch
double-tap slop remains 100 dp; mouse pairing uses mouse click slop. Timing
comes from LocalViewConfiguration, including minimum/maximum double-tap interval
and long-press duration. Do not invent accessibility timeout overrides.

Two-pointer pan can coexist with scale or rotation, but not shove. Entering
shove cancels pan/scale/rotation components. Entering rotation cancels scale;
scale can restart after the existing 75 dp additional-span rule. Adding or
lifting a pointer rebases the selected pair, centroid, and velocity tracking
before any new delta. A single-pointer custom drag cancels when a second contact
joins and the group is suppressed until all contacts lift; it never hands an
unfinished edit to pan. For camera input, one-to-two pointer transition Cancels
the single-pointer binding without momentum and starts eligible transform
components after their thresholds. With three or more contacts retain the two
oldest eligible contacts; unselected contacts do not create additional
transforms. When a selected contact lifts, End components requiring it exactly
once. If two contacts remain, rebase the replacement pair and recognize fresh
components. If one remains, it can recognize a new pan. Stage ended components'
continuation until the entire contact group lifts. Any newly recognized camera
movement, including movement from a replacement pair, discards that staged
continuation. Merely lifting the remaining contacts starts the staged
continuation once. A new component uses a new public gesture ID, but components
within the same contact group share camera ownership until the group and its
continuation finish.

### Custom drag claiming

`canStart` is synchronous and non-suspending. It examines known handles,
application geometry, or previously queried state. There is no asynchronous
press-time query, speculative camera movement, or buffered press awaiting a
rendered-feature result. A false result lets the next binding compete.

A selected handle can be dragged on its first press when its screen geometry is
known. Arbitrary rendered features must first be selected using tap dispatch,
then edited through known selected handles. This limitation is explicit. Custom
actions own their preview and final application-model update.

### Momentum and eased responses

Existing screen-space pan integration, pressure filtering, and released-finger
handoff are retained. Pan `Fling` exposes `minimumSpeed` (1000 dp/second),
`baseTime` (150 ms), and `durationScale` (1). Duration is
`(speed / 10.5 + baseTimeMillis) * durationScale`; travel remains
`velocity * durationSeconds * 0.28`. Apply small screen-space steps as today.
This allows StreetComplete's threshold/base-time tuning without changing units.

Touch-pair pinch, quick zoom, and rotation retain the current GestureMath
continuation eligibility and velocity equations, exposing a `durationScale` on
each binding. Their default scale is 1; clamp resulting non-pan continuation
duration to 300 ms. Expose that `maximumDuration` on these bindings. Tilt adds
angular continuation: minimum pitch speed 5 degrees/second, duration 150 ms,
linear velocity decay to zero; clamp at camera pitch limits. Expose minimumSpeed
and duration. Setting `continuation = null` disables any continuation. These
values are finite and nonnegative; zero duration means no continuation. Continue
testing the separately reported pinch-momentum regression; configuration is not
itself a fix.

Scroll receives no library fling: OS momentum is already part of its stream.
Discrete key/tap easing keeps the current duration (300 ms) and system motion
scale behavior; zero system duration jumps. A new input owner cancels an old
ease/continuation before its own first command. Never continue after Cancel.

### Anchors, presets, and box zoom

Zoom and rotation bindings expose `anchor: GestureAnchor`, with Input or
ViewportCenter. Input uses the event's contact/centroid; no supplied input
anchor uses the viewport center. Quick zoom and keyboard actions default to the
viewport center; pinch, two-finger rotation/tap, and double tap default to
Input.

PositionLocked disables dragPan, scrollPan, boxZoom, and keyboard pan, and sets
all zoom/rotation anchors to ViewportCenter. RotationLocked disables mouse
rotate/tilt, two-finger rotate/tilt, and corresponding keys. ZoomOnly combines
those restrictions. Disabling dragPan alone is not a position lock; named
presets provide the complete policy across new bindings.

Two-finger tilt uses `pitchDegreesPerDp = -0.1`, multiplying vertical Shove
Delta. This intentionally removes the old physical-pixel density dependence.
Mouse rotate/tilt keeps 0.8 bearing degrees/dp and -0.5 pitch degrees/dp. Public
deltas and response scalars never depend on physical pixels. Keep existing
keyboard/zoom steps and quick-zoom range defaults from GestureOptions. Validate
finite signed sensitivities; direction inversion is allowed.

BoxZoom draws a noninteractive selection rectangle in map-local dp. It uses the
standard mouse drag slop, then updates from press to current location. On End,
clear the preview and require width and height at least 8 dp. Project all four
corners against the current viewport snapshot; if any cannot be projected, end
without a camera command. Otherwise unwrap longitudes around the current camera
target, form the enclosing bounding box, and use the attached bounds-fit helper
with current bearing, pitch, and padding and standard 300 ms easing. The camera
operation stays in its gesture session; it must not call a public bounds-fit API
that retries across attachments or takes over its own session. Cancel only
clears the preview. There is no fit or commit on cancellation.

## Tap-family delivery

The order for a recognized event is:

```text
binding onEvent -> map handler -> interactive layers, front to back
                -> unhandled map handler -> optional camera action
```

Binding, map, and layer handlers return ClickResult. Consume stops the chain.
Handlers remain non-suspending; only feature queries suspend internally. The map
parameters are onClick, onDoubleClick, onLongClick, onTwoFingerClick, and
onUnhandledClick. The latter is the ordinary-tap fallback and also returns
ClickResult. Other event families have no unhandled-map convenience in 0.16. A
map handler that must query asynchronously consumes synchronously and owns its
own result; it cannot retrospectively Pass back into the built-in chain.

Layer composables retain onClick/onLongClick and gain onDoubleClick and
onTwoFingerClick. Each tap-family query uses the actual loaded style order, not
composition order. Layer registration supplies recognition capabilities to the
input node without putting layer knowledge into binding classes. Compute whether
a second touch tap is useful from enabled double-tap/quick-zoom bindings and
their complete dispatch paths, including layer subscribers. Snapshot that
eligibility at the first press; subscription changes apply to the next contact
sequence.

Standard double tap falls through to ZoomIn at the event anchor; mouse Shift
inverts it. Two-finger tap falls through to ZoomOut. A layer or application
consumer suppresses those camera actions. There is no timeout, speculative zoom,
or late-consumption fallback. No relevant layer subscribers means no feature
query.

Touch single taps wait only while a second tap could still be used. Mouse keeps
the current eager first click; a later double click does not undo it. This is an
explicit retained platform convention, not an assertion that click and double
click are mutually exclusive for mouse. A consumed first mouse click does not
consume the later DoubleTapEvent. Secondary mouse click routes to LongPressEvent
with secondary-button metadata; touch long press fires at its timeout, not on
up. Quick zoom takes the paired touch press when it crosses vertical scale slop;
once it starts there is no tap or double-tap delivery from that pair.

Capture each dispatch's attachment, loaded-style generation, and candidate layer
IDs in loaded front-to-back order. Query those layers sequentially, skipping
ones without a handler. After each suspension, cancel if attachment or loaded
style generation changed; skip removed/replaced registrations and use the latest
handler on surviving registrations. New layers do not join an already queued
click. Detach, close, or structural gesture reconfiguration cancels delivery.
Application mutations between callbacks are checked before the next stage.

Dispatch events are processed in recognition order in one per-node queue, while
continuous input remains independent. A newer accepted input press, scroll, key,
rotary, custom-camera session, or ordinary public camera mutation immediately
invalidates older pending camera fallthrough. Increment that generation even
when no gesture session is currently active. It does not erase already
recognized clicks or their layer callbacks. A click chain without camera
eligibility still completes application dispatch. Acquisition of a camera
session after a query is conditional on the captured input generation still
being current. Cancellation never means Pass.

Add `hitPadding: Dp = 0.dp` to interactive layer registration for all tap-family
queries. Zero uses a point; positive padding uses its enclosing square DpRect.
Keep front-to-back priority, including features within a layer as returned by
the engine. There is no circular hit test or nearest-feature sorting promise.
Hover uses exact points, not hitPadding. This and onUnhandledClick are included
in the dispatcher implementation rather than requiring another API redesign.

## Hover

Hover is observation, not a consumable tap chain. `onPointerMove` on the map and
`onHover` on layers receive HoverEvent.Enter/Move/Exit and return Unit. Map
hover requires no feature query. It observes mouse/stylus hover only while no
contacts are pressed; entering drag clears layer membership. Touch has no hover.

Membership is per layer, not per feature. One hover sampling pass queries
subscribed layers sequentially in loaded front-to-back order, using one layer ID
per point query, and then publishes that pass's membership. The pinned native
QueriedFeature contains source IDs but not rendered style-layer IDs, so one
combined query cannot recover layer membership. Do not add an upstream query API
dependency for this optimization. A layer enters when its result becomes
nonempty, receives Move while nonempty, and exits when empty. There is no
per-feature identity requirement. Camera/style presentation changes schedule a
new sample at the last hover location even when the pointer is stationary.

At most one hover sampling pass is in flight. Start passes no faster than frame
rate; keep only the newest pending position. Apply a completed sample if its
attachment, style, and registration generations remain valid, even if a newer
move is pending, then query the newest position. Discarding every superseded
sample would starve hover under continuous movement. Exit, pressed input,
removal, disabling hover, and detach clear membership and invalidate results.
Send Exit once to each previously entered registration, using its last known
sample and callback if that registration was removed. A replacement registration
starts outside. Map exit does not require a successful geographic projection.

## Gesture-camera lifetime

Keep GestureTarget and GestureContinuation internal. `MapState.gestureCamera`
exposes `withGesture`, a suspending scoped operation, and `GestureCameraScope`
provides moveBy, scaleBy, rotateAndPitchBy plus their awaiting eased variants.
The scope's deltas use dp, multiplicative scale, and degrees, with optional
DpOffset anchors. Camera padding/constraints are applied by the existing camera
implementation. The scope exposes no raw token or independent begin/end methods.

`withGesture` requires a currently attached, presentable viewport and throws
IllegalStateException otherwise. It does not wait for a future attachment or
replay old input on a new surface. Scope acquisition cancels the previous camera
owner and invokes the block in the caller's coroutine context. Non-suspending
commands enqueue without waiting for rendering; eased variants await completion.
withGesture returns Unit. Its block runs in a session child job registered
before it starts. The outer caller awaits the child and completion fence;
session takeover cancels only that child and returns normally after cleanup, so
an application's outer input loop can await another gesture. Caller cancellation
propagates into the child and still propagates to the caller. Block exceptions
propagate after cleanup. Same-state nested withGesture calls are rejected before
acquiring authority, preventing self-cancellation, including A-to-B-to-A
nesting. Nested sessions on different states are allowed. The block can
encompass the application's own pointer loop when using None. No public method
accepts a caller-created gesture ID as camera authority.

A per-state coordinator issues monotonically increasing private session IDs,
bound to one attachment generation. Built-in concurrent transform components use
one session; an independent key, scroll, or custom session takes over rather
than mixing authority. A new accepted pointer down interrupts existing camera
continuation immediately; camera gesture state begins only upon recognition.
No-op press does not report a camera movement. When key, scroll, custom input,
or a programmatic camera mutation takes over, Cancel old pointer-camera
components and suppress their still-down contacts until all lift. A later Move
cannot steal authority back. Ordinary public MapState camera mutations revoke
active gesture authority and participate in the same command ordering; a new
gesture cancels preceding programmatic easing. Internal gesture helpers use the
attached adapter, bypassing public takeover entry points. A public mutation is
not confused with the gesture's own commands.

Check scope activity and attachment on enqueue AND on execution at the map
owner. Existing native tokens protect completion bookkeeping but do not reject
stale commands; the implementation must add that rejection to both native and JS
command paths. Takeover or cancellation revokes the session immediately, cancels
its coroutine/continuations and waiting eases, and drops its queued commands.
Older cleanup cannot close a newer session.

Normal completion differs from cancellation. Seal the scope against further
enqueues, drain commands already accepted in order, then close its camera token
with an ordered fence. withGesture awaits that fence before returning. Built-in
End can launch its configured continuation under the same session; seal only
when that continuation finishes. Commands accepted before a normal End must not
be dropped by an immediate validity flag change. Retained scopes reject new
calls after sealing, cancellation, or detachment. A command already executing
when cancellation occurs may complete; subsequent queued commands cannot
execute.

Observers/custom handlers run outside the map-owner command loop. After a
callback returns, check authority again before the response. If it
closed/detached the map or acquired another camera session, no old command is
issued. Use try/finally for balanced cleanup when callbacks throw or their
coroutine is cancelled.

## Keyboard, rotary, and focus

Retain the focus contract from #1260 and the Wear crown support from #1259.
Focus and keyboard engagement are distinct. Tab or a host FocusRequester can
focus the map without engaging it; direction keys then pass through for focus
traversal. Enter, NumPadEnter, or D-pad center engages a focused map. Escape
disengages; Back disengages only if the latest engagement was by key. An
accepted pointer press requests focus and engages by pointer only when keyboard
camera bindings exist. It never claims Back, so ordinary Android navigation
continues. A consumed press cannot request focus or engage.

Engage/disengage chords are configurable members of `keys`; validate they do not
overlap camera chords. The Back action retains its key-engagement guard when
rebound. Removing the last keyboard camera binding disengages immediately and
removes the engagement-key handler, even if rotary remains enabled. Focus loss
disengages and clears claimed-key state. Neither pointer input nor a rotary
binding alone creates keyboard engagement.

The keys builder assigns pan, zoom, rotate, and tilt actions to exact key
chords, including repeats. Standard uses unmodified arrows for pan, Shift+arrows
for rotate/tilt, and Plus, Equals, or Minus for zoom; include Shift+Plus and
Shift+Equals aliases so keyboards that produce '+' via Shift continue to work.
Callers can assign Ctrl/Cmd chords. Keep keyboard pan/zoom/angular steps from
GestureOptions. A new camera key action interrupts the prior ease; only
key-down/repeat applies a step. Track each claimed physical key until release.
Consume that key's KeyUp even if modifiers or engagement changed after KeyDown;
it performs no camera step. Repeated engagement or exit keys remain claimed
until release. When configuration changes while keys are held, stop their repeat
actions but keep a release-only handler until claimed keys lift or focus is
lost. This also applies when switching to None; None starts no new handling but
completes previously claimed releases.

Rotary configuration lives in `keys.rotaryZoom` but is independent of keyboard
engagement and pointer scroll slots. Standard enables it with the existing 0.15
zoom levels per notch and 200 ms burst hold. When migrating call sites from
GestureOptions, configure both pointer scroll zoom and rotary zoom from the old
isScrollZoomEnabled intent; new callers can disable either separately. A valid
host-supplied positive, finite rotary pixels-per-notch is required for rotary
focusability. A focused rotary binding consumes nonzero finite
verticalScrollPixels and applies
`zoomDelta = -verticalScrollPixels / rotaryNotchPixels * zoomStep`, anchored at
the viewport center. Zero or invalid samples pass through. Reuse one camera
session through the configured idle hold; a new sample interrupts a previous
continuation, and idle completion adds no momentum. Focus loss cancels the
rotary session and idle job. A rotary-only map retains a focus stop but never
intercepts Enter, Escape, or Back.

Preserve localized map contentDescription and engagement stateDescription, the
public focus-requester path, and the current LocalIndication. Emit focus
interactions only while focused and disengaged; remove that indication while
engaged, when camera movement supplies feedback. Keep MapState's engagement
notification and listener replay behavior. Preserve the Wear demo's focus
request when its map is active and its style is ready. No enabled keyboard
camera or usable rotary bindings means no focus stop, even when pointer bindings
exist. None neither focuses nor engages; it only finishes already claimed key
releases as specified above.

## Host input and scroll normalization

The baseline is Compose Multiplatform 1.12.0 and Nucleus 2.5.12. Handle reported
input once, without also installing a host-native gesture listener.

| Host                  | Route to support in 0.16                                                                                                                                                                                                                                          |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android API 34+       | Classified trackpad Scale/Pan events become pinch/pan component streams; ordinary touchscreen pairs still use recognition. `isTrackpadPinchReinterpretationEnabled` is true in the pinned UI. Do not mutate the global flag.                                      |
| iOS                   | The inspected bridge emits indirect scrolling as Scroll. Support that and ordinary touch pairs. There is no dedicated UIPinch-to-Scale bridge in the pinned iOS sources; no Info.plist change is required by this work. Dedicated indirect pinch is not promised. |
| Desktop AWT           | Scroll input only for trackpads; no native pinch/rotation bridge. Touch works if the host delivers contacts.                                                                                                                                                      |
| Nucleus macOS         | Magnify, rotate, and smart magnify arrive as synthetic Touch pairs; recognize them using the shared pair path.                                                                                                                                                    |
| Nucleus Linux/Wayland | GDK pinch/rotation become synthetic Touch pairs. GTK/X11 has no corresponding trackpad path.                                                                                                                                                                      |
| Nucleus Windows       | Ctrl-wheel/trackpad pinch becomes a synthetic Touch pair; no rotation route.                                                                                                                                                                                      |
| Browser               | Ctrl-wheel drives pinch zoom; other wheel input uses the scroll classifier. Use the retained native WheelEvent for deltaMode/units.                                                                                                                               |

Nucleus syntheses place contacts on either side of the cursor (initially 120
physical pixels away). Shared recognition requires both contacts to reach the
map through Compose hit testing and meet the normal geometry constraints. Near a
map edge or in a narrow map, that pair is not guaranteed to reach the same input
node. No out-of-bounds raw capture or private-ID reconstruction is added; retain
this host-route limitation in documentation and device coverage.

No reliable touchscreen-versus-trackpad filter exists for Nucleus synthetic
Touch pairs. Report the supplied type and do not inspect private pointer IDs.
Compose may split pair press/release into intermediate one-contact events; these
must rebase candidates without spurious tap/pan or losing a continuation.
Nucleus macOS/Linux send normal Release before cancelPointerInput on native
cancellation. The pinned bridge therefore loses the cancellation distinction.
Our End/Cancel contract reflects the Compose stream we receive; it cannot
recover that missing fact. Do not add timing guesses or a second raw listener.
Native cancellation fidelity requires an upstream host fix and is not claimed
for that route. Ordinary Compose cancellation while contacts are down remains
supported.

### Platform-recognized components

The Android adapter uses native MotionEvent classification on API 34+ to tag all
events in a classified pinch/swipe sequence, including ordinary Press/Release
around Scale/Pan. Classified buttonless transforms count as primary contact for
filter matching, like touch, while metadata retains the reported Mouse type and
empty physical buttons. This does not change button matching for ordinary
Scroll. Those wrapper events never enter tap, long-press, or single- pointer
drag recognition. Clear wrapper suppression only after all contacts lift. On
other hosts, a platform Scale/Pan start cancels pending click candidates for its
reported contacts.

ScaleStart/PanStart begin the corresponding component without touch span/slop,
pressure, or finger-angle tests: the platform already recognized the gesture.
ScaleChange supplies incremental positive scaleFactor to PinchEvent.Delta;
PanMove supplies incremental panOffset divided by density to DragEvent.Delta.
The selected binding/action and observers run. Pinch applies the same configured
scale response as touch-pair pinch. A missing start synthesizes Start
immediately before the first valid delta. End terminates the component once;
duplicate end is ignored. Consumed changes or input cancellation produce Cancel.
Calculate component velocity from deltas and monotonic time, without inventing
finger positions or pressure. Platform Scale and Pan have no library
continuation. Pan streams can include OS inertia; Scale supplies no physical
finger velocity/span for the retained touch-pair momentum equation. End reports
measured scale/pan velocity for observation but adds no camera motion. This is a
deliberate host-route difference; actual and Nucleus synthetic touch pairs
retain configured continuation.

Quantized equal timestamps are permitted. When two samples have equal time, rate
is unknown: use cumulative spatial/angular slop and normal geometry/pressure
checks, skipping rate-based rejection for that sample. Keep existing rate checks
for positive time differences; substituting 1 ms can falsely reject ordinary
small-angle rotation. Coalesce equal-time samples for velocity tracking and
report zero release velocity unless there are two distinct sample times. Ignore
negative-time samples and rebase velocity history. Same-tick synthetic
smart-magnify and rotation can cross slop without artificial momentum.

### Scroll routing

Standard orders ctrlScrollZoom, scrollPan for Continuous input, then scrollZoom
for either class. Ctrl zoom uses Containing(Ctrl); it wins before classification
can turn a pinch into pan. The normal bindings accept any remaining modifiers.
Disabling scrollPan leaves both classes zooming. Setting its accepted kinds to
all kinds makes unmodified wheel input pan too. Disabling zoom slots never
silently enables pan. Scroll filters use null button restrictions.

Classify immediately on the first nonzero event and keep the class for a burst.
A burst ends after 200 ms without scroll; expose this idle duration on the
scroll configuration shared by its bindings. Modifier changes Cancel the old
binding and select a new one without a fling; idle completion emits End. No 40
ms wait, delayed consumption, or browser preventDefault after the native wheel
callback. Consume only when an eligible binding accepts the event, synchronously
during Main; zero/unmatched input passes. No library momentum is appended to the
stream. Ambiguous inputs use the explicit heuristic below and users can change
routing through bindings.

Normalize each axis independently before applying the selected action. Positive
normalized scroll moves the content in the negative screen direction. The pan
conversion is a response policy, not a claimed physical wheel distance.
`scrollZoomStep` remains 0.15 zoom levels per notch.

| Host units                            | Camera pan delta in dp               | Zoom notch units |
| ------------------------------------- | ------------------------------------ | ---------------- |
| Browser DOM_DELTA_PIXEL               | `-raw`                               | `raw / 100`      |
| Browser DOM_DELTA_LINE                | `-raw * 100 / 3`                     | `raw / 3`        |
| Browser DOM_DELTA_PAGE                | `-raw * viewportSizeDp` on that axis | `raw`            |
| macOS AWT/Nucleus rotation units      | `-raw * 10 / density`                | `raw`            |
| Windows/Linux/Android rotation units  | `-raw * 40`                          | `raw`            |
| iOS indirect physical-pixel/100 units | `-raw * 100 / density`               | `raw / density`  |

Use Compose scrollDelta for axis values: its web bridge already applied the
Shift-wheel axis swap. Read native WheelEvent only for deltaMode, without
swapping axes again or replacing them with original DOM deltaX/Y. Missing native
metadata falls back to pixel units with the same formulas. The inspected Compose
1.12 web bridge does not divide wheel deltas by density. Remove the compensating
density multiplier from ScrollNotches.js. Use actual deltaMode rather than
keeping the Firefox line-mode TODO. Do not compare Chromium constants to
already-normalized notch values. Zoom uses the component with the greatest
absolute normalized value, with Y winning ties:
`zoomDelta = -component * scrollZoomStep`. This handles horizontal-only fallback
without cancelling opposite-sign diagonal components.

Classify browser line/page as Discrete. For browser pixel units, two nonzero
axes mean Continuous; otherwise nonzero exact multiples of 100 or Chromium's
4.000244140625 increment mean Discrete, and other values mean Continuous. Test
multiples with quotient-to-nearest-integer tolerance 0.000001. On
desktop/Android, fractional raw components farther than 0.001 from an integer,
or two nonzero axes, mean Continuous; other input is Discrete. iOS indirect
scroll is Continuous. The kind is an estimate, not device identity. Integer
trackpad and smooth-wheel streams are intrinsically ambiguous; the binding
overrides above make their behavior deterministic when an application knows its
input environment.

Raw deltas must be finite. Reject nonfinite samples without claiming input. A
modifier change begins a new classification burst. A consumed scroll event
cancels that active scroll binding and clears the burst estimate; the next
unconsumed scroll event begins a new burst. Classification, units, signs,
density handling, and routing are covered by the implementation acceptance tests
below.

## Implementation sequence and acceptance

1. Add public value/builders and internal structural keys according to this
   specification. Compile common usage examples for all targets. Include
   duplicate-ID, scalar validation, priority, and callback freshness tests.
2. Implement the arena's Main/Final consumption and selected-action delivery,
   initially on internal GestureTarget. Run the direct recognition suite in
   androidJvmTest as well as commonTest. Preserve parent click/long-click, tap
   pairing, quick zoom, focus, and token completion behavior, except the
   explicit corrections in this document. Include focus traversal, pointer
   engagement with Back pass-through, key engagement with Back/Escape release
   pairing, callback replay, rotary-only focusability, focus loss, disabling
   bindings during a held key, shifted plus, and Wear crown burst/sign tests.
3. Implement camera-session authority at enqueue/execution and normal-completion
   fences on native and JS. Test takeover with queued commands, delayed
   dispatch, callback-driven detach, and retained scopes. Expose gestureCamera
   only after those tests pass.
4. Wire authoritative tap dispatch, layer capabilities, hitPadding, unhandled
   click, and hover. Delete GestureOptions and old dispatch paths. Test style
   ordering/replacement, slow queries, stationary-pointer updates, and cleanup.
5. Add host normalization, platform Scale/Pan handling, scroll pan, box zoom,
   configurable continuation, and tilt momentum. Treat these as specified
   implementation work, not a later redesign of the value model.
6. Extend the demo with binding/threshold settings, observer-driven pan follow
   cancellation, synchronous selected-handle dragging, and hit-padding examples.
   Run affected target suites with mise; never count a filtered browser run as
   evidence. Validate physical touch/trackpad behavior separately before
   claiming device coverage. The plan is implementation-ready; release
   validation still has to exercise the implemented feature.

Use `mise run test:android` for pure Android-host coverage,
`mise run test:desktop` for JVM recognition and live-map coverage,
`mise run test:js` without --tests, and affected Android-device/iOS tasks. Keep
runtime/UI tests out of commonTest. Existing
androidJvmTest/MapInputRecognitionTest and LayerClickOrderTest are part of the
required suite, not just commonTest and liveMapTest. Run `mise run check` for
implementation changes. Documentation-only edits use the scoped formatter.

Deferred scope is fixed: recognizer/host SPI, public modifier attachment,
arbitrary interlocks, asynchronous drag claims, circular/nearest hit testing,
per-feature hover, and hosts' missing physical gesture primitives. There are no
open API choices in this plan; failures in acceptance are defects to fix against
these contracts, with platform exclusions above kept explicit.

## Evidence and review

Reviewed on 2026-09-05 against checkout e794eaa0 and freshly fetched main
9fa43a43. Main's intervening changes do not alter input behavior. The plan
includes current focus/engagement (#1260), Wear crown support (#1259), and
system motion scale (#1255).

Design verification passed 18 JVM tests covering actual Compose consumption and
callback updates, scene event ordering, real coroutine cancellation, and
isolated camera-authority/queue models; 13 scroll-policy fixtures; and a probe
against actual compiled GestureMath for synthetic-pair timing. Independent
adversarial review converged after correcting the resulting contract gaps.
Temporary prototypes and review scaffolding were removed before the plan PR.
These results establish design feasibility, not production camera or physical-
device coverage; the implementation acceptance tests above remain required.

Primary source references for the pinned host paths:

- [Compose UI desktop 1.12.0 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-desktop/1.12.0/ui-desktop-1.12.0-sources.jar)
- [Compose UI iOS 1.12.0 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-iosarm64/1.12.0/ui-iosarm64-1.12.0-sources.jar)
- [Compose UI web 1.12.0 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-js/1.12.0/ui-js-1.12.0-sources.jar)
- [Android Compose UI 1.12.0 sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.0/ui-android-1.12.0-sources.jar)
- [Nucleus macOS synthetic transforms](https://github.com/NucleusFramework/Nucleus/blob/v2.5.12/decorated-window-tao/src/main/kotlin/dev/nucleusframework/window/tao/scene/TaoComposeSceneHost.kt#L1073)
- [Nucleus Windows pinch](https://github.com/NucleusFramework/Nucleus/blob/v2.5.12/decorated-window-tao/src/main/kotlin/dev/nucleusframework/window/tao/scene/TaoComposeSceneHostWindows.kt#L626)
- [Nucleus Linux native input](https://github.com/NucleusFramework/Nucleus/blob/v2.5.12/decorated-window-tao/src/main/native/src/platform/linux/touch.rs)
