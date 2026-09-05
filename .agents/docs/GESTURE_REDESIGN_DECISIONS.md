# Gesture redesign decisions

These choices refine [the design](GESTURE_REDESIGN.md). Gesture fidelity,
idiomatic Compose, simple code, and predictable behavior take priority over
incidental API details.

## Configuration

The [input separation](GESTURE_EXTRACTION.md) records the shared recognizer
boundary and the decision to keep it internal until a standalone attachment API
has a demonstrated consumer.

- A key chord has one action. `bind` replaces its previous assignment, including
  Standard engagement actions. Requiring `remove` first would add a failure mode
  without strengthening the no-overlap invariant.
- The single-filter shorthand returns the only filter, or null for zero or
  several filters. Assigning null clears the OR-list. Reading the Standard
  rotate/tilt slot therefore does not throw merely because it has two filters.
- Assigning drag `startSlop` sets both touch and mouse thresholds;
  `mouseStartSlop` can override the mouse threshold afterward. Standard retains
  4 dp touch pan and 3 dp mouse pan.
- Public equality includes callback identity. Structural identity includes
  callback presence but excludes identity. Updated callbacks stay with their
  structural configuration, so replacement cleanup reaches the outgoing
  recognizer's latest handlers rather than handlers that never received Start.
- `GestureOptions` and backend `setGestureSettings` copies are removed. Compose
  owns the bindings; demos derive immutable values from their own snapshot
  state.

## Camera ownership and completion

Admission, takeover, and sealing use the existing per-state lifecycle lock. The
native owner queue remains the command queue. A normal completion fence drains
accepted commands and engine events before subsequent camera work runs; there is
no second queue on the Compose thread. JS applies the same authority rules.

Commands check the token and registered child job at execution. Cancellation
therefore rejects queued commands before asynchronous cleanup arrives. Normal
completion seals new enqueues while keeping the child alive through the fence.
Public camera operations carry generation and attachment guards and retain their
existing retry across attachments. A delayed tap may acquire camera authority
only if no newer accepted input or programmatic command intervened.

Each input family cancels its own session. Old pointer cleanup cannot end a
newer key ease. Cancellation observation is dispatched outside the engine-owner
call; an immediate authority check after an input observer prevents a response
from using revoked authority. Compose on Android can wrap the dispatcher in an
`ApplyingContinuationInterceptor`, so cleanup uses a dispatcher when available
and otherwise posts through Main.

Tokens always use production camera authority. Recognition tests attach a
recording adapter to a real `MapState` authority, with an optional paused engine
queue for ordering tests. The former authority-free token lifecycle duplicated
production rules and has been removed. Continuation completion follows tracked
animation jobs; the old duration-only completion timer is also removed.

## Recognition and terminals

Pair pan uses centroid displacement and velocity. Pinch, rotation, pan, and
shove have independent thresholds, gains, anchors, event IDs, and terminals
within one camera session. Contact ordering follows arrival. Pair replacement
ends old components; an unselected contact rebases motion. Pair continuation
waits for the whole group to lift and is discarded by newly recognized movement.

Mouse click candidates consume down as well as up. Leaving down unclaimed lets a
parent clickable take the sequence and cancel the map candidate in Final.
Drag-only demand waits for slop. An unmatched press leaves an unrelated scroll
burst running. Consumption and structural restarts suppress existing contacts or
platform component streams until their terminal events arrive.

Custom drag bindings expose one `onEvent` lifecycle callback. It runs before the
selected camera action; `DragAction.Custom` leaves the response to the
application. There is no separate custom-response callback. An application
commits on End and rolls back on Cancel; cleanup cannot deliver a second
terminal after the action has already received End. Built-in bindings retain
synchronous observers before their camera responses.

## Dispatch and hover

One queue per input node preserves recognized tap order while feature queries
suspend independently of continuous input. Dispatch captures attachment, loaded
style, layer order, registration identities, and structural configuration. It
checks validity between callbacks and queries, skips replaced registrations, and
reads updated handlers for surviving registrations. New input invalidates old
camera fallthrough while recognized application delivery may still finish.

A cancelled lease-bound query drops that click without becoming Pass or stopping
later valid clicks. Callback failures on a valid path propagate. When projection
is unavailable, typed binding callbacks and layer queries still receive screen
input with a null geographic position.

Hit padding uses a square dp rectangle; zero uses a point query. Hover always
uses exact points. Binding hover observation needs no feature query. Layer hover
has one worker and one latest pending sample; a valid in-flight result may
publish before the newer sample. Cancellation retains the worker slot until the
query returns, preventing overlap even when cancellation is delayed.

