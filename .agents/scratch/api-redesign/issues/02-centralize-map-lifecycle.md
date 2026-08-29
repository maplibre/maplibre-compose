# 02: Centralize the logical-map lifecycle

**What to build:** Route map creation, leased attachment, detachment, closure,
and platform-event acceptance through one serialized lifecycle authority. Keep
the existing public map behavior functional while establishing this internal
seam.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] One authority decides every engine-map and presentation transition.
- [ ] The authority implements OpenDetached, Attaching, Attached, Detaching,
      Closing, and Closed as explicit states.
- [ ] Platform adapters perform commands without maintaining a second
      authoritative attachment state.
- [ ] Each attachment receives an opaque render lease.
- [ ] Durable engine events use engine-map and style identities rather than a
      presentation lease.
- [ ] Presentation events and detach requests from departed leases have no
      effect.
- [ ] The closure commit point rejects new work immediately.
- [ ] Rival attachments fail without waiting during Attaching, Attached, and
      Detaching.
- [ ] Attach failure and detach-during-attach invalidate the lease, clean
      partial resources, and return to OpenDetached.
- [ ] Repeated close and child/runtime close races join one cleanup operation.
- [ ] Cleanup attempts every resource and awaitClosed reports collected cleanup
      failures.
- [ ] Physical cleanup continues after caller cancellation.
- [ ] Common tests cover every valid and refused lifecycle transition through a
      fake platform adapter.
- [ ] The fake emits each event family with its required identity, including
      durable engine and style events accepted while a native map is detached.
- [ ] Tests that assert obsolete locks, queues, callback storage, or duplicated
      session state are deleted rather than preserved beside the authority
      tests.
- [ ] Existing live-map behavior remains green on native and Web.

## Test ledger

- Add one common fake-adapter lifecycle suite covering the complete transition
  table, refusal paths, races, cancellation, and cleanup failures.
- Review `MlnFfiMapIdleTest.kt`, `MlnFfiSurfaceLossTest.kt`,
  `MlnFfiMapSurfaceRecoveryTest.kt`, and `BrowserMapLifecycleTest.kt`; retain
  only distinct engine-boundary cases after their shared semantics move to the
  common suite.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.
