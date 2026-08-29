# 16: Provide confined platform-map access

**What to build:** Add an opt-in suspending escape hatch that runs a caller
lambda on the engine owner context without exposing a reusable raw platform
handle.

**Blocked by:** 04: Retain native engine maps between presentations; 05: Replay
Web maps between presentations; 07: Put viewport-bound behavior on
MapPresentation

**Status:** ready-for-agent

- [ ] A caller can invoke platform access from any coroutine dispatcher.
- [ ] The lambda executes on the engine map's owner context.
- [ ] The API shape prevents the platform handle from remaining usable after the
      lambda returns.
- [ ] Native access creates the engine map lazily when necessary.
- [ ] Native access works while MapState has no presentation.
- [ ] Web access works only for the current attached presentation.
- [ ] Web access fails clearly while detached.
- [ ] Closure and departed leases reject platform access.
- [ ] Platform tests verify owner-context execution and handle confinement.
