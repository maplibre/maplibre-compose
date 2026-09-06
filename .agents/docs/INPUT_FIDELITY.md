# Input fidelity and host boundaries

[Map interactions](MAP_INTERACTIONS.md) defines the public API. This document
records the behavior that its internal recognizers and camera responses retain.
Internal recognition stays in this module; Compose delivers input and controls
cooperation with surrounding UI.

## Compose cooperation

Use Compose pointer passes, consumption, cancellation, velocity tracking, focus,
and rotary delivery. Reject relevant downs, moves, and ups consumed by another
handler; distinguish consumption by this map. External takeover cancels the
affected recognized lifetime and pending tap recognition, then suppresses held
contacts until release. A drag-only configuration waits for slop before
consuming. Tap, long-press, double-tap, or tap-drag demand can reserve a down
earlier. Returning `Pass` cannot undo pointer consumption. This is not
nested-scroll delta sharing.

Configuration changes compare structural values independently of callbacks.
Callbacks update after committed compositions. Each callback field has an
internal subscription identity: replacing its body preserves membership;
removing it retires membership, including when it is restored before the next
input event. New subscriptions cannot join an admitted lifetime. Async feature
queries retain press-time membership and validate structural configuration,
style, attachment, and callback slots before delivery. An admitted application
click may finish after camera movement; camera ownership is checked separately
before its camera fallback.

## Recognition and contact transitions

Default slop is 4 dp for pan, 3 dp for mouse drag, 7 dp of span for zoom, 3
degrees for rotation, and 16 dp for shove. Minimum-span, pressure, shove-angle,
and rate gates remain internal in GestureMath. Touch double-tap pairing permits
100 dp separation; mouse pairing permits 3 dp. Compose ViewConfiguration
supplies double-tap and long-press time limits. Two-finger tap uses a 5 dp
movement limit and a 150 ms timeout. Pan uses centroid displacement, so
symmetric pinch cannot cross pan slop. First deltas exclude recognition slop;
later deltas are incremental. Each started component receives one End or Cancel,
with a fresh public gesture ID for a newly recognized component and shared
camera ownership within its contact group. Equal timestamps have unknown rate:
apply geometry slop, coalesce velocity samples, and avoid invented elapsed time.
Negative time changes reset tracking.

Pair pan can coexist with zoom and rotation; shove is exclusive. Default
rotation entry cancels scaling, with scale restart requiring at least 75 dp of
additional span change, or a larger configured zoom threshold. With rotation
disabled during zoom, zoom instead takes priority and rotation rearms from a
fresh baseline after zoom ends. Contact replacement rebases geometry and
velocity. Track the oldest eligible pair; an unselected third contact does not
create another transform. Moving from one contact to a pair cancels the
single-pointer component without momentum. End affected pair components once
when a selected contact lifts. A remaining contact may begin camera pan with
fresh recognition and semantic start, retaining the group's camera session.

Stage release momentum until the last contact lifts and discard it when new
movement is recognized. Custom drag admission uses synchronous app-owned hit
geometry, is latched once accepted, and never queries features speculatively. A
second contact cancels a custom drag and suppresses the whole group until
release. Built-in mouse chord changes reselect only camera mappings; a changed
response cancels without momentum and restarts slop from the current position.

## Response calibration

Preserve the existing equations rather than replacing calibrated behavior with a
generic unitless sensitivity. Pan momentum defaults to minimum speed 1000 dp/s,
base time 150 ms, and duration scale 1. Duration in milliseconds is
`(speed / 10.5 + baseTimeMillis) * durationScale`; travel is velocity times
duration in seconds times 0.28, applied in small screen-space steps. Zoom and
rotation momentum use duration scale 1 and maximum duration 300 ms. Tilt uses
minimum speed 5 degrees/s and duration 150 ms with linear velocity decay.
Cancellation never starts momentum. Tap/key easing defaults to 300 ms and obeys
system motion scaling. Host transforms and scrolling add no library momentum.

Mouse rotation uses 0.8 degrees/dp and pitch uses -0.5 degrees/dp. Shove pitch
uses -0.1 degrees/dp. Single-pointer RotateTilt adds tilt momentum only; bearing
momentum belongs to pair rotation. A null anchor means the padded camera target.
Tap-drag, keys, and rotary default to that target; pair zoom/rotation and
double/two-finger tap use the input location. Disabling camera pan forces target
anchoring.

FitBounds requires at least 8 dp on both rectangle dimensions. Clear its preview
before terminal delivery. Project all four corners against the frozen current
viewport, unwrap longitude around the current camera, and preserve bearing and
tilt. Use zero additional fit padding so persistent padding is applied once.
Native and JS retain their existing pitched-fit limitations; flat-camera
rectangle enclosure is tested without promising exact pitched enclosure.
Cancellation never fits; the input session owns normal-release easing.

## Camera authority and application delivery

Use one authority per MapState and the existing lifecycle lock and owner queue.
Validate token ownership both at enqueue and execution. Tokens bind to an
attachment; a newer owner revokes child work and queued commands. Old cleanup
cannot end a newer owner's session. Normal completion seals commands, drains
accepted work, and waits for the existing completion fence. Camera-input
callbacks run outside the owner loop; recheck authority afterward before issuing
a camera command.

