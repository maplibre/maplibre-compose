# 02: Split Android host CI from the emulator jobs

**What to build:** Run `mise run test:android` in its own job, once per push,
without booting an emulator. Run device tests in an API-level matrix that only
boots the emulator. Keep the release APK and `test:publishing` on the host job.

**Blocked by:** None

**Type:** task

**Status:** resolved

- [x] `android-host` runs host tests, publishing, and the demo APK.
- [x] `android-device` runs only `mise run test:android:device` for API 24 and
      API 36.
- [x] `all-good` waits on both jobs.
- [x] Trunk variants stay `android-host` and `android-device-api-*`.

## Answer

The combined `android` job ran host tests twice and retried them whenever API 24
failed to install the device APK. Host and device now fail and retry
independently.
