# Map interactions

`MapInteractions` separates camera permissions and response tuning, input
recognition and routing, application event delivery, and camera-session
ownership. This document defines the public contract and implementation
boundaries. [Input fidelity](INPUT_FIDELITY.md) records the recognition,
Compose, host, projection, and ownership constraints beneath the API.

## Goals and limits

- Make ordinary configuration small: disable rotation once, observe pan once,
  and tune momentum without constructing another public settings object.
- Express Apple-like, Google-like, and Mapbox-like conventions through small,
  inspectable mapping tables. Keep runtime callbacks for application behavior;
  do not add a generic recognizer registry or branded compatibility promises.
- Retain meaningful tuning and extension points from issue #230 and the pinned
  [StreetComplete findings](https://github.com/sargunv/StreetComplete/blob/94617dcb7ea53f72cb134532081c168d80e8a244/docs/multiplatform/03-maplibre-compose-upstream.md).
- Keep recognition internal to this module. Do not extract a library, commit a
  non-map probe, add parallel attachment APIs, or redesign app selection state.

## Public shape

```kotlin
MaplibreMap(
    interactions = MapInteractions {
        camera {
            rotate { enabled = false }
            tilt { enabled = false }
            pan {
                onStart { followingLocation = false }
                momentum {
                    minimumSpeed = 250.0
                    baseTime = 500.milliseconds
                }
            }
        }
        bindings {
            scroll {
                mappings {
                    on(modifiers = Containing(KeyModifier.Ctrl)) { zoom() }
                    on(kind = ScrollKind.Continuous) { pan() }
                    otherwise { zoom() }
                }
                zoomStep = 0.15
            }
        }
    },
)
```

The documentation site imports compiled examples from the demo module. Builders
have internal constructors; public configuration values are immutable snapshots.
Nested blocks edit settings, assignments set values, and callback blocks supply
runtime logic. Settings such as momentum use builders throughout; immutable
settings records stay internal.

`MapInteractions { ... }` extends Standard.
`MapInteractions(from = value) { ... }` edits another configuration. An omitted
block inherits its value. Repeated edits to a built-in block update the same
configuration. A `mappings` block replaces that family's complete table; an
omitted table inherits. An empty table removes camera mappings while preserving
application event delivery. Tuning a family does not replace its inherited
table.

`MapInteractions.None` disables all built-in input, including layer clicks and
hover: every family/component is disabled, every camera mapping table is empty,
and there are no custom drags or callback subscriptions. Its camera policy
remains permissive so external app input still works. Enabling a routable family
under None enables app delivery or custom drags without restoring Standard
camera mappings; add those mappings explicitly. Enabling a non-routable
component such as transform zoom enables that component's response. Turning off
camera movement alone does not disable feature interaction. Keep Standard and
None; express PositionLocked, RotationLocked, and ZoomOnly as camera settings
instead of maintaining duplicate whole-configuration preset machinery.

| Block                                                          | Owns                                                                                 |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `camera.pan/zoom/rotate/tilt`                                  | Global permission, default momentum, semantic start observation                      |
| `bindings.drag`                                                | Single-pointer admission, selected response, movement thresholds, app drag extension |
| `bindings.transform.pan/zoom/rotate/tilt`                      | Coordinated pair/host components, component eligibility and thresholds               |
| `bindings.scroll`                                              | One ordered scroll table, burst timing, zoom response gain                           |
| `bindings.tap/doubleTap/secondaryClick/longPress/twoFingerTap` | Tap-family eligibility and fallback camera response                                  |
| `bindings.tapDrag`                                             | Paired tap-and-vertical-drag zoom, direction, gain and anchoring                     |
| `bindings.hover`                                               | Hover eligibility                                                                    |
| `bindings.keys`                                                | Focused key admission, selected key response and step sizes                          |
| `bindings.rotary`                                              | Independent focused rotary admission and zoom tuning                                 |
| `callbacks`                                                    | Map click intents, unhandled click, and hover delivery                               |
| `MapState.withCameraInput`                                     | External app-input lifetime using the same camera authority                          |

## Camera policy and response tuning

Each camera component has `enabled`. It gates every built-in response and
`withCameraInput` operation, after routing and before observation or execution.
Ordinary programmatic camera methods remain independent. Structural policy
changes cancel active input before a replacement configuration takes effect.

- `pan.enabled = false` means preserve the padded camera target, not merely drop
  explicit pan deltas. Zoom and rotation use CameraCenter anchoring; FitBounds
  is unavailable. No individual binding can override this restriction.
- `rotate.enabled = false` removes bearing changes; `tilt.enabled = false`
  removes pitch changes. A combined rotate/tilt response can retain its
  permitted axis.
- A response with no permitted components does not claim input for camera work.
  Existing application click/hover demand can still require delivery.
- FitBounds requires both pan and zoom permission. Disabling either removes it
  from routing; it cannot bypass a zoom lock through a bounds operation.
- Routing skips camera-action mappings whose responses have no permitted
  components; `none()` remains a terminal match. Thus the Standard
  continuous-scroll Pan mapping falls through to Zoom when pan is disabled.
  Primary drag has no unrelated rotation fallback to fall through to.

`camera.pan.onStart` runs synchronously before the first effective pan response
in an input lifetime, including drag, transform, scroll, key, and external pan.
It never fires for incidental anchor compensation, rotation, tilt, or an app
drag. FitBounds is its own operation, not a synthetic pan start. Each camera
component has the corresponding semantic `onStart`; no second semantic Delta/End
stream is added. A small `CameraInputStart` contains session ID and origin
(Drag, Transform, Scroll, Tap, TapDrag, Key, Rotary, External); the component is
identified by the callback receiving it. Components beginning later in a shared
transform session receive their own start with that same session ID. A component
that ends and starts again during contact transitions emits a new start, even if
the camera session is retained; this callback is per component start, not once
per session.

Detailed physical event streams remain on the corresponding input family. They
observe recognized input; camera callbacks observe permitted responses. Do not
forward all raw pointer movement into camera pan observers. Check authority
again after an observer, because an observer may start a programmatic camera
operation.

Momentum is movement after normal input release, not an End callback. Configure
it with `momentum { enabled = false }` or model-specific fields:

| Response          | Fields retained, with existing units and equations |
| ----------------- | -------------------------------------------------- |
| Pan               | `minimumSpeed` (dp/s), `baseTime`, `durationScale` |
| Zoom and rotation | `durationScale`, `maximumDuration`                 |
| Tilt              | `minimumSpeed` (degrees/s), `duration`             |

Defaults live on the camera component. Only `transform.pan/zoom/rotate/tilt` and
`tapDrag` accept local momentum overrides. Single-pointer drag inherits the
camera pan or tilt momentum where the response supports it; mouse bearing
rotation does not add release momentum. It has no ambiguous family-level
momentum block for both Pan and RotateTilt. Every omitted override field,
including `enabled`, inherits. Setting a speed does not implicitly enable
disabled momentum. There is one resolved configuration, not multiplied chains of
gains. Transform overrides apply only to library-recognized pairs, not
host-recognized components. Changing resolved momentum settings cancels input or
momentum using those settings. Cancellation never starts momentum. Scroll and
host-recognized transform streams add no library momentum; they may already
include host inertia. External camera commands do not acquire automatic release
physics.

Input-specific response conversions stay near the input: scroll/key/rotary zoom
steps, tap zoom amounts, drag angular gain, and tap-drag zoom levels per
viewport height. Keep anchor configuration on zoom/rotation responses. Do not
create a unitless global sensitivity that combines incompatible inputs.

## Mapping declarations and recognition lifetimes

Mappings are configuration data built before input arrives. The library must
know which actions exist before recognition: to avoid reserving taps in a
clickable parent, delaying a single tap for an unused double tap, or acquiring
keyboard focus with no usable key mappings. An opaque callback cannot provide
that answer without speculative execution or a separate demand declaration. Use
one small mapping DSL instead of asking apps to describe demand twice.

```kotlin
bindings {
    scroll {
        mappings {
            on(modifiers = Containing(KeyModifier.Ctrl)) { zoom() }
            if (trackpadPans) {
                on(kind = ScrollKind.Continuous) { pan() }
            }
            otherwise { zoom() }
        }
    }
}
```

The `if` runs when building the configuration. `on` stores a typed pattern and a
response; its body runs during construction, not for each event. Rebuilding with
a different table changes structural configuration and cancels obsolete input.
There is no parallel runtime `action { input -> ... }` selector API.

Patterns expose only fields meaningful to their input family: reported pointer
types, logical button, modifier matching, ScrollKind, or Compose Key. Omitted
fields match any supported value, except key-row modifiers: omitted key
modifiers mean `Exactly()` (no modifiers), so an ordinary key binding does not
claim unrelated shortcuts. Specify `Any` explicitly to accept every chord.
Pointer-type sets match the admitted pointer's reported type; they do not
promise trackpad/touchscreen distinction. Modifier values are Any, Exactly, and
Containing; there is no public Boolean filter algebra, arbitrary predicate,
specificity ranking, or generic recognizer registry. Small typed matching values
are arguments; settings such as momentum remain nested builders, without a
second public settings-constructor API.

Rows are considered in declaration order. The first matching row with an
available response wins: `none()` is always available; a camera action needs its
required permissions. Focus actions have the keyboard engagement gate below. A
row body must declare exactly one response; `otherwise` is an optional final
catch-all. `none()` is an explicit terminal match with no camera response,
useful for excluding a chord from a later catch-all; it does not suppress
application click delivery. For drag and scroll, `none()` leaves input unclaimed
by that response for Compose parents. It cannot undo earlier consumption or
another recognizer's claim. Empty tables mean no camera response for that
family. Standard single tap, secondary click, and long press have empty camera
tables. Click subscribers can still create demand for those inputs.

| Table        | Responses                                                       | Selection boundary                                                           |
| ------------ | --------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| Drag         | `pan()`, `rotateTilt()`, `fitBounds()`, `none()`                | Eligible press, with mouse chord reselection below                           |
| Scroll       | `pan()`, `zoom()`, `none()`                                     | First valid delta; changed buttons/modifiers end the burst                   |
| Tap families | `zoomIn()`, `zoomOut()`, `none()`                               | Press-time demand; recognized tap selects fallback retained through dispatch |
| Keys         | Existing pan/zoom/rotate/tilt steps and focus actions, `none()` | Initial key-down; repeats and release retain the selected action             |

Each family has an `enabled` master gate. Routable pointer families also have a
`pointerTypes` pre-filter, applied before custom admission, demand, and table
matching. This allows mouse-only drag without rewriting inherited mappings;
modifiers/buttons/kinds belong only to the rows. Non-routable families such as
tap-drag and hover use the relevant static pattern fields directly. Transform
eligibility uses reported types/modifiers where the host provides them,
requiring all participating reported pointer types to be allowed. Do not invent
host button/contact metadata. Primary logical contact includes ordinary
touch/pen contact; a mouse button pattern requires that button to be pressed.
Multiple pressed buttons can match multiple rows; declaration order still
decides.

Rows only select responses; thresholds, gains, anchors, and local event
observers remain on typed family/component settings, not per-row settings
objects. Key row helpers are `panLeft/Right/Up/Down`, `zoomIn/Out`,
`rotateLeft/Right`, `tiltUp/Down`, `engage`, `disengage`, and `back`, each
called as a function. Retain existing engagement and Back behavior. Standard key
rows retain exact modifier matching so unconfigured system/app shortcuts are not
newly claimed.

Drag selection reserves a candidate; Start and camera claim still wait for slop.
The existing tap/secondary-click/long-press/tap-drag/transform competition
remains internal. Press admission considers custom candidates first, then
tap-drag for an eligible paired non-mouse press when enabled and zoom is
available, then the drag table. Secondary-button click and drag still compete by
movement threshold. Custom predicates run only at press, never at a later chord
change; a built-in drag cannot become a custom drag mid-contact. For built-in
mouse drags, changing buttons or modifiers reruns table matching. If the
selected response is unchanged, continue; otherwise cancel the old response
without momentum and rebase the new candidate at the current position. It must
cross its own slop before Start. If no row or `none()` is selected, cancel the
old response without momentum and stop consuming further changes for that drag.
A later chord change may admit a new built-in candidate; previously consumed
input is not returned to parents. This preserves the existing chord-switch
behavior without exposing built-in binding IDs. Releasing the last button ends
normally; it does not start a replacement candidate. Custom drag admission stays
latched until its terminal event; chord changes do not reinterpret an edit as
navigation. Key repeats cannot acquire a new mapping while held; scroll kind
stays fixed within a burst. App state changes used only by a callback do not
change an active route; put navigation mode changes in configuration data.

Demand uses the same pattern matcher and camera-policy resolution as actual
routing, together with current eligible application subscribers. It never
executes an application callback to predict demand:

- With no eligible click subscribers, an empty/none single-tap camera table adds
  no ordinary-tap consumption. A default map inside a clickable card preserves
  the existing Compose parent behavior.
- Double-tap delay requires an eligible double-click subscriber, a matching
  available double-tap camera response, or available tap-drag pairing. Clearing
  double-tap mappings removes camera demand without disabling app double clicks.
- Keyboard focus/engagement requires enabled keys and at least one reachable,
  permitted camera key mapping. Focus actions alone do not create demand; this
  preserves the current last-camera-chord behavior. A zoom-only table with zoom
  disabled does not create keyboard demand, even if Engage/Back rows remain.
  Rotary contributes independently only when enabled and zoom is available.
- Family `enabled = false` disables recognition and corresponding app delivery.
  Removing camera mappings only removes camera demand. No `availableActions`
  public set or duplicated callback/enablement declaration is needed.

### Callback updates

A subscription is identified by its slot: input family/component and callback
field, custom-drag key, or feature-layer registration. Lambda identity is never
structural. A non-null body replaced by another body receives subsequent events
through the new body. A newly populated slot does not join an existing lifecycle
with Move/End without Start; a removed slot is skipped after removal. For
separate Start/Delta/End callback fields, membership means the field was present
when the lifecycle began; a delta-only observer does not need to register a
Start callback. Retiring hover slots receive Exit through their outgoing body;
new ones receive Enter. Custom-drag removal delivers Cancel through the outgoing
handler before retirement.

Callback presence changes update demand for future presses; they do not restart
recognized input or invalidate in-flight click dispatch. Click delivery records
eligible subscription slots at admission and checks their continued validity at
each stage. If a callback removes itself and returns Pass, other admitted layers
and unhandled delivery can still run. Semantic `camera.pan.onStart` can clear
itself without cancelling the pan. Explicit enablement, pattern/table changes,
and resolved tuning are structural; retain cancellation and stale-query guards
for those changes, attachment/style replacement, and layer registration changes.

## Control-scheme examples

The
[issue investigation](https://github.com/maplibre/maplibre-compose/issues/230#issuecomment-2576090070)
records these desktop conventions. They become configuration recipes using the
same recognizers, not distinct top-level controls.

| Scheme      | Rotate/tilt                           | FitBounds            | Context action  | Double-click zoom out |
| ----------- | ------------------------------------- | -------------------- | --------------- | --------------------- |
| Apple-like  | Alt + primary drag                    | None                 | Secondary click | Alt                   |
| Google-like | Alt/Shift/Ctrl + primary drag         | None                 | Secondary click | Configure explicitly  |
| Mapbox-like | Secondary drag or Ctrl + primary drag | Shift + primary drag | App-configured  | Shift                 |

For example, Apple-like drag and double-click conventions:

```kotlin
bindings {
    drag {
        mappings {
            on(pointerTypes = setOf(PointerType.Mouse),
               button = PointerButton.Primary,
               modifiers = Containing(KeyModifier.Alt)) { rotateTilt() }
            on(button = PointerButton.Primary) { pan() }
        }
    }
    doubleTap {
        mappings {
            on(modifiers = Containing(KeyModifier.Alt)) { zoomOut() }
            otherwise { zoomIn() }
        }
    }
    scroll {
        mappings {
            on(modifiers = Containing(KeyModifier.Ctrl)) { zoom() }
        }
    }
}
```

The final scroll block demonstrates cooperative embedding: unmatched scroll is
left to the parent. The primary drag row still accepts ordinary touch contact.
For an embedded map requiring two-finger touch navigation, restrict
single-pointer drag eligibility to Mouse/Pen while leaving transform pan
enabled. These are independent choices; ordinary tuning or eligibility edits
inherit existing tables. Replacing a control scheme explicitly replaces its
small family table, with no public row IDs, inheritance patches, or
Standard-delegation callback machinery.

Unconsumed input is left to Compose parents. This is not a nested-scroll
delta-sharing API and does not promise recovery of contacts a parent already
consumed. OS/browser shortcuts and host gesture availability retain the existing
documented limits.

## Transform and tap-drag configuration

```kotlin
bindings {
    transform {
        pan { startSlop = 5.dp }
        zoom { startSpanSlop = 7.dp }
        rotate {
            startAngle = 1.5
            allowDuringZoom = false
        }
        tilt { startSlop = 8.dp }
    }
    tapDrag {
        direction = QuickZoomDirection.UpZoomsIn
        zoomLevelsPerViewport = 3.0
        momentum { enabled = false }
    }
}
```

Transform pan is independent of single-pointer drag pan. Components expose
explicit enablement/static eligibility settings and existing typed event
observation. One selected pair supplies coordinated pan, scale, rotation, and
shove; host-recognized components enter after recognition without fake contacts
or duplicate slop.

Default competition and thresholds preserve current fidelity. With
`allowDuringZoom = false`, accepted zoom cancels/suppresses rotation for the
zoom lifetime, including simultaneous threshold crossings; pan can coexist.
Rotation rearms after zoom's terminal event, using a fresh angle baseline. Shove
stays exclusive. This narrow interlock addresses a concrete StreetComplete
requirement; do not expose an arbitrary competition graph.

Tap-drag is the existing paired non-mouse quick-zoom recognition, given an input
name and an explicitly named gain. It does not imply ordinary mouse
click-click-hold support. Retain direction, anchor, and momentum tuning.

## Application handling

Map-wide delivery has one handler for each click kind and hover, feature-layer
handlers, and one ordinary-click unhandled stage. A single `callbacks` block on
MapInteractions configures: `click`, `doubleClick`, `contextClick`,
`twoFingerClick`, and `hover`. Click blocks provide `onEvent`, ordinary click
also provides `onUnhandled`. Feature handlers remain on layers; do not
reintroduce callbacks on MaplibreMap itself. `MapState.events` remains the
engine event flow; `callbacks` describes application interaction delivery.

Single click, double click, context request, and two-finger click are delivery
intents. Standard secondary click and literal long press produce the same
context intent. `bindings.secondaryClick` has its own `enabled`, static
eligibility, and fallback `mappings`, independent of `bindings.longPress`. Both
deliver `ContextClickEvent`; layers expose `onContextClick`. A secondary button
release is not fabricated long-press recognition. Preserve screen and nullable
geographic positions, hitPadding, layer order, and stale-query guards.

Tap-family mappings choose only the fallback camera response. They do not
replace application click delivery. Dispatch remains map handler, layers front
to back, ordinary unhandled handler, then the selected camera response only
after Pass and only while still valid. Disabling a tap family suppresses its
associated input, while clearing its camera mappings merely removes the camera
fallback. Disabling long press does not disable secondary-click context
delivery, or vice versa. Mouse's first ordinary click remains eager; recognizing
double-click cannot retract it. Hover remains non-consuming and preserves
stationary-pointer resampling.

```kotlin
bindings {
    drag {
        custom("edit-handle") {
            canStart { press -> selectedHandleContains(press.screenOffset) }
            onEvent { event ->
                // App-owned preview, delta, commit, and cancellation.
            }
        }
    }
}
```

Custom drags are independent of camera component permissions but acquire the
same input ownership session, so programmatic camera takeover can cancel an
edit. Custom drags have one lifecycle stream and always invoke application
handling; no `DragAction.Custom` switch or parallel camera-response factory.
Matching custom drags are considered in declaration order before the ordinary
drag table. `canStart` and `onEvent` are required; use an explicit true
predicate for an unconditional app drag. Custom candidates first require logical
primary contact; non-primary mouse buttons do not enter their predicates. The
press record exposes modifiers, physical buttons, reported pointer types, and
whether this is a paired second press. A predicate can reject modifiers or a
paired press to retain standard rotate/tilt or tap-drag handling. Custom keys
are nonblank and stable. Editing an inherited key updates that definition in
place; omitted callbacks/settings inherit. A new key must supply both callbacks.
Duplicate declarations of a key within one builder block fail validation.
Adding/removing/reordering keys is structural.

A declined custom candidate permits ordinary map navigation. Removal, mode/key
change, consumption, or camera takeover cancels an active custom drag. A second
contact cancels the edit and suppresses that contact group until all contacts
lift; it does not hand an unfinished edit to transform navigation. App state,
hit regions, selection styling, geometry, preview, commit, and rollback belong
to the application. Keep projection functions available for app hit tests and
edits.

## External camera input

App input uses a scoped camera lifetime:

```kotlin
mapState.withCameraInput {
    controllerDeltas.collect { delta ->
        panBy(delta.xDp, delta.yDp)
    }
}
```

The scope retains the existing three immediate operations (pan, scale,
rotate/pitch) and their three awaiting-transition variants, with matching names
and units. It uses the attached viewport's current interaction camera policy.
Disabled components are no-ops, but arguments must still be valid. No automatic
momentum is added; apps own command production and input lifetime.

Retain child-job takeover cancellation, caller cancellation/failure propagation,
normal-completion draining, invalid retained-scope rejection, same-state nesting
rejection, and attachment/queued-command guards. Policy changes cancel the
session. A newer owner cancels the session's child and the call returns after
cleanup; caller cancellation still propagates. MapInteractions.None remains
compatible with this external-input path. It is not a TransformableState adapter
or a second engine implementation.

## Internal boundaries

```text
Compose/host delivery -> normalization -> candidate admission and recognition
    -> typed response -> camera policy and semantic observer -> session -> engine
                      -> application click/custom-drag dispatch
```

- Use typed immutable family records and small ordered mapping tables. Keep
  fields meaningful to their response; do not introduce public filter algebra,
  reserved built-in string IDs, or behavior selected by string comparisons.
- Keep camera permissions and default response tuning in one record. Resolve
  inherited input overrides once. Check permissions/authority on application
  input too; ordinary programmatic methods keep their independent path.
- Use one typed pattern matcher for demand and routing, with declaration-order
  matching and one typed selected response per active lifetime. Standard is data
  built through the same API. No callback introspection, shadow demand protocol,
  general rule interpreter, or selector-to-filter adapters.
- Separate single-pointer drag settings from transform components. Share pan,
  scale, rotate/pitch, fit, and momentum response execution where semantics
  agree; retain source-specific calibrated conversions and host no-extra-inertia
  rules.
- Compile structural configuration separately from current callback references.
  Preserve outgoing-handler cleanup and per-family release suppression.
- Preserve one click dispatcher and one camera authority. Consolidate redundant
  adapter behavior instead of adding new queues, listeners, or state machines.
- Continue using Compose input passes, consumption, cancellation, velocity
  tools, focus, and rotary delivery. Keep map-specific recognition policy
  internal. Do not replace fidelity behavior with stock transformable solely for
  API symmetry.

## Acceptance and validation

| Scenario                            | Required evidence                                                                                                                                                                                                                                   |
| ----------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Default controls                    | Existing gesture-fidelity tests retain numeric behavior                                                                                                                                                                                             |
| Apple/Google/Mapbox-like schemes    | Mapping decision matrix plus representative Compose drag/click tests                                                                                                                                                                                |
| Scroll fallback and modifier gating | Continuous/discrete classification, permitted-action fallback, unclaimed input, modifier transition                                                                                                                                                 |
| Dynamic configuration               | Callback updates do not restart input; table changes cancel; held keys retain action; mouse chord changes rebase                                                                                                                                    |
| Picker/rotation locks               | Every input source obeys policy; live tests preserve padded target and prohibit FitBounds with either pan or zoom locked                                                                                                                            |
| StreetComplete following            | Semantic pan starts before the command; zoom/rotation/tilt/custom drag do not trigger it                                                                                                                                                            |
| Transform independence              | Mouse-only drag leaves touch-pair pan possible; suppression during zoom has deterministic terminals                                                                                                                                                 |
| App drag                            | Admission fallback, stable-key updates, one terminal, preview cleanup through the production path                                                                                                                                                   |
| Click and context intents           | No default single-tap demand without subscribers (double-tap/tap-drag may still reserve a press); unused double-tap mappings add no delay; layer priority, hit padding, ordinary unhandled delivery, stale async queries, right-click vs long press |
| External input                      | Same authority/guards/fences; policy gating; None; takeover, nesting, cancellation and detach                                                                                                                                                       |
| Host limits                         | No fake pointer identity, duplicate host listeners, or added host-stream inertia                                                                                                                                                                    |

Keep production regression tests for recognition, Compose routing, and live
engine ownership. Replace repeated old-slot tests with focused routing and
policy tests; avoid a Cartesian product of every scheme, device, and tuning
value. Run affected mise platform tasks and static/documentation checks.
Synthetic input and live-engine tests do not establish physical touch/trackpad
calibration; that remains a separate release validation requirement.
