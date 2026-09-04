# Keyboard focus and engagement

Design notes for how `MaplibreMap` takes focus, when it consumes keys, and how
an app reaches the map with a keyboard, a D-pad, or a TV remote. Tracks
[#1248](https://github.com/maplibre/maplibre-compose/issues/1248) (accessibility
and keyboard focus) and the TV and tvOS findings in
[#26](https://github.com/maplibre/maplibre-compose/issues/26).

This is a sibling of [GESTURE_REDESIGN.md](./GESTURE_REDESIGN.md). That document
owns recognition, bindings, and dispatch. This document owns the layer above the
key bindings: whether the map is in focus traversal, and whether a focused map
consumes keys. It lands before the gesture redesign and touches no recognizer,
event value, or binding. The section
[Alignment with the gesture redesign](#alignment-with-the-gesture-redesign)
lists what the redesign inherits.

## What the current code is

The key handler in `MapInput.kt` is one `onKeyEvent` on the internal input node.
The node is always `focusable()`, and a pointer press requests focus on it.
Direction keys pan, Plus and Minus zoom, and Shift with a direction key rotates
or tilts. All of it runs only while the node has focus.

Each host reaches that state differently:

- **Desktop and web.** Tab reaches the map, because the input node is the first
  focusable in composition order, and Tab leaves it. Arrow keys pan while the
  map has focus. The map draws no focus indication, so a keyboard user has no
  sign that the map has focus or that arrows now pan.
- **Android TV and tvOS.** A remote has no Tab. Compose moves focus with
  direction keys through a two-dimensional search, and that search never selects
  a candidate whose rectangle contains the focused one. The map fills the window
  and contains every control, so no direction key reaches it. After a touch
  focuses the map, the handler consumes all four directions, so no direction key
  leaves it either. The Compose tvOS fork translates Siri Remote input into the
  same direction, Enter, and Back key events, so tvOS has the same behavior.
- **Touch.** A press focuses the map. No key arrives, so focus has no visible
  effect.

Issue #1248 states that focus configuration on the public `MaplibreMap` modifier
does not reach the input node, because the modifier applies to an outer `Box`.
Compose resolves a `focusRequester`, `focusProperties`, and `onFocusChanged` on
an ancestor against descendant focus targets, so the outer placement is expected
to work. The first step in [Sequence](#sequence) confirms it with a test before
anything depends on it.

## The shape

Focus and engagement are two separate states on the input node.

- **Focused** means the node holds Compose focus. Focus arrives by traversal, by
  a `FocusRequester`, or by a pointer press.
- **Engaged** means the key bindings are armed. Only an engaged map consumes
  direction keys.

A map that is focused and not engaged passes direction keys through unconsumed,
so Compose continues focus traversal from the map. This is the property that
D-pad hosts need, and it costs desktop nothing, because Compose desktop also
moves focus with unconsumed arrow keys.

### Transitions

| Trigger                                | Focused | Engaged | Consumed |
| -------------------------------------- | ------- | ------- | -------- |
| Focus by traversal or `FocusRequester` | yes     | no      |          |
| Enter, numpad Enter, or D-pad center   | yes     | yes     | yes      |
| Pointer press                          | yes     | yes     | no       |
| Escape while engaged                   | yes     | no      | yes      |
| Back while engaged by a key            | yes     | no      | yes      |
| Back while engaged by a pointer        | yes     | yes     | no       |
| Focus loss                             | no      | no      |          |

A pointer press engages immediately, so a click followed by arrow keys pans, as
it does today. That press must not make the map consume Back. Compose delivers
`KEYCODE_BACK` to the focused node before the activity, so a map that consumed
Back after a touch would break back navigation on every Android phone. The node
records whether a key or a pointer engaged it, and consumes Back only in the
first case. Escape has no system meaning and is consumed in both cases.

The engage and disengage keys are fixed in this design. The gesture redesign
makes them configurable; see
[Alignment with the gesture redesign](#alignment-with-the-gesture-redesign).

### Focusability

The node is focusable only while at least one keyboard gesture is enabled. A map
with keyboard pan, zoom, and rotate-tilt all disabled handles no key, so it
stays out of traversal. Today it takes a Tab stop and does nothing with it.

### Semantics

The node gets a content description of "Map" and a `stateDescription` that
reports the engaged state. This is the part of #1248 that costs one modifier.
Rendered features, gesture actions, and instructions in the semantics tree are
out of scope here and stay on #1248.

### Observable state

`MapState` exposes the focused state and the engaged state as observable
properties. The overlay is a sibling of the input node, so it cannot attach an
`onFocusChanged` modifier to it and reads the state instead.

The properties are reads. Focus and engagement are an input-node lifecycle, like
the gesture token, so the node owns the writes.

### Focus ring

The ring is an overlay composable in `MapOverlay.Default`, so `Full` and the
Material 3 presets inherit it. It fills the overlay with a border-only drawing,
appears while the map is focused and `LocalInputModeManager` reports keyboard
mode, and draws a stronger stroke while the map is engaged. Touch users never
see it, the same rule that Material applies to its own focus indication. The
Material 3 module draws it in the theme's primary color.

The ring is a drawing only. A focusable or a pointer handler on it would take
presses from the map.

## Demo app

The demo is the proof that the model works. Its shell has a panel, a toggle
handle, the map, and overlay controls, and every one of them sits inside the
map's rectangle. Implicit search cannot reach the map from any of them, and
cannot reach any of them from the map, so the demo wires the route explicitly.

- The handle, the map, and the zoom buttons each get a `FocusRequester`.
- The handle's `focusProperties` send Right to the map.
- The map's `focusProperties` send Left to the handle and Right to the zoom
  buttons. These apply only while the map is not engaged, because an engaged map
  consumes direction keys before traversal sees them.
- On the compact layout with the panel open, the map is under the panel. Its
  `focusProperties` set `canFocus = false`, matching the accessibility hide that
  is already there.
- The demo uses the `Full` and Material 3 presets, so it inherits the focus ring
  and draws no indicator of its own.
- The settings dropdowns use `ExposedDropdownMenuBox` over a read-only text
  field. Check them on a D-pad. If Select does not open them, a list dialog
  replaces them in keyboard mode.

The demo's Wear layout and rotary zoom are separate items under #26. Rotary
events reach the focused node the same way keys do, so rotary zoom joins the
same handler when it lands.

## Verification

`MapInputRecognitionTest` already drives keys against the input modifier as an
Android host test. New cases cover the transitions: Tab into the map and assert
that a direction key does not pan and moves focus; press Enter and assert that
it pans; press Escape and assert that the next direction key moves focus; click
and assert that Back is not consumed.

For the real hosts, the Android TV emulator image from the #26 test runs the
demo, `adb shell input keyevent` sends `DPAD_*`, `DPAD_CENTER`, and `BACK`, and
the demo driver captures a screenshot between presses. On desktop, the Compose
UI test host covers Tab and arrow traversal through the shell.

## Sequence

1. **Confirm the modifier path.** A host test places `focusRequester` and
   `onFocusChanged` on `MaplibreMap`'s modifier and asserts that they reach the
   input node. If they do not, the input node moves under the caller's modifier
   before step 2.
2. **Engagement.** The engaged state, the transitions, conditional focusability,
   and the semantics, in `MapInput.kt`, with the test cases above.
3. **Observable state and ring.** The focused and engaged properties on
   `MapState`, then the ring in the overlay presets and its Material 3 version.
4. **Demo wiring.** The focus route and the dropdown check, then the TV emulator
   pass.

## Alignment with the gesture redesign

The gesture redesign keeps its shape. Three points carry over from this design:

- **The `keys` block derives focusability.** The redesign already derives which
  recognizers run from the set of bindings. The same rule decides focus: no key
  and no rotary binding means the map stays out of traversal.
- **Engagement is a layer above bindings.** It gates whether key bindings fire,
  so it belongs in the redesign as its own concept rather than as one binding.
  The engage and disengage keys become members of the `keys` builder. Shipping
  the fixed-key version now avoids a throwaway option on `GestureOptions`, which
  the redesign deletes.
- **The focus target lives on the gesture modifier.** When the redesign extracts
  a public `Modifier.mapGestures`, that modifier carries the focus target, so a
  caller's focus modifiers attach to it directly. The `keys` block broadens to
  focused input, so rotary bindings for Wear join it.
