# Android ARMv7 support

## Decision

Package `armeabi-v7a` alongside `arm64-v8a` and `x86_64` in the Vulkan loader
AAR. Keep the existing Android API 24 floor and explicit application runtime
selection. ARMv7 does not imply Vulkan hardware support: OpenGL needs OpenGL ES
3.0, and Vulkan needs a usable Vulkan driver. Do not add an ABI-based backend
fallback.

JNI transports pointer addresses and Vulkan non-dispatchable handles in Java
`long` values. Zero-extend pointers through `uintptr_t`. Preserve all 64 bits of
non-dispatchable handles without passing through a pointer-sized integer. Apply
the conversion to context pointers, Vulkan dispatchable handles, surface
handles, and function pointers. Keep the existing Kotlin `Long` transport and
FFI `VulkanHandle.ofBits` boundary.

## Dependency and packaging audit

The parent is PR #1291, `sargunv/mln-ffi-bump`, commit
`17ef8665a79af308d75b721ad6cdb79d78e39024`. Ancestry was verified before edits.
The published FFI `0.202609.0` binding and both Android runtime AARs contain
ARMv7, ARM64, and x86-64 libraries. The upstream release source is `a64390894`;
[OpenGL #658](https://github.com/maplibre/maplibre-native-ffi/pull/658) and
[Vulkan #660](https://github.com/maplibre/maplibre-native-ffi/pull/660)
introduced ARMv7 support and the separate 64-bit Vulkan handle contract.

The resolved Android graph uses AndroidX Compose UI 1.12.0 and graphics-path
1.0.1. Android renders through the platform Canvas; Skiko and LWJGL are absent
from the demo's Android runtime graph. JavaCPP 1.5.14 supplies the Java binding
support; its JNI bridge comes from the FFI AAR. The complete ARMv7 APK inventory
is:

| Library                         | Origin                                     | Backends |
| ------------------------------- | ------------------------------------------ | -------- |
| `libjniMaplibreNativeC.so`      | FFI Android binding 0.202609.0             | Both     |
| `libmaplibre-native-c.so`       | Selected FFI runtime 0.202609.0            | Both     |
| `libmaplibre_compose_vulkan.so` | This repository's JNI shim                 | Vulkan   |
| `libandroidx.graphics.path.so`  | AndroidX graphics-path 1.0.1               | Both     |
| `libTransform.so`               | Huawei LocationLiteSdk core 2.12.0.300     | Both     |
| `libucs-credential.so`          | Huawei ucs-credential-developers 1.0.4.312 | Both     |

All are ELF32 ARM binaries. Their `DT_NEEDED` entries resolve to APK libraries
or public NDK system libraries available at API 24. None requires an unpackaged
`libc++_shared.so`. This verifies library coverage, not symbol-level
compatibility with a running device's driver. The optional Huawei location
dependencies are part of the demos, not required by the map library.

AndroidX and Huawei also contribute x86 libraries, but FFI publishes no x86
binding/runtime. Restrict both demo APKs to the three supported ABIs so they do
not advertise a partial x86 installation. Library consumers with other native
dependencies must restrict their application ABIs too.

`mise run check:android-abis` builds Android and Wear OS debug APKs with each
backend, serially. It checks the three ABI directories, ARMv7 coverage of all
transitive native libraries, ELF class/machine, required MapLibre libraries, and
`DT_NEEDED` closure using the pinned NDK's API 24 system library stubs. The
Vulkan build compiles assertions against actual NDK handle types, including
nonzero upper bits, the sign bit, and all bits set. CI runs this task before its
existing x86-64 OpenGL device suite; CI does not run an ARMv7 process.

## Local validation

Validated on 2026-09-05 with published dependencies, NDK 28.2.13676358, CMake
4.1.2, and JDK 25 on macOS ARM64:

- `mise run check:android-abis`: passed for all four APKs and all three ABIs.
  The ARMv7 shim target is `armv7-none-linux-androideabi24`.
- `:lib:maplibre-compose-runtime-vulkan-android:bundleReleaseAar`: passed; the
  release AAR contains exactly the shim for ARMv7, ARM64, and x86-64, with ELF
  class/machine checked for each entry.
- The unchanged parent shim fails ARMv7 compilation at its `VkSurfaceKHR`
  reinterpret cast. A temporary mutation that narrows integer handles through
  `uintptr_t` fails the new high-bit assertions.
- The APK checker rejects temporary APK mutations with a missing ARMv7 shim,
  missing AndroidX ARMv7 library, ARM64 bytes under an ARMv7 path, or a missing
  `DT_NEEDED` library.
- `mise run test:android`: passed, 299 host tests. These do not render maps.
- `mise run lint:android`: passed.
- `mise run check`: passed.

Reports and APKs are under `build/reports/android-abis/`; additional compile,
release AAR, test, and emulator evidence is under
`build/reports/android-armv7/`.

## Runtime validation blocker

Runtime acceptance requires a process with `Process.is64Bit() == false` and
ARMv7 libraries, rendered maps/snapshots, surface replacement, and teardown.
Existing Android device tests cover rendering and lifecycle with OpenGL; they do
not prove the Vulkan backend. `adb devices -l` reported no connected device.
Installed ARM64 API 24 and 26 images explicitly declare an empty
`ro.product.cpu.abilist32`; they cannot supply 32-bit execution evidence.

Downloaded Google's official API 24 ARMv7 revision 7 image into this checkout,
verified its published SHA-1 (`3454546b4eed2d6c3dd06d47757d6da9f4176033`), and
attempted an isolated AVD on port 5586. Android emulator 36.6.11.0
(build 15507667) exits before boot:

```text
FATAL | CPU Architecture 'arm' is not supported by the QEMU2 emulator,
(the classic engine is deprecated!)
```

Requesting `-engine classic` produces the same rejection. Image metadata comes
from
[Google's system image catalog](https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml).
No other worktree's emulator or simulator was used. Neither OpenGL nor Vulkan
ARMv7 rendering, snapshots, surface recreation, or teardown is proven by this
PR. A physical ARMv7 device or ARM64 device with a 32-bit userspace is needed;
Vulkan additionally needs a compatible 32-bit driver.

## Remaining effort

Implementation and build validation are complete. Allow about half to one
engineering day for device setup, rendering/lifecycle validation of both
backends, and human review once compatible hardware is available; fixes found
on-device would add effort. Hardware availability adds calendar time. No major
product/API decision remains. Keep the PR draft until human review; retain the
runtime limitation until device evidence exists. musl Linux is outside scope.
