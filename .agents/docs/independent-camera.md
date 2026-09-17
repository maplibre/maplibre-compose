# Independent camera commands

## Status and scope

This is the proposed contract, not an implemented public API. The branch is
stacked on #1429 (`sargunv/bindings-kotlin-v0.202609.3`). Breaking API changes
are welcome; compatibility is not a design constraint. Global style state,
bearing sectors, and rendered projections are outside this work.

FFI #724 supplies independent native tracks, but the current backends do not yet
provide the same ownership and cancellation primitives. Keep the public API
change blocked until those requirements are met. This branch covers native
viewport-inset concurrency and fixes premature idle reporting when the inset
command ends.

## Source findings

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

## Proposed public API

Use a `CameraUpdate` value with nullable `target`, `zoom`, `bearing`, `tilt`,
and `padding: DpPadding?`. Null means no ownership and no mutation. Reject an
empty update. Keep `CameraPosition` as the complete observed/saved value.

Expose `setCamera(update)` and suspending
`animateCamera(update, animation = CameraAnimation.Ease(), anchor = null)`.
Allow an anchor only for easing, without an explicit target. Reject invalid
combinations before acquiring ownership. Fits remain queries returning a
position; convert that position explicitly to a complete update when moving.
Remove redundant mutation entry points when implementing this API, rather than
adding aliases or per-property convenience overloads. Keep `stopCamera()` as an
explicit global stop.

One command has one duration/easing. Launch separate commands for separate
timing. Easing owns exactly its specified properties. A flight additionally owns
center and zoom as a coupled pair. An anchored ease additionally owns center and
couples it to every specified property. Coupling is part of command ownership
even when a target equals the current value.

## Ownership and completion

Give each command an ID, presentation lease, job, live property set, and coupled
set. Register ownership before queueing, then recheck it on the owner thread.
Snapshot starting values only when execution begins with a usable viewport.

A newer command removes overlapping properties from older commands. If it
overlaps a coupled set, remove the entire coupled set. Unrelated properties of
the older command continue with their original clock and easing. Do not cancel
its coroutine merely because it lost one property. Its suspend call completes
when all its properties have ended or been superseded. This matches the native
transition-ID completion event; completion does not promise every requested
target was reached.

Coroutine cancellation stops only the still-owned properties of that command. It
must not stop replacements, even if cleanup runs after a newer command was
queued. Cancellation before execution removes the pending command. Track
completion and late cancellation must be idempotent and reentrancy-safe.

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

## Backend prerequisites and rejected shortcuts

The native prerequisite is an owner-thread cancellation operation addressed by
transition ID, exposed through C and Kotlin. It must stop only that command's
remaining tracks, respect coupling, leave replacements alone, emit completion
once, and do nothing for an already-finished ID. Retain cancel-all for input and
global stop. IDs must be scoped to the map; Compose must not reuse them while a
late cancellation can still arrive. No FFI files are changed by this branch.

A padding/zoom/etc. `jumpTo` at the sampled current values could supersede
selected native tracks, but is a new camera mutation with constraint evaluation
and movement events. It also requires Compose to duplicate the engine's live
ownership and coupling bookkeeping. Prefer a real cancellation primitive over
presenting that workaround as cancellation.

The browser prerequisite is larger: independent engine tracks with command
completion and scoped cancellation, including flight/anchor coupling. Either add
that capability upstream or deliberately undertake a common camera animator for
both backends. The latter must implement projection-aware interpolation, flight
paths, anchors, constraints, reduced motion, and aggregate event reporting; it
is not a small adapter change. Do not access private JS transforms to simulate
partial animation or silently make the same API cancel unrelated browser motion.

After the primitives exist, replace the single job/generation and transition
slot with command ownership in one change across both backends. Retain lifecycle
and input guards. Browser startup must queue disjoint commands, and completion
must use command IDs rather than aggregate `moveend`.

## Verification gates

The implementation must demonstrate these representative behaviors on native and
real Chromium/Firefox maps:

1. Two disjoint commands reach their targets on different clocks; completion of
   the short command neither resumes the long waiter nor clears movement state.
2. Replacing one property leaves an older command's other property moving;
   cancelling that older coroutine stops only its remaining property.
3. Late cancellation cannot stop a replacement; input and explicit global stop
   cancel all commands, including ones waiting for the first viewport/style.
4. Overlapping flight/anchor ownership stops the coupled set. Insets preserve an
   unanchored camera track, supersede padding, and cancel anchors. Detach
   prevents queued work from affecting a replacement presentation.

The native inset regression checks continuing motion, camera padding, and
aggregate movement reporting. The original boolean movement state failed after
the inset command ended; counting outstanding camera changes fixes that failure.
Abandonment and detach clear the count. This does not establish that the
proposed public API, scoped cancellation, or browser concurrency has been
implemented.
