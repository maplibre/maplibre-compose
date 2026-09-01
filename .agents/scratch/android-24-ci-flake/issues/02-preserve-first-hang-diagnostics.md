# 02: Capture guest state before a session-install retry

**Type:** task

**Status:** resolved

**Blocked by:** none

When `run-android-device-tests` sees a session-install hang, write diagnostics
into `diagnostics/attempt-1` before `stop-android-emulator`. The workflow upload
already includes that tree.

## Answer

The runner calls `capture-android-emulator-diagnostics attempt-1` before the
reboot. A later workflow capture still writes the retry guest into
`diagnostics/`.
