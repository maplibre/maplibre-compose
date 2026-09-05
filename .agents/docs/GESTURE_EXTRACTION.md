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
