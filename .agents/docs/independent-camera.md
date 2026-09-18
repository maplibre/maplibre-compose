# Independent camera commands

## Status and scope

This records the implemented API and the remaining cancellation follow-up. The
branch started on #1429 (`sargunv/bindings-kotlin-v0.202609.3`), which has since
merged. GitHub retargeted draft #1430 to main. Breaking API changes are welcome;
compatibility is not a design constraint. Global style state, bearing sectors,
and rendered projections are outside this work.

Proceed with independent native animations now. GL JS keeps its current
latest-animation-wins behavior. Selective cancellation is a follow-up tracked in
[FFI #726](https://github.com/maplibre/maplibre-native-ffi/issues/726), not a
release blocker. Cancelling a coroutine withdraws its waiter; an already-started
engine animation continues. `stopCameraMovement()` explicitly stops all motion.
Input takeover, detach, and anchor geometry invalidation still stop engine work.

## Starting point

- `CameraInputAuthority.beginProgrammatic` owns one job and generation. Each
  command cancels its predecessor, including commands waiting for a viewport.
  Guards are checked before enqueueing and again when the engine executes.
- `MapAttachment` binds suspending operations to the presentation lease. Keep
  that fence: a command cannot migrate to a replacement presentation.
- `MlnFfiMapSession` registers transition-ID waiters but uses one
  `currentTransitionId` to decide whether cancellation calls
  `cancelTransitions`. Removing the public guard would leave an older cancelled
  command running, or stop unrelated commands when cancelling the newest one.
- At FFI tag `bindings/kotlin/v0.202609.3`, `camera.h` exposes only
  `mln_map_cancel_transitions(map)`. Transition IDs identify completion events,
  not a cancellation operation. The carried
  `0015-independent-camera-animations.patch` keeps omitted properties running,
  couples flight center/zoom, and couples anchored command properties to center.
- Native `applyViewportInsets` sends padding alone. That can preserve other
  tracks, but it replaces an animated padding track. Anchor geometry changes
  deliberately cancel the anchored operation.
- In
  [GL JS 6.9.1 camera.ts](https://github.com/maplibre/maplibre-gl-js/blob/v6.9.1/src/ui/camera.ts),
  `jumpTo` calls `stop`, `easeTo` calls `_stop`, and `_ease` stores one frame
  callback. `setPadding` calls `jumpTo`. `easeId` changes end-event handling; it
  does not create independent tracks. Compose also has one pending initial style
  action and resolves transition waiters from aggregate `moveend`.

## Public API

Use a `CameraUpdate` value with nullable `target`, `zoom`, `bearing`, `tilt`,
and `padding: DpPadding?`. Null means no ownership and no mutation. Reject an
empty update. Keep `CameraPosition` as the complete observed/saved value.

Expose suspending `animateCamera(update, animation = CameraAnimation.Ease())`.
Replace `animateCameraPosition`; use `CameraPosition.toCameraUpdate()` when all
properties should be targeted. Retain `setCameraPosition` for durable
full-camera assignment and `animateCameraAround` for the separate, ease-only
anchored operation. Fits remain useful complete-camera operations. This avoids
an anchor parameter whose legality would depend on the chosen animation and
update fields. Zoom and compass callbacks also return updates, so their defaults
change only zoom or orientation. Location following omits zoom, tilt, and
padding.

One command has one duration/easing. Launch separate commands for separate
timing. Easing owns exactly its specified properties. A flight additionally owns
center and zoom as a coupled pair. An anchored ease additionally owns center and
couples it to every specified property. Coupling is part of command ownership
even when a target equals the current value.

## Ownership and completion

The input authority retains a set of waiting jobs and a generation that input or
exclusive camera operations revoke. Immediate commands also carry an admission
revision, so a queued global stop cannot stop a newer animation. Guards are
checked when queued and on the engine thread. A call waits for a usable viewport
on one attachment and cancels if that attachment is lost.

Native owns property sets and coupling; Compose keeps waiters by transition ID.
This avoids duplicating the native track scheduler in Kotlin. Replacement order
is the order in which commands execute on the engine thread. Drain completion
events after starting a command, before later geometry work can inspect a stale
anchor ID.

A newer command removes overlapping properties from older commands. If it
overlaps a coupled set, remove the entire coupled set. Unrelated properties of
the older command continue with their original clock and easing. Do not cancel
its coroutine merely because it lost one property. Its suspend call completes
when all its properties have ended or been superseded. This matches the native
transition-ID completion event; completion does not promise every requested
target was reached.

Coroutine cancellation removes queued work or withdraws the completion waiter.
An already-started engine command continues. This temporary limitation applies
to both backends, so late cancellation cannot stop a replacement or an unrelated
animation. Explicit global stop cancels waiting jobs and stops engine motion.
Scoped engine cancellation will replace this behavior after FFI #726 ships.

Recognized camera input takes exclusive ownership and cancels all programmatic
commands, including pending ones. Below-slop input retains its current behavior.
A programmatic command revokes the active gesture and its momentum. Global stop,
detach, and close invalidate every applicable guard before stopping tracks and
settling waiters. Aggregate movement state remains true while any track or
recognized gesture is active; one command finishing must not clear it.

## Viewport insets

Viewport insets belong to presentation geometry, not the camera update. Native
receives effective padding equal to camera padding plus viewport insets.

An inset change may continue unanchored center, zoom, bearing, and tilt tracks.
It supersedes the padding track at its current camera-padding value and applies
the new insets. It cancels anchored commands because their screen geometry has
changed. Logical resizing follows the same anchor rule. This contract does not
claim that animated camera padding can retain its old interpolation while its
effective-padding endpoints change.

Do not restart surviving tracks toward their targets: that resets timing and
easing. A future requirement to preserve an animated padding track through inset
changes needs separate engine viewport insets or an endpoint-rebasing primitive.

## Backend follow-ups

FFI #726 requests owner-thread cancellation by transition ID: stop only the
remaining tracks, respect coupling, preserve replacements, emit completion once,
and make cancellation of a finished ID a no-op. Keep cancel-all for input,
geometry invalidation, detach, and explicit global stop.

GL JS currently stops all animation when starting another command or applying
viewport insets. Document that limitation on the public API. Independent JS
tracks can be implemented later without changing the update type. Avoid private
transform mutation and avoid reimplementing projection, flight, or anchor math.

Command admission keeps a set of waiting jobs and a generation revoked by
exclusive operations/input. Native transition IDs own completion; the browser
continues to use its single easing and `moveend`. Completion after replacement
is normal, and does not promise that every target was reached.

## Verification gates

Verify native independent behavior and the documented browser fallback on real
maps:

1. Two disjoint commands reach their targets on different clocks; completion of
   the short command neither resumes the long waiter nor clears movement state.
2. Replacing one property leaves an older command's other property moving;
   cancelling that older coroutine withdraws its waiter without stopping either
   animation.
3. Late cancellation cannot stop a replacement; input and explicit global stop
   cancel all commands, including ones waiting for the first viewport/style.
4. Overlapping flight/anchor ownership stops the coupled set. Insets preserve an
   unanchored camera track, supersede padding, and cancel anchors. Detach
   prevents queued work from affecting a replacement presentation.

The existing native inset regression checks continuing motion, camera padding,
and aggregate movement reporting. The original boolean movement state failed
after the inset command ended; counting outstanding camera changes fixes that
failure. Abandonment and detach clear the count.
