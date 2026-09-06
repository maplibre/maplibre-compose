#include "jni_handles.h"

#include <limits>
#include <vulkan/vulkan.h>

static_assert(handle_to_jlong(uint64_t{0x123456789abcdef0}) == 0x123456789abcdef0LL);
static_assert(
  handle_to_jlong(uint64_t{0x8000000000000000}) ==
  std::numeric_limits<jlong>::min()
);
static_assert(handle_to_jlong(uint64_t{0xffffffffffffffff}) == -1);
static_assert(sizeof(VkSurfaceKHR) == sizeof(jlong));

#if !VK_USE_64_BIT_PTR_DEFINES
static_assert(
  handle_to_jlong(VkSurfaceKHR{0x123456789abcdef0}) == 0x123456789abcdef0LL
);
static_assert(
  handle_to_jlong(VkSurfaceKHR{0x8000000000000000}) ==
  std::numeric_limits<jlong>::min()
);
static_assert(handle_to_jlong(VkSurfaceKHR{0xffffffffffffffff}) == -1);
#endif
