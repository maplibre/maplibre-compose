#pragma once

#include <cstdint>
#include <type_traits>

#include <jni.h>

// Non-dispatchable Vulkan handles remain 64-bit on 32-bit Android.
template <typename Handle>
constexpr auto handle_to_jlong(Handle handle) -> jlong {
  if constexpr (std::is_pointer_v<Handle>) {
    static_assert(sizeof(uintptr_t) <= sizeof(uint64_t));
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(handle));
  } else {
    static_assert(std::is_same_v<Handle, uint64_t>);
    return static_cast<jlong>(handle);
  }
}
