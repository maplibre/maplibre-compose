# Gesture redesign decisions

This log records implementation choices and changes to
[the design](GESTURE_REDESIGN.md). Gesture fidelity, idiomatic Compose, simple
code, and predictable behavior take priority over incidental API details.

## Exact key assignments

The keys builder stores one action per exact `KeyChord`. `bind` replaces that
chord's previous action, including a Standard engagement action. A chord cannot
have both a camera action and an engagement action, so the design's no-overlap
invariant holds by construction. Requiring an explicit `remove` before a
replacement would add an unnecessary failure mode to ordinary rebinding.

## Reading the single-filter shorthand

`filter` is nullable. Reading it returns the only filter, or null when `filters`
is empty or contains several alternatives. Assigning a filter still replaces the
whole OR-list, as designed; assigning null clears the list. This avoids throwing
when an application reads the Standard rotate/tilt slot, which has two filters.
The full list remains available through `filters`.

## Drag slop across pointer types

Standard keeps 4 dp pan slop for touch and 3 dp for mouse. Assigning a drag's
`startSlop` sets both thresholds, so an application's explicit tuning works for
both input routes. The additional `mouseStartSlop` property can override mouse
slop afterward. Mouse-only built-in slots start with 3 dp for both values.

## Camera command ordering

Camera admission, takeover, and sealing use the state's existing lifecycle lock.
The native owner queue remains the camera command queue. A normal gesture fence
ends its command batch and drains engine events before later commands execute.
This preserves movement reporting when a programmatic command follows a gesture,
without adding a second command queue on the Compose thread.

Cancellation checks both the token and its registered child job at execution.
This rejects queued work even before a cancelled coroutine reaches its cleanup.
Normal completion keeps the child alive while its sealed token drains. Public
camera operations carry a generation guard and an attachment guard into native
and JS execution, preserving their existing retry across attachments.

The native live-map fixture now uses the same `MapState` for its session,
presentation, and callback recorder. Its previous two-owner setup could not
exercise per-state gesture authority correctly.

## Input session ownership

Built-in pointer, scroll, key, and rotary camera work now registers a child job
with the same authority as public gesture scopes. Normal input completion seals
new commands and keeps that job alive until the backend fence drains. Takeover
or detach cancels its continuations and idle timers immediately, without waiting
for another input sample. Cancellation observation is dispatched onto the input
coroutine's dispatcher, outside the engine owner's callback. Input-thread
observers also trigger an immediate authority check before the response; this
preserves the camera-takeover reason when a parent consumes the same event later
in Main.

Each input family cancels only its own session. In particular, cleanup from an
old pointer cannot cancel a newer key ease. A fresh accepted press closes the
previous session instead of reviving a pending end token. Accepted input
advances a generation even before camera recognition. Conditional camera
acquisition checks that generation under the lifecycle lock, so stale
asynchronous work cannot revoke a newer owner. Tap camera fallthrough now uses
this guard after application dispatch.

## Pair components

Pair recognition now keeps separate pan, pinch, rotation, and shove lifetimes.
Each uses its selected filter, threshold, response gain, anchor, and callbacks.
The first delta subtracts the selected component's slop. A zero threshold still
requires movement in that component. Pair pan uses centroid velocity for its End
and fling; finger velocity remains the input to the retained pinch and rotation
momentum equations. Rotation and pinch continuations retain independent anchors.

The contact group keeps one camera session while components start, cancel, and
end. Rotation cancels pinch; pinch can restart after the additional-span rule.
Shove cancels the other components. Contact order follows arrival rather than
pointer ID or event-list order. Adding an unselected contact rebases motion;
replacing a selected contact ends the old components before recognizing a new
pair. Their continuation waits for the whole group to lift and is discarded by
newly recognized movement. Cancellation cleans up every started component even
when an observer throws, then propagates the failure.

An unmatched press leaves an unrelated scroll burst running. Scroll cancellation
now follows accepted contact demand instead of every unconsumed down.

## Click presses and Compose consumption

Mouse click candidates claim their down as well as their up. The design
explicitly requires down consumption for touch/stylus clicks; applying the same
rule to mouse clicks preserves the existing parent-click behavior under proper
Main/Final cooperation. Leaving mouse down unclaimed lets a parent clickable
claim it, which correctly cancels the map candidate in Final. Drag-only demand
waits for its selected binding's slop.

## Scroll conversion

Scroll normalization uses Compose's two axis values and reads the retained web
WheelEvent only for deltaMode. Native units follow the host table in the design.
There is no density compensation for browser pixel or line deltas. Values are
checked against Float range before constructing DpOffset: Kotlin/JS can retain a
larger finite number until DpOffset packs it into an infinite Float.

