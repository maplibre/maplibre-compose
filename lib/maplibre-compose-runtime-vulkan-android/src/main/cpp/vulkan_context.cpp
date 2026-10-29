#include "jni_handles.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <vulkan/vulkan.h>

namespace {

constexpr auto kLogTag = "MapLibreCompose";

struct VulkanContext {
  ANativeWindow* window = nullptr;
  VkInstance instance = VK_NULL_HANDLE;
  VkSurfaceKHR surface = VK_NULL_HANDLE;
  VkPhysicalDevice physical_device = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkQueue graphics_queue = VK_NULL_HANDLE;
  uint32_t graphics_queue_family_index = 0;
};

auto require_vk(VkResult result, const char* operation) -> void {
  if (result != VK_SUCCESS) {
    throw std::runtime_error(
      std::string(operation) + " failed with VkResult " + std::to_string(result)
    );
  }
}

auto context_from_handle(jlong handle) -> VulkanContext* {
  auto* context =
    reinterpret_cast<VulkanContext*>(static_cast<uintptr_t>(handle));
  if (context == nullptr) {
    throw std::invalid_argument("Vulkan context handle is null");
  }
  return context;
}

auto throw_java_exception(JNIEnv* env, const char* message) -> void {
  auto* exception_class = env->FindClass("java/lang/IllegalStateException");
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message);
  }
}

// A VkImage imported from an AHardwareBuffer, with the dedicated memory that
// holds the buffer's reference.
struct HardwareBufferImage {
  VkImage image = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
};

auto create_instance(
  uint32_t api_version, const std::vector<const char*>& extensions
) -> VkInstance {
  const VkApplicationInfo app_info{
    .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
    .pNext = nullptr,
    .pApplicationName = "maplibre-compose",
    .applicationVersion = 1,
    .pEngineName = "maplibre-compose",
    .engineVersion = 1,
    .apiVersion = api_version,
  };
  const VkInstanceCreateInfo create_info{
    .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
    .pNext = nullptr,
    .flags = 0,
    .pApplicationInfo = &app_info,
    .enabledLayerCount = 0,
    .ppEnabledLayerNames = nullptr,
    .enabledExtensionCount = static_cast<uint32_t>(extensions.size()),
    .ppEnabledExtensionNames = extensions.data(),
  };
  VkInstance instance = VK_NULL_HANDLE;
  require_vk(
    vkCreateInstance(&create_info, nullptr, &instance), "vkCreateInstance"
  );
  return instance;
}

auto create_surface(
  JNIEnv* env, VkInstance instance, jobject surface, ANativeWindow** out_window
) -> VkSurfaceKHR {
  auto* window = ANativeWindow_fromSurface(env, surface);
  if (window == nullptr) {
    throw std::runtime_error("ANativeWindow_fromSurface returned null");
  }
  const VkAndroidSurfaceCreateInfoKHR create_info{
    .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
    .pNext = nullptr,
    .flags = 0,
    .window = window,
  };
  VkSurfaceKHR vk_surface = VK_NULL_HANDLE;
  try {
    require_vk(
      vkCreateAndroidSurfaceKHR(instance, &create_info, nullptr, &vk_surface),
      "vkCreateAndroidSurfaceKHR"
    );
  } catch (...) {
    ANativeWindow_release(window);
    throw;
  }
  *out_window = window;
  return vk_surface;
}

auto device_supports(
  VkPhysicalDevice device, const std::vector<const char*>& required
) -> bool {
  uint32_t count = 0;
  require_vk(
    vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr),
    "vkEnumerateDeviceExtensionProperties"
  );
  std::vector<VkExtensionProperties> extensions(count);
  require_vk(
    vkEnumerateDeviceExtensionProperties(
      device, nullptr, &count, extensions.data()
    ),
    "vkEnumerateDeviceExtensionProperties"
  );
  for (const auto* name : required) {
    auto found = false;
    for (const auto& extension : extensions) {
      found = found || std::strcmp(extension.extensionName, name) == 0;
    }
    if (!found) {
      return false;
    }
  }
  return true;
}

const std::vector<const char*> kSurfaceInstanceExtensions = {
  VK_KHR_SURFACE_EXTENSION_NAME,
  VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
};
const std::vector<const char*> kSurfaceDeviceExtensions = {
  VK_KHR_SWAPCHAIN_EXTENSION_NAME,
};
// Vulkan 1.1 provides the extension's other dependencies.
const std::vector<const char*> kHardwareBufferDeviceExtensions = {
  VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
  VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
};

