# 02: Centralize the logical-map lifecycle

**What to build:** Route map creation, leased attachment, detachment, closure,
and platform-event acceptance through one serialized lifecycle authority. Keep
the existing public map behavior functional while establishing this internal
seam.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] One authority decides every engine-map and presentation transition.
- [x] The authority implements OpenDetached, Attaching, Attached, Detaching,
      Closing, and Closed as explicit states.
- [x] Platform adapters perform commands without maintaining a second
      authoritative attachment state.
- [x] Each attachment receives an opaque render lease.
- [x] Durable engine events use engine-map and style identities rather than a
      presentation lease.
- [x] Presentation events and detach requests from departed leases have no
      effect.
- [x] The closure commit point rejects new work immediately.
- [x] Rival attachments fail without waiting during Attaching, Attached, and
      Detaching.
- [x] Attach failure and detach-during-attach invalidate the lease, clean
      partial resources, and return to OpenDetached.
- [x] Repeated close and child/runtime close races join one cleanup operation.
- [x] Cleanup attempts every resource and awaitClosed reports collected cleanup
      failures.
- [x] Physical cleanup continues after caller cancellation.
- [x] Common tests cover every valid and refused lifecycle transition through a
      fake platform adapter.
- [x] The fake emits each event family with its required identity, including
      durable engine and style events accepted while a native map is detached.
- [x] Tests that assert obsolete locks, queues, callback storage, or duplicated
      session state are deleted rather than preserved beside the authority
      tests.
- [x] Existing live-map behavior remains green on native and Web.

## Test ledger

- Add one common fake-adapter lifecycle suite covering the complete transition
  table, refusal paths, races, cancellation, and cleanup failures.
- Review `MlnFfiMapIdleTest.kt`, `MlnFfiSurfaceLossTest.kt`,
  `MlnFfiMapSurfaceRecoveryTest.kt`, and `BrowserMapLifecycleTest.kt`; retain
  only distinct engine-boundary cases after their shared semantics move to the
  common suite.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.

## Answer

Draft PR #1144 implements the centralized lifecycle authority and routes the
native and Web sessions through it. Common lifecycle tests cover the transition
table, refusal paths, races, cancellation, event identities, and cleanup
failures.
