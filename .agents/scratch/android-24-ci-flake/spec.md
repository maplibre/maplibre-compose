# Android 24 CI flake

**Type:** research

The `android (24)` job fails often enough to stall pull requests. Three
mitigations are already in the tree. They did not stop the failure. This spec
records why, and the change that removes the failing path.

GitHub issue: https://github.com/maplibre/maplibre-compose/issues/1047

## Two failure classes

`android (24)` is not one flake. The job runs host tests, then boots an API 24
emulator, then installs several 20 MB JNI test APKs through UTP/ddmlib, then
runs the live-map suite on Nougat plus SwiftShader.

| Class                         | When                                                   | Signature                                                                                   | Fixed by retries?                     |
| ----------------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------- | ------------------------------------- |
| Session-install hang          | After Gradle starts device tests, before any test body | `ShellCommandUnresponsiveException`, `Failed to install-write all apks`, `Starting 0 tests` | Almost never                          |
| Live-map assertion or timeout | After tests start                                      | A named `Execute org.maplibre... FAILED` line                                               | No. Those are test bugs or GPU timing |

#856, #987, and #1056 targeted the install hang. Recent feature-branch failures
are often the second class (`MlnFfiMapInputTest` timeouts, style-switch
timeouts, `AndroidSurfaceReplacementTest`). Those need test fixes, not another
emulator reboot. This spec covers the install hang, which is the API 24
infrastructure failure.

## What the hang is

UTP installs APKs through ddmlib `Device.installPackages`. On API 21 and above
that method opens a Package Manager session and streams APK bytes into:

```
cmd package install-write -S <size> <session> <name> -
```

ddmlib waits for that shell command to print a result. After about four minutes
with no output it throws `ShellCommandUnresponsiveException` and reports
`Starting 0 tests`.

The guest is still up. Diagnostics from
https://github.com/maplibre/maplibre-compose/actions/runs/32618867450/job/97143829731
show:

- `adb get-state` is `device`
- `/data` has gigabytes free
- guest RAM is not exhausted
- host RAM and disk are not exhausted
- no emulator crash, OOM, or ANR
- the abandoned session is `sizeBytes=20508470`, `mClientProgress≈1.0`,
  `mProgress=0.8`, `mFinalStatus=-115` (`INSTALL_FAILED_ABORTED`)

The host finished sending bytes. The API 24 Package Manager never finished the
streamed write. `-115` is the cleanup status after ddmlib abandoned the session,
not the original stall.

API 36 jobs in the same window never produced this signature. The hang is the
API 24 guest's streamed-install path, not Gradle, not KVM, and not the test
code.

## Why the mitigations failed

### #856: diagnostics

The capture is useful. It is not a fix. The retry in #987 reboots the emulator
before the workflow runs the capture, so the uploaded guest state is the second
attempt. The first hang is gone.

### #987: ABI filter plus one reboot retry

Device-test APKs keep one JNI ABI. That cut the APK to about 20 MB. The session
still hangs at that size.

The retry reboots the AVD and runs the same UTP install again. Issue #1047
counted completed API 24 jobs from the #987 merge (2026-08-19 21:11 UTC) through
2026-08-23 05:13 UTC:

- 142 completed API 24 jobs
- 7 install hangs
- 1 recovered on retry
- 6 hung again after the cold restart

A later hang
(https://github.com/maplibre/maplibre-compose/actions/runs/32618867450/job/97143829731)
shows the same four-minute stall on both attempts. A reboot does not change the
install path, so it does not change the outcome.

### #1056: rerun one failed CI job

The workflow reruns a job only when exactly one primary job failed on attempt

1. An install hang that already retried inside the job fails again on the
   workflow retry, because that retry is the same streamed `install-write`. When
   `android (24)` fails together with another job, the workflow does nothing.

### Timeouts and `installOptions`

Raising `ADB_INSTALL_TIMEOUT` or `installation.timeOutInMs` waits longer for a
stream that never completes.

`installation { installOptions += "--no-streaming" }` does not select the adb
push path. `--no-streaming` is an `adb install` flag. UTP passes
`installOptions` to `pm` / `cmd package` through ddmlib, which always uses
session streaming on API 21+. `pm` does not implement `--no-streaming`.

Wrapping the `adb` binary also does nothing. ddmlib talks to the adb server
socket and issues `install-write` itself.

## Recommended fix

Stop using the API 24 emulator as a full-suite CI device.

Keep `android-minSdk = 24`. Compilation and Lint still enforce the floor. Move
the old device-test matrix entry from API 24 to API 26 (Oreo). Keep API 36 as
the current device.

API 26 is the closest published image whose Package Manager completes a 20 MB
streamed UTP install in this job. API 25 is still Nougat. API 28 is further from
the floor. If API 26 hangs the same way, move the old leg to API 28 rather than
adding retries.

Do not keep API 24 and try to out-retry the Package Manager. A true API 24
device run needs a different installer: `adb install --no-streaming` (push, then
`pm install` of a file on the guest) and an instrumentation driver that does not
call ddmlib `installPackages`. That is a new test runner. It is the right
follow-up only if a maintainer requires a live Nougat map, not a prerequisite
for a green old-device job.

## Out of scope

Live-map flakes that run after a successful install (`pitched_fling_*` on both
Android jobs, `MlnFfiMapInputTest` 1-minute timeouts, surface-replacement missed
frames) are separate. They belong with #1033 and the test-suite work, not with
the install hang.
