# Text-unit compilation cleanup

Extend #1434, not a separate PR. Fold only arithmetic introduced by unit
conversion; leave application arithmetic intact. Compile operands before folding
so deferred text units resolve in the correct target context. Remove identity
scales, but do not annihilate dynamic operands with zero or reassociate ratios.
Fold only finite results representable without changing their serialized numeric
value; leave division by zero and inexact quotients to the engine.

Produce literal arrays for constant converted offsets. Retain the separate
text-size compilation contexts required by zoom interpolation. Keep mixed-unit
validation for properties without a conversion context; consolidate its repeated
logic instead of replacing a small guard with a new context hierarchy.

Extend expression-output coverage and preserve dynamic globals, feature values,
and top-level zoom interpolation. Run desktop and JS tests serially and static
checks. Remove this note from the tip after committing it to history.
