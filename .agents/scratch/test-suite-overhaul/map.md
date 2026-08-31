# Test suite overhaul map

| #  | Ticket                                                | Type | Status          |
| -- | ----------------------------------------------------- | ---- | --------------- |
| 01 | Document the test layers                              | task | resolved        |
| 02 | Split Android host CI from the emulator jobs          | task | resolved        |
| 03 | Stop using pixels for wrapper contracts               | task | claimed         |
| 04 | Move cheap tests out of live source sets              | task | ready-for-agent |
| 05 | Run the live desktop suite once per backend           | task | ready-for-human |
| 06 | Isolate process-global native and cache tests         | task | ready-for-agent |
| 07 | Host remaining FFI surface tests on FakeMlnFfiMapHost | task | ready-for-agent |
| 08 | Drive JS live tests with a deterministic frame pump   | task | ready-for-agent |
| 09 | Add a desktop unit Gradle filter                      | task | ready-for-agent |
| 10 | Decide Material 3 test coverage                       | task | ready-for-human |

Spec: [spec.md](spec.md). ADR:
[0001-test-suite-layers.md](../../docs/adr/0001-test-suite-layers.md).