Frames, camera changes, source changes, and style registrations resample a
stationary pointer. Updated projections retain the input timestamp. Removal or
replacement sends Exit to the outgoing registration's latest handler; membership
is recorded before callbacks so reentrant cleanup cannot resurrect it.

## Host input and focus

Scroll normalization uses Compose axis values and reads browser `WheelEvent`
only for `deltaMode`. Browser pixel/line units need no density compensation.
Finite values are checked against Float range before constructing `DpOffset`.
Scroll classification stays fixed for the burst; the library adds no momentum.

Platform Scale/Pan uses pinchZoom/dragPan without touch geometry thresholds or
added momentum. Components share a camera session and synthesize a missing
Start. Android API 34+ classification routes wrapper events away from taps and
ordinary drags. Reported contacts and cancelled components remain suppressed
across structural restarts. No native listener, reconstructed fingers, or global
Compose flag is added; documented host exclusions remain explicit.

Scroll and platform delta streams use Compose's common one-dimensional impulse
estimator over coalesced cumulative displacement. Host pointer estimators gave
different velocities for identical delta samples across platforms. Physical
pointer drags retain their host estimator. Key metadata uses a host monotonic
clock because Compose's common key API exposes no timestamp; no reflection into
Skiko's internal key wrapper is required.

Structural configuration changes make held keys release-only until they lift;
callback-only updates preserve repeats. Rotary focusability requires a finite,
positive host notch size. Desktop's pinned Skiko test dispatcher does not
deliver rotary injection, so UI routing tests run on Android and explicitly skip
desktop.

## Box selection and demos

BoxZoom draws on the existing input node, appears after recognition, and clears
before End or Cancel. Both dimensions must reach 8 dp. Native projects all four
corners with the camera under the frozen-projection lock and unwraps longitudes
around its target. An invalid projection abandons the fit. Existing bounds-fit
helpers receive zero additional padding, preserving declarative padding,
bearing, tilt, and existing pitched-fit semantics. The awaited ease uses the
originating session and the shared 300 ms default, respecting system motion
scale.

Demo gesture overrides extend the selected base configuration. Tracking stops
following on pan or box selection; zoom, rotation, and tilt retain follow and
pause tracking camera writes while user input owns the camera. The drag demo
uses layer handles, padded selection, and synchronous screen-distance
reservation. It accumulates dp displacement in a preview, commits on End, and
clears on Cancel. Changing edit mode changes the binding ID so unfinished
previews are cancelled.

## Test boundaries and evidence

| Boundary                             | Regression responsibility                                                                                                                         |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| Common configuration/math            | Validation, immutable snapshots, filters, presets, thresholds, velocity equations, and projection/box geometry.                                   |
| Common input processors              | Pair/platform component lifecycles, callback replacement/failure, burst timing, delta velocity, and routing suppression.                          |
| Compose UI                           | Main/Final consumption, parent interaction, subscriber demand, real structural restarts, contact changes, keyboard/focus, and host event routing. |
| Common authority with paused adapter | Stale admission/enqueues, sealing, caller cancellation, nested/retained scopes, and old cleanup after takeover.                                   |
| Native/JS live maps                  | Actual command execution and fences, attachment/callback races, camera anchoring, box fit, and stationary hover.                                  |
| Native composition                   | Rendering-dependent behavior such as the pitched-fling regression.                                                                                |

Consolidation removes the duplicate processor restart scenario in favor of the
UI restart test, moves pair filter rejection into the pair processor case, and
leaves preset anchoring to configuration and live-camera tests. Platform
pan/scale/no-momentum is tested at the processor boundary; UI tests prove
delivery through the pointer node. The separate platform live-map replay is
removed: shared camera integration tests already cover backend pan/scale and
fencing. Removed duration-timer tests belonged to the deleted continuation path.

Before input separation, the consolidated suites passed: 372 Android-host tests,
872 desktop tests (seven explicit skips), 543 browser tests, 671 iOS simulator
tests, and 806 Android API 36 emulator tests. See
[the separation audit](GESTURE_EXTRACTION.md) for current validation. Static
checks and Android Lint passed. Physical touch/trackpad calibration and
documented host exclusions remain release checks. Direct desktop automation did
not establish demo handle dragging. An earlier Android-host snapshotter cleanup
test failed intermittently and passed on rerun without a source fix; this work
makes no claim to fix it.