## Keyboard timestamps

Compose's common key API exposes chords and press/release state, but no event
timestamp. Skiko also wraps its native key event in an internal type. Key
observation therefore uses the host's monotonic clock at dispatch. This avoids
reflection or dependencies on Compose internals. Pointer and rotary observation
retain their reported input timestamps.

## Attachment migration

`MaplibreMap` now takes `gestures: MapGestures`; `GestureOptions` and its
callers have been removed. The backend `setGestureSettings` methods were no-ops
and have also been removed. Compose owns the bindings, so there is no backend
settings copy to keep synchronized. Demo controls keep their own snapshot state
and derive a `MapGestures` value through the public builder.

## Tap delivery and subscriber demand

Each input node now owns one ordered tap queue. Binding callbacks run first,
then the map handler, subscribed layers in loaded front-to-back order, the
ordinary-tap unhandled handler, and optional camera fallthrough. Suspended
feature queries do not block continuous input. A newer accepted input
invalidates the pending camera action while the recognized click still completes
application delivery. Structural reconfiguration cancels the queue with the
input node.

Map handlers default to null. A family's handlers, camera response, and current
map/layer subscribers determine recognition demand. Subscriber eligibility is
snapshotted at the first press, so changing a double-click subscription mid-tap
does not change the pairing decision. Quick-zoom-only and two-finger-tap-only
configurations still claim the presses required for their recognizers. Mouse
retains its eager first click, and its consumption does not consume a later
double click. Secondary-click metadata retains the pressed button on release.

Each dispatch captures the attachment, loaded style, structural gesture
configuration, loaded layer order, and registration identities. It checks these
between callbacks and queries, skips removed or replaced registrations, and
reads updated handlers for surviving registrations. Hit padding uses a square
DpRect; zero uses a point query. A cancelled query from an invalidated
attachment or style drops that click, without becoming Pass or stopping later
valid clicks. Callback failures on a still-valid path propagate.

The existing MapClickHandler requires a geographic Position. When projection is
unavailable, typed binding callbacks and layer queries still receive the input;
the legacy map handler is skipped. This preserves its non-null callback contract
without dropping otherwise valid screen input.

## Hover sampling and cleanup

Map and binding hover observers run without feature queries. Layer membership
uses exact point queries in loaded front-to-back order, including when the layer
sets padding for taps. A single worker waits for a frame, samples subscribed
layers sequentially, and publishes the complete valid pass. New pointer input
replaces the pending sample without discarding valid results already in flight.
Cancellation retains the worker slot until the cancelled query returns, so even
delayed cancellation cannot overlap the next pass.

Camera snapshots, rendered frames, source changes, and style registrations
resample the last pointer location. This also updates map and binding Move
observations with the new projection, preserving the original input timestamp.
It lets applications update a geographic hover label while the camera moves.
Changing callback identity preserves membership; removing or replacing a layer
registration sends Exit to its last callback and starts the replacement outside.
Removing a subscription invalidates its pending query even before its first
Enter, so restoring the same layer cannot publish a result from that old query.
Callbacks see membership recorded before delivery, so reentrant cleanup cannot
resurrect an entered observer or publish the remainder of an invalidated pass.

The live hover test explicitly applies snapshot notifications because its map
fixture has no Compose UI host. Its initial desktop and browser runs timed out
without those notifications. The corrected test verifies stationary-pointer
resampling against a real map and checks that hover queries let rendering
settle.

## Custom response terminals

A custom response tracks whether it received Start separately from the binding
observer. If an End observer takes the camera, the observer has already received
End, but the started custom response receives Cancel instead of committing with
revoked authority. If the Start observer takes the camera, the custom response
receives neither Start nor an orphan Cancel. This refines the design's wording
that observers and custom responses receive the same lifecycle: each started
recipient gets one terminal, while observer-before-response ordering and the
authority check remain intact.

Terminal observer failures also cancel a started custom response. Cleanup
attempts the response even when the observer throws, preserves the original
failure, and records the terminal before invoking user code so it cannot repeat.

## Box selection and fitting

BoxZoom uses a drawing modifier on the existing input node. The selection is
clipped to the map's drawing bounds and adds no pointer handler or layout node.
It appears after drag recognition, retains the press as its origin, and clears
before End delivery or during cancellation. Both dimensions must reach 8 dp.

