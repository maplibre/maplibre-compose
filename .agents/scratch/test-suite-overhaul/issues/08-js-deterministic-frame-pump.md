# 08: Drive JS live tests with a deterministic frame pump

**What to build:** Replace `waitUntilMap` wall-clock loops that depend on
`requestAnimationFrame` with the same explicit `pump` / `pumpUntil` contract
`MapFixture` already uses. An idle CI machine must fail as an assertion, not as
a timeout.

**Blocked by:** 01

**Type:** task

**Status:** resolved

`AGENTS.md` already warns that browser tests die as timeouts when the machine
idles. `GlJsMapFixture` pumps frames; some `jsTest` helpers still wait on real
`setTimeout`.

- [x] `BrowserMapTest.waitUntilMap` counts yields, still calls `yieldToBrowser`,
      and fails with an `AssertionError` that includes the last diagnostics.
- [x] Compose-hosted callers pass presentation nullness, style load state, and
      any error or event lists they already track.
- [x] `waitUntilMap` draws a `GlJsFrameTarget.Detached` frame from a published
      `GlJsMapSession` only after Compose has applied a non-empty extent.
- [x] Do not add a longer timeout as the fix.
- [x] `BrowserCompositingTest` stays layer 5. `CompositedMap.drawUntil` stays on
      a real WebGL target, counts frames, and reports the layer ids and last
      load error on timeout.

## Test ledger

- [x] A case that never gets a style reports the missing event, not
      `ChromeHeadless` idle.
- [x] `mise run test:js` still covers compositing, style failure, and platform
      map access.

## Answer

`waitUntilMap` still yields through a real `setTimeout` so MapLibre's promises
and timers can run. Timeout is still measured. The failure is an
`AssertionError` that reports the frame count plus the last presentation, style
load state, and any extra dump the caller supplied.

Compose-hosted maps use `DetachedGlJsCompositor`, not `GlJsMapFixture`. After
Compose has applied a viewport, `pumpPublishedDetachedFrame` calls
`GlJsMapSession.render(GlJsFrameTarget.Detached, extent)` so a stalled
`requestAnimationFrame` does not have to be the only way a detached map
advances. `BrowserCompositingTest` still draws into a real framebuffer.
