# 18: Provide borrowed platform-map access

**What to build:** Add a delicate opt-in suspending escape hatch that runs a
caller lambda on the engine owner context and exposes the raw platform map as a
borrowed, callback-scoped value.

**Blocked by:** 04, 05, 07

**Status:** resolved

- [x] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [x] A caller can invoke platform access from any coroutine dispatcher.
- [x] The lambda executes on the engine map's owner context.
- [x] Documentation states honestly that Kotlin cannot prevent retention and
      requires callers to use the borrowed handle only during the lambda.
- [x] Native access creates the engine map lazily when necessary.
- [x] Native access works while MapState has no presentation.
- [x] Web access works only for the current attached presentation.
- [x] Web access fails clearly while detached.
- [x] Native invocations bind to an engine-map identity; Web invocations bind to
      both an engine-map identity and the current render lease.
- [x] Replacement, Web detachment, or closure that wins before execution rejects
      the invocation without running its callback.
- [x] Once a callback starts, detach, replacement, and closure queue behind it
      and continue after it returns.
- [x] Platform tests verify owner-context execution, native detached access, Web
      attached-only access, and rejection after closure.

## Test ledger

- Extend owner-context coverage from `MlnFfiOwnerThreadTest.kt` through the
  public access API and keep lower-level thread tests only for distinct FFI
  behavior.
- Add native detached, pre-execution engine replacement, Web detached or stale
  lease, callback-versus-close, and closed-state cases. Do not add a test
  claiming Kotlin can prevent raw-handle retention.
- Run `mise run test:android`, `mise run test:desktop`, `mise run test:ios`, and
  `mise run test:js`.

## Answer

`MapState.withPlatformMap` now provides delicate, callback-scoped access to the
raw native or Web map. Native calls create and retain an engine without a
presentation. Web calls require the current presentation. Both paths validate
the captured engine identity immediately before the callback; Web also validates
the render lease. A callback that has started finishes before detach,
replacement, or closure proceeds. Identity validation and callback delivery hold
both lifecycle serialization locks until the callback returns.

The native tests cover detached creation, owner-thread execution, replacement
before execution, closure after execution starts, and closed-state rejection.
The Web tests cover detached rejection, attached access, and stale-lease
rejection. The Android, desktop, and iOS tasks pass. The Web task runs all 263
tests: the three platform-access tests pass, while nine existing
`BrowserCompositingTest` cases time out waiting for rendered pixels on this
host.