auto pick_device(
  VkInstance instance, VkSurfaceKHR surface, uint32_t* out_queue_family
) -> VkPhysicalDevice {
  uint32_t device_count = 0;
  require_vk(
    vkEnumeratePhysicalDevices(instance, &device_count, nullptr),
    "vkEnumeratePhysicalDevices"
  );
  if (device_count == 0) {
    throw std::runtime_error("Vulkan instance exposes no physical devices");
  }
  std::vector<VkPhysicalDevice> devices(device_count);
  require_vk(
    vkEnumeratePhysicalDevices(instance, &device_count, devices.data()),
    "vkEnumeratePhysicalDevices"
  );

  for (auto* device : devices) {
    if (!device_supports(device, kSurfaceDeviceExtensions)) {
      continue;
    }
    uint32_t family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &family_count, nullptr);
    std::vector<VkQueueFamilyProperties> families(family_count);
    vkGetPhysicalDeviceQueueFamilyProperties(
      device, &family_count, families.data()
    );
    for (uint32_t index = 0; index < family_count; ++index) {
      if ((families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) {
        continue;
      }
      VkBool32 present_supported = VK_FALSE;
      require_vk(
        vkGetPhysicalDeviceSurfaceSupportKHR(
          device, index, surface, &present_supported
        ),
        "vkGetPhysicalDeviceSurfaceSupportKHR"
      );
      if (present_supported == VK_TRUE) {
        *out_queue_family = index;
        return device;
      }
    }
  }
  throw std::runtime_error(
    "No Vulkan device supports graphics and presentation"
  );
}

auto pick_hardware_buffer_device(
  VkInstance instance, uint32_t* out_queue_family
) -> VkPhysicalDevice {
  uint32_t device_count = 0;
  require_vk(
    vkEnumeratePhysicalDevices(instance, &device_count, nullptr),
    "vkEnumeratePhysicalDevices"
  );
  std::vector<VkPhysicalDevice> devices(device_count);
  require_vk(
    vkEnumeratePhysicalDevices(instance, &device_count, devices.data()),
    "vkEnumeratePhysicalDevices"
  );
  for (auto* device : devices) {
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(device, &properties);
    if (
      properties.apiVersion < VK_API_VERSION_1_1 ||
      !device_supports(device, kHardwareBufferDeviceExtensions)
    ) {
      continue;
    }
    uint32_t family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &family_count, nullptr);
    std::vector<VkQueueFamilyProperties> families(family_count);
    vkGetPhysicalDeviceQueueFamilyProperties(
      device, &family_count, families.data()
    );
    for (uint32_t index = 0; index < family_count; ++index) {
      if ((families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
        *out_queue_family = index;
        return device;
      }
    }
  }
  throw std::runtime_error(
    "No Vulkan 1.1 device imports Android hardware buffers for graphics"
  );
}

auto create_device(
  VkPhysicalDevice physical_device, uint32_t queue_family_index,
  const std::vector<const char*>& extensions
) -> VkDevice {
  constexpr float kQueuePriority = 1.0F;
  const VkDeviceQueueCreateInfo queue_info{
    .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
    .pNext = nullptr,
    .flags = 0,
    .queueFamilyIndex = queue_family_index,
    .queueCount = 1,
    .pQueuePriorities = &kQueuePriority,
  };
  VkPhysicalDeviceFeatures features{};
  vkGetPhysicalDeviceFeatures(physical_device, &features);
  const VkDeviceCreateInfo create_info{
    .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
    .pNext = nullptr,
    .flags = 0,
    .queueCreateInfoCount = 1,
    .pQueueCreateInfos = &queue_info,
    .enabledLayerCount = 0,
    .ppEnabledLayerNames = nullptr,
    .enabledExtensionCount = static_cast<uint32_t>(extensions.size()),
    .ppEnabledExtensionNames = extensions.data(),
    .pEnabledFeatures = &features,
  };
  VkDevice device = VK_NULL_HANDLE;
  require_vk(
    vkCreateDevice(physical_device, &create_info, nullptr, &device),
    "vkCreateDevice"
  );
  return device;
}

auto destroy_hardware_buffer_image(
  const VulkanContext& context, HardwareBufferImage* imported
) -> void {
  if (imported->view != VK_NULL_HANDLE) {
    vkDestroyImageView(context.device, imported->view, nullptr);
  }
  if (imported->image != VK_NULL_HANDLE) {
    vkDestroyImage(context.device, imported->image, nullptr);
  }
  if (imported->memory != VK_NULL_HANDLE) {
    vkFreeMemory(context.device, imported->memory, nullptr);
  }
  delete imported;
}