Click delivery is map intent, front-to-back eligible layers, ordinary unhandled
click, then permitted camera fallback after Pass. Mouse's first click is eager;
a later double click cannot retract it. Long press and secondary-button release
share the context-click intent but have independent admission settings. Layer
hit padding affects feature queries, not pointer recognition. Hover reports
layer membership rather than feature identity. Query layers sequentially in
render order because native queried features lack rendered style-layer identity.
Use one frame-paced worker and the newest pending sample; a still-valid older
result may publish to avoid starvation. Resample stationary hover after
projection, frame, or style changes. Retirement delivers Exit through the
outgoing subscription's latest body.

## Scroll normalization

Classify the first nonzero sample immediately and latch its kind for a burst.
Default idle timeout is 200 ms. Button/modifier changes cancel the old burst.
Consume in Main only when a response is accepted. Zero or nonfinite deltas pass
through. External consumption cancels the burst and clears its classification.
Hosts may already include inertia; do not add another momentum model.

| Host units                     | Pan in dp                               | Zoom units    |
| ------------------------------ | --------------------------------------- | ------------- |
| Browser pixel                  | negative raw delta                      | raw / 100     |
| Browser line                   | negative raw × 100 / 3                  | raw / 3       |
| Browser page                   | negative raw × viewport dimension in dp | raw           |
| macOS rotation                 | negative raw × 10 / density             | raw           |
| Windows/Linux/Android rotation | negative raw × 40                       | raw           |
| iOS physical pixels / 100      | negative raw × 100 / density            | raw / density |

Compose web already handles Shift axis swapping. Read WheelEvent only for
`deltaMode`; do not compensate density twice. Zoom uses the dominant absolute
axis, with Y winning ties. Browser line/page input is discrete. Pixel input on
two axes is continuous; otherwise exact multiples of 100 or 4.000244140625 are
discrete (quotient tolerance 1e-6), and other values are continuous.
Desktop/Android input is continuous when fractional beyond 0.001 or on both
axes; iOS scroll is continuous.

## Focus and rotary

Tab focus does not engage map navigation. Standard Enter engages, Escape
disengages, and Back disengages only when a key engaged the map; pointer
engagement leaves Back to the app. Focus loss clears engagement. Held keys
retain their selected response and semantic component lifetime across repeats;
configuration changes and camera takeover suppress old repeats while consuming
their owed releases. A new press can select the replacement mapping.

Rotary needs focus but not keyboard engagement. Positive vertical input zooms
out. It has an independent idle burst and no library release momentum; a
rotary-only map can be focusable without camera key mappings.

## Host limits

These boundaries were inspected against Compose 1.12.0 and Nucleus 2.5.12. They
are source-level support statements, not physical-device calibration.

- Android 34+ classified Scale/Pan uses Compose wrapper suppression without
  changing global platform flags. Classified Press/Release wrappers do not enter
  tap, long-press, or single-pointer drag recognition.
- iOS provides indirect scroll and touch pairs, without a dedicated indirect
  pinch guarantee. AWT supplies scrolling, not native transform events.
- Nucleus macOS and Wayland synthesize touch pairs; Windows provides pinch but
  not rotation. X11 has no corresponding transform route. Synthetic contacts
  offset by 120 pixels may miss narrow maps or map edges. Do not reconstruct
  private pointer identities or add parallel native listeners.
- A reported Touch type does not distinguish a touchscreen from a trackpad. Some
  Nucleus macOS/Linux paths send Release before Cancel and lose the native
  distinction; do not infer cancellation from timing.
- Browser Ctrl-wheel routes to zoom using browser delta units.
- Host-recognized Scale/Pan needs no raw-contact slop, pressure checks,
  synthetic contacts, or extra momentum. Synthesize a missing Start, then use
  the same camera authority and release suppression as other input.

## Host source references

- [Compose UI desktop 1.12.0 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-desktop/1.12.0/ui-desktop-1.12.0-sources.jar)
- [Compose UI iOS 1.12.0 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-iosarm64/1.12.0/ui-iosarm64-1.12.0-sources.jar)
- [Compose UI web 1.12.0 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-js/1.12.0/ui-js-1.12.0-sources.jar)
- [Android Compose UI 1.12.0 sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.0/ui-android-1.12.0-sources.jar)
- [Nucleus macOS synthetic transforms](https://github.com/NucleusFramework/Nucleus/blob/v2.5.12/decorated-window-tao/src/main/kotlin/dev/nucleusframework/window/tao/scene/TaoComposeSceneHost.kt#L1073)
- [Nucleus Windows pinch](https://github.com/NucleusFramework/Nucleus/blob/v2.5.12/decorated-window-tao/src/main/kotlin/dev/nucleusframework/window/tao/scene/TaoComposeSceneHostWindows.kt#L626)
- [Nucleus Linux native input](https://github.com/NucleusFramework/Nucleus/blob/v2.5.12/decorated-window-tao/src/main/native/src/platform/linux/touch.rs)
