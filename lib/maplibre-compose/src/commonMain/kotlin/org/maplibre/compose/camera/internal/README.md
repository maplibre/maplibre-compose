# Camera input lifetime

Built-in controls use `GestureInputSession` to group contact movement and
release momentum. A session starts when input is recognized and holds one camera
token. A pointer held below drag slop does not reserve the camera; crossing slop
interrupts the then-current camera motion. Pan, zoom, rotation, and pitch can
share that token, but each reports its own semantic `onStart` before its first
effective command. A replacement contact can restart a component without
replacing the whole session.

`CameraInputAuthority` coordinates input with programmatic camera changes and
map attachment changes. Tokens and commands run on main. Changing callbacks
alone preserves input; changing camera settings cancels it.

Commands check their authority before they start. Starting a command can stop an
earlier transition or report state, invoking callbacks that replace it again.
`runCameraCommand` checks before and after that activation step. These checks
have different purposes and cannot be replaced with one check at recognition
time.

Finishing stops new commands. Cancellation revokes the token. Completion means
the backend has processed the finish: native awaits the END completion and
drains events inline on main; JS completes through its event handling. Coroutine
cleanup must not publish an early finish while native camera events are still
queued.

A session's end carries the release velocities and the snaps of the parts the
input changed. Native sends them with the END delta, and MapLibre Native runs
the momentum: the token stays active, so the camera keeps reporting a gesture,
until the delta's end handler reports that the momentum ended. GL JS can't take
momentum, so the session runs the same glide and snap frame by frame before it
finishes. Newer input, a camera command, or a settings change stops the
momentum, and the speed it still had joins the next release that goes the same
way.

Two counters distinguish camera replacement from newer input. A programmatic
camera command becomes stale when another camera operation replaces it. A
delayed click's camera response also becomes stale after accepted input that
does not move the camera. That input still allows the click callback to be
delivered; it only prevents an old asynchronous click query from unexpectedly
zooming the map.
