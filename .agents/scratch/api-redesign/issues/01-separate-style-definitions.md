# 01: Separate reusable style definitions from loaded styles

**What to build:** Make layer, source, and image definitions reusable values
that do not contain live map state. Collapse StyleBinding, MlnFfiStyleBinding,
and the native session binding into one loaded-style port contract. Give each
loaded style one opaque identity so that internal style operations cannot target
a replacement style or another map.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] The same immutable definition can be evaluated for two maps without shared
      mutable binding state.
- [x] Native and Web implement one loaded-style port contract without another
      platform or session interface layer.
- [x] One opaque identity represents each loaded base-style generation.
- [x] Base-style requests have independent monotonic identities, and callbacks
      from superseded requests cannot publish state.
- [x] Starting a base-style reload invalidates operations from the outgoing
      identity.
- [x] A stale internal style operation fails clearly and cannot mutate the next
      style or another map.
- [x] Pending, Loading, Ready, and Failed distinguish desired configuration from
      the current applied style without rolling back after failure.
- [x] Existing declarative style behavior remains unchanged.
- [x] Tests that exist only for the three binding layers are deleted or replaced
      with loaded-style port behavior tests.
- [x] Style-spec parity and focused style tests pass.

## Test ledger

- Replace `RecordingStyleBinding.kt` and `FakeStyle.kt` with tests of immutable
  definitions and the single loaded-style port.
- Rewrite `StyleOwnershipTest.kt`, `StyleCompositionOrderTest.kt`,
  `MlnFfiStyleSwitchTest.kt`, and `BrowserStyleStateTest.kt` around style
  identities; delete cases that only distinguish the three old binding layers.
- Run `mise run style-spec:parity --check`, `mise run test:android`,
  `mise run test:desktop`, and `mise run test:js`.

## Answer

`Source`, `Layer`, and image definitions are reusable values without loaded-map
state. `StyleBinding` is the single loaded-style port implemented directly by
the native and Web engines, and internal `SourceHandle` and `LayerHandle`
instances bind mutations to one opaque style generation.

Definitions snapshot mutable GeoJSON collections and image pixels. Custom-source
provider changes publish new definitions, while the installed handle stores the
only live forwarding reference for its style generation.

Tests for the removed descriptor attachment, proxy, feature-state, cluster, and
invalidation machinery were deleted. The remaining tests cover immutable
definitions, style identities, stale-handle rejection, and declarative behavior
through the single port. Focused native coverage retains the observable GeoJSON
recomposition, repaint, and rendered-update behavior.
