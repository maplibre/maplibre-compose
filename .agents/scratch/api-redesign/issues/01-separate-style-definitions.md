# 01: Separate reusable style definitions from loaded styles

**What to build:** Make layer, source, and image definitions reusable values
that do not contain live map state. Collapse StyleBinding, MlnFfiStyleBinding,
and the native session binding into one loaded-style port contract. Give each
loaded style one opaque identity so that internal style operations cannot target
a replacement style or another map.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] The same immutable definition can be evaluated for two maps without shared
      mutable binding state.
- [ ] Native and Web implement one loaded-style port contract without another
      platform or session interface layer.
- [ ] One opaque identity represents each loaded base-style generation.
- [ ] Starting a base-style reload invalidates operations from the outgoing
      identity.
- [ ] A stale internal style operation fails clearly and cannot mutate the next
      style or another map.
- [ ] Existing declarative style behavior remains unchanged.
- [ ] Style-spec parity and focused style tests pass.
