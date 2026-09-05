#pragma once

#include <cstdint>
#include <type_traits>

#include <jni.h>

// Dispatchable Vulkan handles and function/context pointers are pointer-sized.
// Non-dispatchable Vulkan handles are pointers on 64-bit Android, but uint64_t
// on 32-bit Android. Java long must retain their bits in both representations.
template <typename Handle>
constexpr auto handle_to_jlong(Handle handle) -> jlong {
  if constexpr (std::is_pointer_v<Handle>) {
    static_assert(sizeof(uintptr_t) <= sizeof(uint64_t));
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(handle));
  } else {
    // C++20 integral conversion preserves the value modulo 2^64.
    static_assert(std::is_same_v<Handle, uint64_t>);
    return static_cast<jlong>(handle);
  }
}
