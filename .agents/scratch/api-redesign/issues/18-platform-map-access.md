# 18: Provide borrowed platform-map access

**What to build:** Add a delicate opt-in suspending escape hatch that runs a
caller lambda on the engine owner context and exposes the raw platform map as a
borrowed, callback-scoped value.

**Blocked by:** 04, 05, 07

**Status:** ready-for-agent

- [ ] The changed test area contains no redundant, impossible,
      compatibility-only, or implementation-shape scenarios.
- [ ] A caller can invoke platform access from any coroutine dispatcher.
- [ ] The lambda executes on the engine map's owner context.
- [ ] Documentation states honestly that Kotlin cannot prevent retention and
      requires callers to use the borrowed handle only during the lambda.
- [ ] Native access creates the engine map lazily when necessary.
- [ ] Native access works while MapState has no presentation.
- [ ] Web access works only for the current attached presentation.
- [ ] Web access fails clearly while detached.
- [ ] Native invocations bind to an engine-map identity; Web invocations bind to
      both an engine-map identity and the current render lease.
- [ ] Replacement, Web detachment, or closure that wins before execution rejects
      the invocation without running its callback.
- [ ] Once a callback starts, detach, replacement, and closure queue behind it
      and continue after it returns.
- [ ] Platform tests verify owner-context execution, native detached access, Web
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
