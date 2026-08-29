# 02: Centralize the logical-map lifecycle

**What to build:** Route map creation, leased attachment, detachment, closure,
and platform-event acceptance through one serialized lifecycle authority. Keep
the existing public map behavior functional while establishing this internal
seam.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] One authority decides every engine-map and presentation transition.
- [ ] Platform adapters perform commands without maintaining a second
      authoritative attachment state.
- [ ] Each attachment receives an opaque render lease.
- [ ] Events and detach requests from departed leases have no effect.
- [ ] The closure commit point rejects new work immediately.
- [ ] Physical cleanup continues after caller cancellation.
- [ ] Common tests cover every valid and refused lifecycle transition through a
      fake platform adapter.
- [ ] Existing live-map behavior remains green on native and Web.
