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

## Evidence and validation plan

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
support; its JNI bridge comes from the FFI AAR. Verify the final APKs, including
all transitive native libraries, rather than relying only on the dependency
graph or ABI filters.

Add a mise task and CI coverage to build both runtime variants and audit their
packaged native libraries for every supported ABI. Check ELF architecture and
`DT_NEEDED` closure against the Android system libraries and APK contents.
Compile the real Vulkan shim for ARMv7, including assertions for high 64-bit
handle values. Keep build/package evidence separate from device evidence.

Runtime acceptance requires a process with `Process.is64Bit() == false` and
ARMv7 libraries, rendered maps/snapshots, surface replacement, and teardown.
Existing Android device tests cover rendering and lifecycle with OpenGL; they do
not prove the Vulkan backend. No device was connected at audit start; the
installed emulator images are ARM64 or x86-64. Investigate an isolated emulator
without using another worktree's device.

The original 1–2 engineering day estimate remains reasonable for implementation
and review. Compatible 32-bit device availability may add calendar time. musl
Linux and unrelated runtime/lifecycle redesigns are outside this change.
