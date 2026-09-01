# 01: Move the old device-test emulator off API 24

**Type:** task

**Status:** resolved

**Blocked by:** none

Change the CI Android matrix old leg from API 24 to API 26. Leave
`android-minSdk` at 24.

## Answer

The matrix entry is `api-level: 26`. The job name becomes `android (26)`. Setup
still caches the emulator image per API level, so the first run on this key
installs the Oreo system image.
