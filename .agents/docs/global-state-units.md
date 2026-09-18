# Unit conversion exploration

Stacked on global-state PR #1432. Keep this note in commit history, then remove
it from the final diff with the other additive documentation.

`SymbolLayer` converts text size from SP to DP using font scale. Text offsets
use EM, so SP offsets divide by unscaled text size; DP offsets also divide by
font scale. That text-size expression can read feature properties or global
state. Global-state values cannot contain executable expressions.

A hidden global font scale would replace a literal with a lookup, not simplify
the expression. It would also need key ownership, initialization before layer
installation, replay after reload, snapshot handling, and support for different
`LocalDensity` values around individual layers. Do not add that state model for
this change. Applications can already use a global-state number as text size.

Instead, fold numeric unit-conversion products and ratios when both operands are
finite literals and the result is finite. Keep division by zero and dynamic
operands as expressions. Constant text offsets should compile to literal arrays
rather than semiliteral arrays of arithmetic. Do not rewrite generic user math,
remove runtime type assertions, or change SP/DP/EM behavior.

Validate literal output and dynamic dependencies in focused unit tests. Extend
symbol composition coverage for scoped font scales and global-state text size.
Use a real map to check that changing global text size updates symbol layout
without rewriting the layer expressions. Run relevant desktop and JS suites
serially, plus formatting checks. No additive guide or signature-restating KDoc.
