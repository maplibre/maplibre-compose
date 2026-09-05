# Gesture API consolidation and reusable input

Preserve the behavior required by gesture issues #230, #951, #952, and #1201 and
the pinned StreetComplete findings in [the redesign](GESTURE_REDESIGN.md).
Keyboard rebinding, decay tuning, pan observation, recognition thresholds,
padded feature hits, and unhandled click delivery have concrete requirements. PR
size alone is not a reason to remove them.

## Implementation sequence

1. Give each map-wide tap and hover event one public handler. Keep feature-layer
   handlers and post-layer unhandled clicks distinct. Consolidate custom drag
   action delivery with its lifecycle observation where the two overlap.
2. Separate screen input and recognition from geographic projection, map camera
   responses, layer dispatch, and attachment ownership. Preserve contact
   transitions, consumption, thresholds, and cancellation behavior.
3. Compare the advanced camera scope with Compose 1.12.0 `TransformableState`.
   Reuse Compose contracts where their semantics fit; keep necessary map-owner
   command validation and completion fences.
4. Exercise shared recognition in a non-map canvas with pan, zoom, rotation, and
   a draggable selection. The consumer must not supply dummy map objects,
   geographic types, or camera-specific action enums.
5. Extract a Compose-dependent, map-independent module if that consumer proves a
   cohesive boundary. Keep map policy in MapLibre Compose. Do not introduce a
   public arbitrary recognizer registry or a second input implementation merely
   to make extraction possible.
6. Validate changed behavior at recognition, Compose input, and live camera
   boundaries. Run affected platform suites and documentation checks. Keep
   physical-device fidelity claims separate from synthetic input coverage.

## Acceptance

- One map-wide handler for each tap/hover event; no obsolete callback route.
- Built-in and application input use the same camera authority and backend
  operations.
- Reusable input contains no map, geographic projection, style, or native-engine
  dependency.
- The non-map consumer exercises production recognition and custom interaction
  arbitration, rather than implementing a parallel detector.
- Requirements and existing regression coverage survive the separation.
- Rotation suppression during scaling remains an explicit deferred requirement
  unless separately implemented and validated.

## Review boundaries

Commit API consolidation, recognition separation, and a proven extraction as
reviewable changes. Use stacked draft PRs if independently buildable boundaries
make review clearer. Record final decisions and validation here without
duplicating the original redesign contracts.

## Implemented boundary

`org.maplibre.compose.input` contains screen-space drag recognition,
selected-pair tracking, component lifecycles, velocity estimation, and
Main/Final consumption. It depends on Compose geometry, input, and units; it has
no map, geographic, style, or native-engine dependency. MapTransformPolicy
selects map components using the retained pressure, velocity, span, angle, and
shove rules. PointerPairGesture translates recognized deltas to geographic
events and camera responses. Single-pointer map drags use the same PointerDrag
recognizer as the canvas proof.

MapGestures owns the single map-wide callback for each tap family and hover.
`tap.onUnhandled` remains a distinct post-layer stage. Custom bindings have one
`onEvent` callback before their selected action; `DragAction.Custom` leaves the
application response to that callback. Built-in bindings keep their
before-response observers. The obsolete MaplibreMap callback parameters and
MapClickHandler alias are removed.

## Compose transformation contract

Compose 1.12.0 TransformableState already provides scoped mutation, priorities,
and centroid-aware pixel transforms. Its stock detector does not implement the
map's per-component thresholds, shove, quick zoom, or competition rules. Reuse
Compose pointer delivery, cancellation, velocity estimators, focus, and timing;
keep those map-specific recognition policies explicit.

Retain the existing gestureCamera scope for this pass. It accepts dp deltas and
pitch, validates queued commands against attachment ownership, and drains
accepted commands on normal completion. TransformableState accepts pixel pan,
zoom, and rotation with priority-based mutation; adopting it would also require
an attached density adapter and a decision about how its priorities interact
with map input and programmatic takeover. Adding a second adapter API without a
consumer would increase the public contracts. Both built-in and custom camera
input continue to share the existing authority and backend operations.

## Extraction decision

The non-map Canvas test consumer uses the production drag, pair, and consumption
code. It pans, scales, and rotates with its own policy, reserves a selected
handle, commits a completed edit, and rolls back when another contact joins. Its
small two-contact span deliberately differs from the map's minimum-span policy.
A pixel assertion verifies that the edited selection is drawn at its new
location.

Keep these primitives internal for now. The proof establishes reusable
recognition, but it still assembles a raw event loop and policy callbacks. It
does not establish a cohesive standalone binding/attachment API covering contact
transitions, taps, scroll, keyboard, and application actions. Publishing the
current primitives would commit callers to manual event feeding and competition
bookkeeping. That is not yet the cohesive application API this extraction is
intended to provide. A future module should prove its public attachment API with
a production non-map consumer before exporting it. The current boundary
preserves that option without creating a second input implementation or removing
useful map behavior.

## Validation

The final callback consolidation and recognition separation passed:

- Android host: 372 tests.
- Android API 36 emulator: 806 tests.
- Browser: 543 tests.
- iOS simulator: 671 tests.
- Focused desktop: 152 tests covering recognition, dispatch, hover, camera
  ownership/integration, and the canvas proof. Three rotary-injection cases are
  documented skips; all other selected cases passed.
- `mise run check` and `mise run lint:android`.
- `mise run build:docs`, including API reference and internal link validation.
  Updated Kotlin documentation snippets also compiled with the Android demo.

The Android, browser, and iOS suites had no failures or skips. A full desktop
invocation reported success but emitted only 103 test results, ending at a
skipped native-camera case. Full desktop coverage is not claimed. The focused
desktop invocation completed all selected cases. Physical touch and trackpad
calibration remain unverified.
