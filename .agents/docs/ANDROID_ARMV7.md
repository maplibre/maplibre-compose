# Android ARMv7 support

## Design

Add `armeabi-v7a` to the existing Vulkan loader AAR alongside `arm64-v8a` and
`x86_64`. Keep API 24 and explicit runtime selection: OpenGL needs OpenGL ES
3.0, and Vulkan needs a compatible device and driver. No ABI-based fallback or
public API change is needed.

JNI carries pointer addresses and non-dispatchable Vulkan handles in Java `long`
values. Zero-extend pointers through `uintptr_t`; convert integer handles
directly to `jlong` so ARMv7 retains all 64 bits. C++20 signed integer
conversion preserves the value modulo 2^64. The Kotlin `Long` transport and FFI
`VulkanHandle.ofBits` boundary already preserve those bits.

Compile-time assertions cover nonzero upper bits, the sign bit, and all bits set
against the actual NDK Vulkan types. Existing Android Lint and publishing tasks
compile the shim for its configured ABIs. No separate ABI validation task or
dependency-audit framework is needed.

## Dependency audit

Audited published FFI 0.202609.0, based on upstream `a64390894`, including
[OpenGL #658](https://github.com/maplibre/maplibre-native-ffi/pull/658) and
[Vulkan #660](https://github.com/maplibre/maplibre-native-ffi/pull/660). Android
uses AndroidX Compose UI 1.12.0 and the platform Canvas; Skiko and LWJGL are
absent from the Android runtime graph. JavaCPP 1.5.14 supplies Java support; its
JNI bridge comes from the FFI binding AAR.

The phone/TV and Wear OS demo APKs contain these ARMv7 libraries:

| Library                         | Origin                                     |
| ------------------------------- | ------------------------------------------ |
| `libjniMaplibreNativeC.so`      | FFI Android binding 0.202609.0             |
| `libmaplibre-native-c.so`       | Selected FFI runtime 0.202609.0            |
| `libmaplibre_compose_vulkan.so` | This repository; Vulkan only               |
| `libandroidx.graphics.path.so`  | AndroidX graphics-path 1.0.1               |
| `libTransform.so`               | Huawei LocationLiteSdk core 2.12.0.300     |
| `libucs-credential.so`          | Huawei ucs-credential-developers 1.0.4.312 |

All are ELF32 ARM. Their `DT_NEEDED` entries resolve to packaged libraries or
public API 24 NDK system libraries; none needs an unpackaged `libc++_shared.so`.
This was a one-time APK audit, not driver or runtime validation. Huawei location
dependencies are optional demo dependencies, not map requirements.

AndroidX and Huawei also ship x86 libraries, but FFI does not. Restrict both
demo APKs to the supported ABIs to avoid partial x86 installations. Consumers
with other native dependencies must likewise restrict their application ABIs.

## Validation and limitations

Local validation on macOS ARM64 with NDK 28.2.13676358 and CMake 4.1.2:

- Compiled and linked the real shim for all three ABIs in debug and release.
- Inspected all four demo APKs (Android/Wear OS, OpenGL/Vulkan) and the release
  Vulkan AAR for native-library coverage and ELF architecture.
- The parent shim fails ARMv7 compilation at its surface cast. Deliberately
  narrowing integer handles through `uintptr_t` fails the high-bit assertions.
- Android host tests: 299 passed. Android Lint and static checks passed locally.

The first Linux CI run crashed NDK Clang 19 while generating the
`getInstanceProcAddr` JNI function with `std::bit_cast`. Use ordinary C++20
integer conversions; no bit reinterpretation helper is needed for integers.

ARMv7 rendering, snapshots, surface recreation, and teardown remain unverified.
No compatible device is connected. Installed ARM64 API 24/26 emulator images
have an empty `ro.product.cpu.abilist32`. Google's official API 24 ARMv7 image
(revision 7, verified SHA-1 `3454546b4eed2d6c3dd06d47757d6da9f4176033`) was
tried in an isolated AVD. Emulator 36.6.11.0 exits before boot with:

```text
CPU Architecture 'arm' is not supported by the QEMU2 emulator,
(the classic engine is deprecated!)
```

`-engine classic` gives the same rejection. No other worktree's device was used.
Runtime acceptance needs an ARMv7 process on compatible hardware, with both
backend lifecycles exercised. Allow half to one engineering day for this and
human review once hardware is available, plus fixes found on-device. There are
no remaining product/API decisions. musl Linux is outside scope.
