# 13: Isolate Android device tests with Orchestrator

**What to build:** Each `maplibre-compose` instrumented test runs in its own
instrumentation process. One native abort keeps logcat and leaves the rest of
the suite able to run. Retry stays infra-only.

**Blocked by:** 12

**Type:** task

**Status:** resolved

Android instrumentation today runs about 431 tests in one process. A hang
watchdog that calls `Process.killProcess` voids every remaining test and drops
logcat. Orchestrator starts a new process per method. The watchdog can still
kill that one process.

- [x] `maplibre-compose` device tests set
      `execution = "ANDROID_TEST_ORCHESTRATOR"`.
- [x] The orchestrator APK is an `androidTestUtil` dependency.
- [x] Do not set `clearPackageData`. Process isolation is enough.
      `resetForTest()` already clears Compose-side application state.
- [x] Do not widen `ci-retry.yml`. A live assertion flake is not infra.
- [x] Other Android library modules keep host execution. They do not host the
      431-test Compose plus FFI suite.

## Test ledger

- [x] The device-test Gradle configuration compiles with Orchestrator enabled.
- [x] `ci-retry.yml` still reruns only when exactly one primary job failed.

## Answer

`:lib:maplibre-compose` device tests set `execution` to
`ANDROID_TEST_ORCHESTRATOR` and depend on `androidx.test:orchestrator:1.6.1`.
The Android device job timeout is 90 minutes. Diagnostics now run when the
device-test step is not success, including a cancelled hang. `ci-retry.yml` is
unchanged.
