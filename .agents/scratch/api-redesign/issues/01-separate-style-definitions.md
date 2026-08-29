# 01: Separate reusable style definitions from loaded styles

**What to build:** Make layer, source, and image definitions reusable values
that do not contain live map state. Collapse StyleBinding, MlnFfiStyleBinding,
and the native session binding into one loaded-style port contract. Give each
loaded style one opaque identity so that internal style operations cannot target
a replacement style or another map.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] The same immutable definition can be evaluated for two maps without shared
      mutable binding state.
- [ ] Native and Web implement one loaded-style port contract without another
      platform or session interface layer.
- [ ] One opaque identity represents each loaded base-style generation.
- [ ] Base-style requests have independent monotonic identities, and callbacks
      from superseded requests cannot publish state.
- [ ] Starting a base-style reload invalidates operations from the outgoing
      identity.
- [ ] A stale internal style operation fails clearly and cannot mutate the next
      style or another map.
- [ ] Pending, Loading, Ready, and Failed distinguish desired configuration from
      the current applied style without rolling back after failure.
- [ ] Existing declarative style behavior remains unchanged.
- [ ] Tests that exist only for the three binding layers are deleted or replaced
      with loaded-style port behavior tests.
- [ ] Style-spec parity and focused style tests pass.

## Test ledger

- Replace `RecordingStyleBinding.kt` and `FakeStyle.kt` with tests of immutable
  definitions and the single loaded-style port.
- Rewrite `StyleOwnershipTest.kt`, `StyleCompositionOrderTest.kt`,
  `MlnFfiStyleSwitchTest.kt`, and `BrowserStyleStateTest.kt` around style
  identities; delete cases that only distinguish the three old binding layers.
- Run `mise run style-spec:parity --check`, `mise run test:android`,
  `mise run test:desktop`, and `mise run test:js`.