Native projects all four corners under the existing frozen-projection lock,
reading the camera from that same snapshot. Longitudes are unwrapped around its
target, including noncanonical world copies. A missing or invalid projection
abandons the entire fit. Each backend reuses its bounds-fit helper with zero
additional padding and applies an awaited ease under the existing gesture token.
This preserves declarative camera padding and the existing pitched-fit
semantics. The shared animation duration defaults to 300 ms and respects the
system animator scale, like the other discrete camera responses.

The fit job belongs to the same continuation lifetime as the originating drag.
It keeps that session open until the engine releases the camera. Programmatic
takeover cancels the fit; there is no public camera API call, new session, or
retry across attachments.

## Platform-recognized transforms

Scale and Pan events use the named pinchZoom and dragPan bindings. The host has
already recognized these components, so they bypass touch slop, span, pressure,
and angle tests. Missing Start is synthesized before a valid delta. Components
have independent event IDs and terminals but share one camera session while
overlapping. End reports velocity and adds no library momentum, including when
the host's Pan stream already contains inertia.

Android API 34+ classification routes pinch/swipe wrapper events away from taps
and ordinary drags. Reported wrapper contacts remain suppressed until they lift;
consumption of a wrapper also blocks the following component. The pinned Android
adapter can omit the outer pinch press/release and report a buttonless Mouse
instead. The implementation uses the delivered stream and classification without
changing Compose's global flag, reconstructing fingers, or adding a native
listener. Logical Primary applies to a buttonless classified transform; physical
button requirements and reported pointer types remain intact.

Cancelled component kinds and wrapper contacts survive structural input-node
restarts. Classified streams can report no pressed contacts, so the ordinary
already-down contact guard cannot detect that a replacement recognizer is still
seeing the old stream. Its End clears suppression before a new component can
start.

Delta streams use Compose's common one-dimensional impulse estimators over
coalesced cumulative displacement. The platform-specific pointer estimator
produced different velocities for the same delta sequence on Android/JVM and
browser/iOS. These streams provide displacements rather than physical pointer
trajectories, so the common estimator gives them consistent velocity
observations. Touch and mouse drags retain their host's pointer estimator.
Scroll observations use the same delta-stream estimator; neither scroll nor
classified transforms uses that velocity to add momentum.

## Callback retention during replacement

Pointer callbacks are remembered within their structural configuration. A
callback-only update replaces the callbacks used by the current recognizer. A
structural replacement leaves its last callbacks available to the outgoing
recognizer's cleanup, while the new recognizer gets the replacement callbacks.
The latest structural key is tracked separately to retain ConfigurationChanged
cancellation. This prevents a custom drag's Cancel from reaching a replacement
handler that never received its Start.

## Held keys and rotary focus

A claimed key becomes release-only when structural configuration changes. Its
repeat presses stay consumed until release, including after disabling every
binding or replacing the action for that chord. Callback-only updates preserve
repeat handling. This avoids starting a newly bound action from a key that was
already held when the configuration changed. Rotary focusability checks that the
host's notch size is finite as well as positive.

Compose 1.12.0's Skiko test dispatcher does not deliver injected rotary events.
The rotary UI cases therefore run only on Android; skipping them on desktop is
explicit, rather than treating no-op injection as coverage. Common tests feed
rotary samples into the same processor to cover direction, camera-center anchor,
burst identity, idle completion, callback replacement/failure, and takeover.
They do not establish routing through an Android focus target.

## Cancellation dispatch with a wrapped coroutine interceptor

Android device tests exposed that Compose can wrap a coroutine dispatcher in an
`ApplyingContinuationInterceptor`. Input-session cleanup must not cast every
interceptor to `CoroutineDispatcher`. It uses the existing dispatcher when
available and otherwise posts through the main dispatcher before delivering
cancellation callbacks. Both paths preserve the rule that application callbacks
run outside the map-owner command loop. The device run also confirmed that box
fit duration assertions must honor the system animator scale; its test host
disables animations.

## Demo interactions

The shared map lets each demo extend the user's gesture configuration. The
settings panel exposes binding enablement, continuation enablement, and separate
touch pan, mouse pan, pinch span, rotation, and tilt thresholds.

The live-tracking example cancels follow from pan observers, including keyboard
pan, instead of treating every camera gesture as a request to stop following.
Box selection also stops follow because its purpose is to choose a different
region. Zoom, rotation, and tilt keep follow enabled. Follow camera writes pause
while those inputs or camera transitions are active so tracking does not take
camera ownership away from the user.

