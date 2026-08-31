# Test suite overhaul map

| #  | Ticket                                                | Type | Status          |
| -- | ----------------------------------------------------- | ---- | --------------- |
| 01 | Document the test layers                              | task | resolved        |
| 02 | Split Android host CI from the emulator jobs          | task | resolved        |
| 03 | Stop using pixels for wrapper contracts               | task | resolved        |
| 04 | Move cheap tests out of live source sets              | task | resolved        |
| 05 | Cut desktop live suite to one runner per backend      | task | wontfix         |
| 06 | Isolate process-global native and cache tests         | task | resolved        |
| 07 | Host remaining FFI surface tests on FakeMlnFfiMapHost | task | resolved        |
| 08 | Drive JS live tests with a deterministic frame pump   | task | resolved        |
| 09 | Add a local desktop unit Gradle filter                | task | ready-for-agent |
| 10 | Decide Material 3 test coverage                       | task | wontfix         |

Keep the full live desktop suite on every architecture. Leave Material 3 without
tests in this overhaul.

Spec: [spec.md](spec.md). ADR:
[0001-test-suite-layers.md](../../docs/adr/0001-test-suite-layers.md).
