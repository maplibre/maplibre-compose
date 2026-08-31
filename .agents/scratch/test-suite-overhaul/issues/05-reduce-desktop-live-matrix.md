# 05: Run the live desktop suite once per backend

**What to build:** Full `jvmTest` (layers 3–5) on one runner per render backend.
ARM runners that package the same backend as their x64 sibling run cheap tests
plus OS-specific classes only.

**Blocked by:** 09

**Type:** task

**Status:** ready-for-human

Suggested first cut, after ticket 09 exists:

| Runner           | Live suite            | Also runs                            |
| ---------------- | --------------------- | ------------------------------------ |
| ubuntu-24.04     | yes (Vulkan)          | all                                  |
| macos-26         | yes (Metal)           | all                                  |
| windows-2022     | yes (Windows backend) | all                                  |
| ubuntu-24.04-arm | no                    | unit + Linux location                |
| windows-11-arm   | no                    | unit + Windows location + D3D layout |

Keep `LinuxVulkanOpenGlInteropTest` on Linux x64. It is the Linux EGL reuse
contract, not a generic live-map copy.

## Test ledger

- One push still executes every live native class on Vulkan and on Metal.
- Windows live coverage stays on windows-2022 until someone names a Windows
  ARM-only live contract.
