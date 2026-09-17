# Bearing accuracy sectors

## Decision

Add a sector to `LocationIndicatorLayer` on Native and JS. The value overload
takes `bearingAccuracy: Rotation? = null`, the angular error on either side of
`bearing`. The state overload selects one `BearingMeasurement` and forwards both
fields. It must never combine a heading with course accuracy or vice versa.

Both overloads expose `bearingAccuracyRadius: Expression<DpValue>` (48 dp),
`bearingAccuracyColor: Expression<ColorValue>` (blue at 35% opacity), and
`bearingAccuracyTransition: TransitionOptions` (defaults to
`bearingTransition`). One transition controls changes to the sector's angle,
radius, and color. Radius and color accept constant or zoom expressions;
feature-dependent expressions have no meaning for this source-free layer. The
angular error is a measurement, like horizontal accuracy, rather than a
camera-dependent style expression.

`Rotation` preserves the measurement's units without wrapping like `Bearing`.
Reject non-finite or negative errors and saturate errors above 180 degrees to a
full circle. Zero hides the sector. Null bearing or error hides it immediately;
the first complete measurement appears immediately. Later complete measurements
use the configured transition, including interrupted transitions. Removing the
location removes the layer and resets history. Radius zero also hides it, and
transparent color produces no pixels. Missing accuracy does not imply a full
circle or a fabricated error. Applications can disable sectors with a zero
radius.

The sector lies above the horizontal accuracy circle and below all images. It
rotates with geographic bearing, uses the images' perspective compensation, and
has no image tilt displacement. Radius is in logical pixels, independent of the
location accuracy in meters. Alpha fades outward with the Native shader's smooth
radial falloff. Sectors never add hit targets; only top and bearing images do.

## Alternatives

- A bitmap wedge cannot express measured uncertainty without regenerating
  images, and duplicates the native sector implementation.
- A separate sector composable or configuration object splits one indicator's
  measurement and rendering ownership without a separate lifecycle to justify
  it.
- Float degrees/pixels lose the units the public API already uses. An expression
  for the measured angular error complicates the common state/value path; zoom
  belongs in visual radius and color here. Native's internal setter still
  accepts expressions, as the upstream property does.
- Defaulting the radius to zero makes available uncertainty invisible until an
  application discovers an additional setting. Use a modest visible default;
  missing measurements still hide it.
- Three timing parameters add independent knobs without an identified use case.
  A single sector transition keeps its shape and appearance synchronized.

Compatibility does not constrain these choices. No aliases or new overloads are
needed. Existing location, camera, and projection ownership stays out of scope.

## Implementation and verification

The source contract is patch `0021-location-indicator-bearing-accuracy.patch` at
FFI tag `bindings/kotlin/v0.202609.3`. It specifies half-width degrees (0–180),
logical-pixel radius, color, zoom expressions, and transitions. The patch also
supplies the shader mask and draw order used for JS parity.

Compile the new properties through the existing layer DSL and set their
transition before each target. JS will evaluate zoom styling with the pinned
style-spec expression evaluator, interpolate updates with Native easing, and
draw a quad through its existing projection shader. Keep hit feedback
image-only. No global state, camera animation changes, or new
rendered-projection paths.

Extend composition tests for paired selection, loss/recovery, and timing. Extend
native property tests and add framebuffer assertions for a sector without
images. Add real Chromium/Firefox rendering checks for half-width, radial fade,
rotation, zoom styling, transitions, zero/full-circle behavior, and
non-interactivity. Reuse existing fixtures and target representative behavior
rather than platform matrices. Run mise static/parity checks, desktop tests,
browser tests, and documentation validation serializing all Gradle invocations.
Publish a draft against `sargunv/bindings-kotlin-v0.202609.3` (#1429), pending
human review.