The drag example now renders selectable circle handles as map layers. This makes
layer hit padding visible in the same example as custom drag reservation. The
selected handle uses a synchronous screen-distance predicate; it does not query
rendered features from `canStart`. A drag accumulates dp deltas from its initial
projected position, keeps a separate preview, commits on End, and clears on
Cancel. Changing editing mode changes the custom binding ID and cancels any
unfinished preview. A second contact uses the same cancellation path. The
circles replace the old independently draggable overlay widgets, which could not
demonstrate layer hit testing or map binding priority.

## Verification

The first implementation adds common configuration, typed event values, filters,
response tuning, keyboard chords, and structural keys. Public equality includes
callbacks; the structural key includes their presence and omits their identity.
Builders and events copy collection inputs.

The existing recognizer now starts pair pan from centroid displacement instead
of individual finger displacement. Equal-time scale/rotation classification uses
cumulative slop without rate rejection, and negative-time pair samples rebase
without applying motion. Pure continuation equations accept the new tuning and
cap non-pan durations; tilt's linear-decay equation is implemented.

`MapState.gestureCamera.withGesture` and its scoped pan, scale, and
rotation/pitch operations are implemented. Native and JS check authority on
enqueue and execution. Tests cover normal completion fences, queued stale
commands, cancelled callers, takeover, retained scopes, same-state and
cross-state nesting, detach, zero-duration awaiting commands, and camera-center
target invariance with asymmetric padding.

Pointer and scroll processing now share one Main/Final event loop. The contact
gate rejects consumed input, distinguishes its own consumption in Final, and
suppresses cancelled or already-down contacts until release. UI tests cover
parent interception, parent clicks/long clicks, cancellation during a drag,
consumed release, and own consumption. Pure tests cover restarting with contacts
already down and suppression across changing contact counts.

Scroll conversion and immediate classification are implemented for every host
unit in the design. Tests cover density, axis signs/dominance, raw Chromium
increments, fractional rotation units, native WheelEvent metadata, and invalid
values. Scroll now selects the configured pan/zoom binding, retains
classification for its burst, delivers lifecycle observations, claims
synchronously in Main, cancels consumed bursts and modifier changes, and appends
no momentum.

Single-contact drag selection uses filter order, synchronous custom predicates,
and the selected binding's slop. Custom drags receive lifecycle events, cancel
when another contact joins, and suppress that group until release. Mouse
button/modifier changes can select a fresh candidate. Single drag and scroll
callbacks use updated state keyed separately from structural configuration.
Tests cover callback-only updates, structural cancellation, custom reservation
and rejection, observer-before-response ordering, and None passing clicks to a
parent.

Keys use the configured exact chords and synchronous observers. Rotary uses its
own binding, observer, and burst timeout, independent of scroll configuration.
Single/pair velocity tracking coalesces equal timestamps; selected continuation
tuning feeds the pan, zoom, rotation, and tilt responses. Quick-zoom
continuation uses its binding's anchor, including the null camera-center anchor.

Input-session, pair, tap, hover, custom terminal, and box-fit validation passes
Android host, desktop, browser, iOS simulator, and static checks. The shared
input-recognition suite now includes 90 cases. All 90 pass on the Android
emulator; desktop runs 87 and explicitly skips the three rotary UI injection
cases. Eight shared pair tests, ten tap/interaction dispatch tests, and eleven
hover tests pass on all four platforms. Nine live camera integration cases and
one stationary-hover case pass on desktop, browser, and iOS simulator. These
include delayed tap delivery after programmatic takeover and hover Exit after
the camera moves a feature away. The later Android emulator run also passes
these live cases. This does not establish physical input calibration.

BoxZoom adds five common geometry/preview tests and three desktop recognition
cases. Pixel assertions check that the rectangle appears during a drag and
clears on release or structural cancellation. Two live cases compare the gesture
fit to the existing bounds-fit behavior with asymmetric padding, bearing, and
tilt, and cancel an executing fit after its zoom begins to change. These pass on
desktop, browser, and iOS simulator.

Platform transforms add thirteen common component/routing tests, one Android
host classification-gate test, and one live-map integration case on desktop,
browser, and iOS simulator. These exercise supplied Scale/Pan samples,
independent component terminals, shared command ownership, no added momentum,
and suppression after consumption, takeover, modifier changes, or structural
restart. They do not establish physical Android trackpad input delivery. The
desktop replacement test also verifies that an outgoing custom response gets
Cancel through its latest callback, before the replacement receives a new Start.

