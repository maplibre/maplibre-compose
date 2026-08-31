# 08: Drive JS live tests with a deterministic frame pump

**What to build:** Replace `waitUntilMap` wall-clock loops that depend on
`requestAnimationFrame` with the same explicit `pump` / `pumpUntil` contract
`MapFixture` already uses. An idle CI machine must fail as an assertion, not as
a timeout.

**Blocked by:** 01

**Type:** task

**Status:** ready-for-agent

`AGENTS.md` already warns that browser tests die as timeouts when the machine
idles. `GlJsMapFixture` pumps frames; some `jsTest` helpers still wait on real
`setTimeout`.

- `BrowserMapTest.waitUntilMap` should pump the fixture or fail with the last
  events and errors.
- Do not add a longer timeout as the fix.
- `BrowserCompositingTest` stays layer 5 and may still need a real WebGL turn;
  bound it and dump the render tree on failure.

## Test ledger

- A case that never gets a style reports the missing event, not `ChromeHeadless`
  idle.
- `mise run test:js` still covers compositing, style failure, and platform map
  access.
