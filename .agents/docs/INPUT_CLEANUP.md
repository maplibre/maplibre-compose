# Remaining input cleanup

Temporary checklist, approved by the maintainer. Remove when complete.

- [ ] Keep momentum on camera pan/zoom/rotate/tilt only. Remove per-binding
      momentum, override types, inherited-value builders, and tests of the
      removed contract.
- [ ] Separate callback storage from immutable settings internally; preserve
      public camera.pan.onStart. Use settings equality for input restarts.
      Resolve exact keyboard reachability once per configuration, not on key
      events.
- [ ] Encapsulate camera token state. Consolidate command checks across backends
      where semantics match. Preserve enqueue/execution checks, callback
      reentrancy checks, native event draining, and JS transition behavior.
      Avoid a generic backend framework.
- [ ] Extract tap pairing and move its timing/state tests out of Compose UI
      tests. Keep integration tests for consumption and quick zoom. Split
      remaining UI tests by input family without adding duplicate coverage or
      fixture frameworks.
- [ ] Add a short implementation overview explaining remaining camera lifetimes,
      cancellation, queued commands, and delayed-click invalidation. No Compose
      tutorial.
- [ ] Validate affected JVM, browser, Android host/device, docs and static
      checks; inspect final diff for unnecessary machinery, remove this
      checklist, commit and push.

Do not move public camera start hooks into click callbacks, add public builder
base classes, replace exact keyboard matching with approximations, or merge
distinct backend completion behavior merely to reduce textual duplication.
