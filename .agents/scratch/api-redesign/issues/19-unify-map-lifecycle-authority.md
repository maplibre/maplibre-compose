# 19: Unify the map lifecycle authority

**What to build:** Correct the lifecycle split introduced when `MapState` was
added after the session lifecycle authority. Route logical presentation
reservations, engine transitions, closure, and callback acceptance through one
`MapState`-owned authority. Platform sessions execute commands for that
authority and retain no independent lifecycle state machine.

**Blocked by:** 14

**Status:** resolved

- [x] `MapState` owns the only lifecycle authority for its logical map.
- [x] Native and Web sessions receive that authority instead of creating one.
- [x] One serialized transition model decides presentation reservation,
      attachment, detachment, engine retention or replacement, and closure.
- [x] Platform callbacks use identities from the `MapState` authority.
- [x] Rival presentations and departed leases retain their specified behavior.
- [x] Native maps remain reusable across compatible presentations.
- [x] Web maps and incompatible native maps replace their engines without
      replacing `MapState`.
- [x] Closure collects every platform cleanup failure and completes once.
- [x] Common tests exercise public `MapState` behavior through fake adapters.
- [x] Native surface-loss and Web recreation tests retain their distinct engine
      coverage.
- [x] Static checks and the full supported platform test matrix pass.

## Test ledger

- Preserve the existing public `MapState` presentation and closure tests as the
  primary seam.
- Keep the physical engine and lease transition table on an authority-owned fake
  binding. The binding tests retain distinct coverage of races below the public
  API that presentation tests cannot replace.
- Retain native engine-retention and Web engine-replacement tests.
- Run `mise run check`, `mise run style-spec:parity --check`, and the complete
  platform matrix from the specification.

## Answer

`MapState` now owns presentation reservations, bound platform lifecycle
controllers, retained adapters, and closure. Native and Web sessions bind to
that owner instead of constructing an independent authority. The binding lock
serializes callback validation with delivery, while the owner lock stays
released across platform commands. This ordering prevents closure from racing an
accepted callback without creating cross-lock deadlocks. Versioned camera and
base-style writes replay the newest durable value when an older platform call
finishes late.

Static checks, style-spec parity, documentation, publishing, Android host and
device, iOS simulator and device, Web, and Desktop tests pass. The Desktop
matrix covers macOS, Linux x64 and Arm64, and Windows x64 and Arm64.
