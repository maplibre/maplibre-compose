# 11: Live Compose waits dump diagnostics

**What to build:** Every live Compose wait uses one helper. Timeout is an
`AssertionError` that reports presentation, style load state, attach count, and
layer ids. A hang of one minute with no dump is a bug in the helper.

**Blocked by:** 08

**Type:** task

**Status:** resolved

Live GPU coverage stays. Reliability is a wait that fails as one assertion with
a dump, or that passes. Compose `waitUntil` already pumps frames. It does not
report map state. JS `waitUntilMap` already dumps presentation and style.

- [x] `waitUntilLive` wraps Compose `waitUntil` and appends
      `liveWaitDiagnostics`.
- [x] Composition, style-switch, layer-click, desktop host, and Android
      recreation waits call `waitUntilLive`.
- [x] Recognition and FakeHost waits stay on Compose `waitUntil`. They do not
      create a live map.
- [x] A cheap case proves a timeout message includes the dump. It does not open
      a GPU.

## Test ledger

- [x] `liveWaitDiagnostics` on a null state reports `presentation=null`.
- [x] `waitUntilLive` on a false condition throws `AssertionError` with the
      condition text and the dump.

## Answer

`waitUntilLive` lives in `maplibreNativeTest`. Timeout is an `AssertionError`
that reports presentation, style load state, attach count, and layer ids. Each
call pings the hang watchdog so a long test with many waits is not killed from
process start. `LiveWaitTest` covers the dump on the JVM without a GPU.
`MapInputRecognitionTest` and FakeHost waits stay on Compose `waitUntil`.