Android Lint passes after the platform transform and callback-retention changes.
The revised demos compile on JVM and JS. In the running AWT desktop demo, a
click outside a small corner handle selected it through hit padding; keyboard
zoom kept ferry follow enabled, and keyboard pan turned it off. Automated drag
attempts moved neither a selected handle nor the map with ordinary pan, so that
interaction has no runtime verification yet. Static checks pass for the demo
changes. The held-key, invalid-rotary-focus, and injected Scale/Pan tests pass
on desktop and the Android emulator. The three rotary UI injection cases pass on
Android and are explicitly skipped on desktop. Five common rotary/focus cases
cover direction, anchoring, burst timing, callback replacement/failure,
takeover, and engagement replay.

Integrated validation after the demo, focus, rotary, and cancellation-dispatch
changes passes: 375 Android-host tests, 807 Android-emulator library tests, 873
desktop tests (seven explicit skips), 547 browser tests, and 675 iOS simulator
tests. Android Lint and static checks pass. The documentation build passes,
including compiled snippets and internal-link validation.

The final acceptance additions exercise pan Delta failure, custom End observer
failure, pair End failure, scroll End failure, and tap callback failure. They
verify balanced cleanup, preservation of the original exception, and no camera
fallthrough or replay. Existing pair, platform-transform, rotary, and hover
cases cover callback replacement and failures during cancellation. The four new
pointer/scroll cases pass on desktop and Android; the Android run passes all 115
cases in the recognition and native composition classes.

The pitched-fling regression renders a real native map at 60 degrees tilt and
replays the same stroke in both vertical directions, with and without momentum.
It verifies additional travel in the release direction while zoom, bearing, and
tilt stay fixed. It passes on desktop and the Android emulator.

After these acceptance additions, the full suites pass again: 376 Android-host
tests, 879 desktop tests (seven explicit skips), 548 browser tests, and 676 iOS
simulator tests. Static checks also pass. The earlier full Android-emulator,
Android Lint, and documentation results apply to the same production code; the
final changes add tests and this acceptance record.

The acceptance audit maps the design's required areas to these production-path
tests:

| Area               | Evidence                                                                                                                                                                 |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Configuration      | `MapGesturesTest`, `PointerFilterTest`, and input recognition cases for callback-only updates, structural replacement, priority, and subscriber snapshots.               |
| Recognition        | `MapInputRecognitionTest`, `PointerPairGestureTest`, consumption and velocity tests, and the pitched-fling native composition case.                                      |
| Camera ownership   | `GestureCameraTest`, native token-ordering and browser transition tests, lifecycle callback races, and `GestureCameraIntegrationTest`.                                   |
| Dispatch and hover | `MapClickDispatcherTest`, `MapTapDispatcherTest`, existing layer-order tests, `MapHoverGestureTest`, and stationary-hover integration.                                   |
| Camera responses   | Preset/configuration and continuation tests, camera-center integration with asymmetric padding, `BoxZoomTest`, and `BoxZoomIntegrationTest`.                             |
| Focus and rotary   | Input recognition cases for traversal, engagement, held keys, Back/Escape, and Android rotary routing; `MapRotaryGestureTest` for burst processing and callback cleanup. |
| Hosts              | Native/browser scroll conversion, Android classification, shared platform-transform tests, injected Scale/Pan UI cases, and live-map transform integration.              |

Physical touch and trackpad calibration, including the documented host
exclusions, remain release validation gates. Synthetic tests and an Android
emulator cannot establish that evidence. The desktop automation's unsuccessful
drag attempts also leave direct demo handle-drag verification outstanding;
custom reservation, preview cancellation, and terminal delivery are covered by
the library UI tests. No physical-device or successful demo-drag claim is made.

The desktop demo can hang during JVM shutdown in the native logging worker. The
user confirmed this is a known mln-ffi issue fixed for its next release. It is
outside this gesture redesign; force-quitting the demo is permitted.

The first Android device attempt failed during headless emulator startup. A
later visible emulator with the host GPU booted successfully. The first full
library run executed 807 tests and exposed the wrapped-interceptor cancellation
failure and the box-duration assertion described above. After those fixes, all
807 library tests passed with zero failures or skips. The emulator ran API 36
with the host GPU; this is emulator evidence, not physical-device calibration.

During attachment-migration validation, one Android host run failed
`MapSnapshotterTest.style_invalidation_failure_is_reported_without_stalling_close`:
`awaitClosed()` completed instead of reporting the injected cleanup failure. A
full rerun passed without source changes. This is an intermittent result, not a
verified fix to snapshotter cleanup.

During tap-dispatch validation, overlapping Gradle runs interfered with shared
build outputs: one JVM run reported missing generated classes, and simultaneous
iOS runs failed while writing binary test results. These runs are invalid
verification evidence. The subsequent full desktop run and isolated iOS rerun
passed with no failures. Platform test invocations must finish before another
run uses the same build outputs.