auto import_hardware_buffer(
  JNIEnv* env, const VulkanContext& context, jobject hardware_buffer,
  uint32_t width, uint32_t height
) -> HardwareBufferImage* {
  AHardwareBuffer* buffer = nullptr;
  if (__builtin_available(android 26, *)) {
    buffer = AHardwareBuffer_fromHardwareBuffer(env, hardware_buffer);
  }
  if (buffer == nullptr) {
    throw std::runtime_error("AHardwareBuffer_fromHardwareBuffer returned null");
  }
  auto get_properties =
    reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
      vkGetDeviceProcAddr(
        context.device, "vkGetAndroidHardwareBufferPropertiesANDROID"
      )
    );
  if (get_properties == nullptr) {
    throw std::runtime_error(
      "vkGetAndroidHardwareBufferPropertiesANDROID is unavailable"
    );
  }
  VkAndroidHardwareBufferFormatPropertiesANDROID format_properties{
    .sType =
      VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID,
  };
  VkAndroidHardwareBufferPropertiesANDROID properties{
    .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
    .pNext = &format_properties,
  };
  require_vk(
    get_properties(context.device, buffer, &properties),
    "vkGetAndroidHardwareBufferPropertiesANDROID"
  );
  if (
    format_properties.format != VK_FORMAT_R8G8B8A8_UNORM ||
    properties.memoryTypeBits == 0
  ) {
    throw std::runtime_error("The hardware buffer is not an RGBA8 image");
  }

  auto* imported = new HardwareBufferImage();
  try {
    const VkExternalMemoryImageCreateInfo external_info{
      .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
      .pNext = nullptr,
      .handleTypes =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    const VkImageCreateInfo image_info{
      .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
      .pNext = &external_info,
      .flags = 0,
      .imageType = VK_IMAGE_TYPE_2D,
      .format = VK_FORMAT_R8G8B8A8_UNORM,
      .extent = {width, height, 1},
      .mipLevels = 1,
      .arrayLayers = 1,
      .samples = VK_SAMPLE_COUNT_1_BIT,
      .tiling = VK_IMAGE_TILING_OPTIMAL,
      .usage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
      .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
      .queueFamilyIndexCount = 0,
      .pQueueFamilyIndices = nullptr,
      .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    require_vk(
      vkCreateImage(context.device, &image_info, nullptr, &imported->image),
      "vkCreateImage"
    );
    const VkImportAndroidHardwareBufferInfoANDROID import_info{
      .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
      .pNext = nullptr,
      .buffer = buffer,
    };
    const VkMemoryDedicatedAllocateInfo dedicated_info{
      .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
      .pNext = &import_info,
      .image = imported->image,
      .buffer = VK_NULL_HANDLE,
    };
    const VkMemoryAllocateInfo allocate_info{
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .pNext = &dedicated_info,
      .allocationSize = properties.allocationSize,
      .memoryTypeIndex =
        static_cast<uint32_t>(__builtin_ctz(properties.memoryTypeBits)),
    };
    require_vk(
      vkAllocateMemory(
        context.device, &allocate_info, nullptr, &imported->memory
      ),
      "vkAllocateMemory"
    );
    require_vk(
      vkBindImageMemory(context.device, imported->image, imported->memory, 0),
      "vkBindImageMemory"
    );
    const VkImageViewCreateInfo view_info{
      .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
      .pNext = nullptr,
      .flags = 0,
      .image = imported->image,
      .viewType = VK_IMAGE_VIEW_TYPE_2D,
      .format = VK_FORMAT_R8G8B8A8_UNORM,
      .components = {},
      .subresourceRange =
        {
          .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
          .baseMipLevel = 0,
          .levelCount = 1,
          .baseArrayLayer = 0,
          .layerCount = 1,
        },
    };
    require_vk(
      vkCreateImageView(context.device, &view_info, nullptr, &imported->view),
      "vkCreateImageView"
    );
    return imported;
  } catch (...) {
    destroy_hardware_buffer_image(context, imported);
    throw;
  }
}

auto destroy_context(VulkanContext* context) -> void {
  if (context == nullptr) {
    return;
  }
  if (context->device != VK_NULL_HANDLE) {
    vkDeviceWaitIdle(context->device);
    vkDestroyDevice(context->device, nullptr);
  }
  if (context->surface != VK_NULL_HANDLE) {
    vkDestroySurfaceKHR(context->instance, context->surface, nullptr);
  }
  if (context->instance != VK_NULL_HANDLE) {
    vkDestroyInstance(context->instance, nullptr);
  }
  if (context->window != nullptr) {
    ANativeWindow_release(context->window);
  }
  delete context;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_create(
  JNIEnv* env, jobject, jobject surface
) {
  auto* context = new VulkanContext();
  try {
    context->instance =
      create_instance(VK_API_VERSION_1_0, kSurfaceInstanceExtensions);
    context->surface =
      create_surface(env, context->instance, surface, &context->window);
    context->physical_device = pick_device(
      context->instance, context->surface, &context->graphics_queue_family_index
    );
    context->device = create_device(
      context->physical_device, context->graphics_queue_family_index,
      kSurfaceDeviceExtensions
    );
    vkGetDeviceQueue(
      context->device, context->graphics_queue_family_index, 0,
      &context->graphics_queue
    );
    return handle_to_jlong(context);
  } catch (const std::exception& error) {
    __android_log_print(
      ANDROID_LOG_ERROR, kLogTag, "Vulkan setup failed: %s", error.what()
    );
    destroy_context(context);
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_createForHardwareBuffers(
  JNIEnv* env, jobject
) {
  auto* context = new VulkanContext();
  try {
    context->instance = create_instance(VK_API_VERSION_1_1, {});
    context->physical_device = pick_hardware_buffer_device(
      context->instance, &context->graphics_queue_family_index
    );
    context->device = create_device(
      context->physical_device, context->graphics_queue_family_index,
      kHardwareBufferDeviceExtensions
    );
    vkGetDeviceQueue(
      context->device, context->graphics_queue_family_index, 0,
      &context->graphics_queue
    );
    return handle_to_jlong(context);
  } catch (const std::exception& error) {
    __android_log_print(
      ANDROID_LOG_ERROR, kLogTag, "Vulkan setup failed: %s", error.what()
    );
    destroy_context(context);
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_importHardwareBuffer(
  JNIEnv* env, jobject, jlong handle, jobject hardware_buffer, jint width,
  jint height
) {
  try {
    return handle_to_jlong(import_hardware_buffer(
      env, *context_from_handle(handle), hardware_buffer,
      static_cast<uint32_t>(width), static_cast<uint32_t>(height)
    ));
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_destroyImage(
  JNIEnv* env, jobject, jlong handle, jlong image
) {
  try {
    destroy_hardware_buffer_image(
      *context_from_handle(handle),
      reinterpret_cast<HardwareBufferImage*>(static_cast<uintptr_t>(image))
    );
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_image(
  JNIEnv*, jobject, jlong image
) {
  return handle_to_jlong(
    reinterpret_cast<HardwareBufferImage*>(static_cast<uintptr_t>(image))
      ->image
  );
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_imageView(
  JNIEnv*, jobject, jlong image
) {
  return handle_to_jlong(
    reinterpret_cast<HardwareBufferImage*>(static_cast<uintptr_t>(image))->view
  );
}

extern "C" JNIEXPORT void JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_destroy(
  JNIEnv* env, jobject, jlong handle
) {
  try {
    destroy_context(context_from_handle(handle));
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_instance(
  JNIEnv* env, jobject, jlong handle
) {
  try {
    return handle_to_jlong(context_from_handle(handle)->instance);
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_surface(
  JNIEnv* env, jobject, jlong handle
) {
  try {
    return handle_to_jlong(context_from_handle(handle)->surface);
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_physicalDevice(
  JNIEnv* env, jobject, jlong handle
) {
  try {
    return handle_to_jlong(context_from_handle(handle)->physical_device);
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_device(
  JNIEnv* env, jobject, jlong handle
) {
  try {
    return handle_to_jlong(context_from_handle(handle)->device);
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_graphicsQueue(
  JNIEnv* env, jobject, jlong handle
) {
  try {
    return handle_to_jlong(context_from_handle(handle)->graphics_queue);
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_graphicsQueueFamilyIndex(
  JNIEnv* env, jobject, jlong handle
) {
  try {
    return static_cast<jint>(
      context_from_handle(handle)->graphics_queue_family_index
    );
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_getInstanceProcAddr(
  JNIEnv*, jobject
) {
  return handle_to_jlong(&vkGetInstanceProcAddr);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_getDeviceProcAddr(
  JNIEnv*, jobject
) {
  return handle_to_jlong(&vkGetDeviceProcAddr);
}
