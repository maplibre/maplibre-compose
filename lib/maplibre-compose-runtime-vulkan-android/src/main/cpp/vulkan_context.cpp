#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

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
    reinterpret_cast<VulkanContext*>(static_cast<intptr_t>(handle));
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

auto create_instance() -> VkInstance {
  const char* extensions[] = {
    VK_KHR_SURFACE_EXTENSION_NAME,
    VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
  };
  const VkApplicationInfo app_info{
    .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
    .pNext = nullptr,
    .pApplicationName = "maplibre-compose",
    .applicationVersion = 1,
    .pEngineName = "maplibre-compose",
    .engineVersion = 1,
    .apiVersion = VK_API_VERSION_1_0,
  };
  const VkInstanceCreateInfo create_info{
    .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
    .pNext = nullptr,
    .flags = 0,
    .pApplicationInfo = &app_info,
    .enabledLayerCount = 0,
    .ppEnabledLayerNames = nullptr,
    .enabledExtensionCount = 2,
    .ppEnabledExtensionNames = extensions,
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

auto device_supports_swapchain(VkPhysicalDevice device) -> bool {
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
  for (const auto& extension : extensions) {
    if (
      std::strcmp(extension.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0
    ) {
      return true;
    }
  }
  return false;
}

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
    if (!device_supports_swapchain(device)) {
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

auto create_device(
  VkPhysicalDevice physical_device, uint32_t queue_family_index
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
  const char* extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
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
    .enabledExtensionCount = 1,
    .ppEnabledExtensionNames = extensions,
    .pEnabledFeatures = &features,
  };
  VkDevice device = VK_NULL_HANDLE;
  require_vk(
    vkCreateDevice(physical_device, &create_info, nullptr, &device),
    "vkCreateDevice"
  );
  return device;
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
    context->instance = create_instance();
    context->surface =
      create_surface(env, context->instance, surface, &context->window);
    context->physical_device = pick_device(
      context->instance, context->surface, &context->graphics_queue_family_index
    );
    context->device = create_device(
      context->physical_device, context->graphics_queue_family_index
    );
    vkGetDeviceQueue(
      context->device, context->graphics_queue_family_index, 0,
      &context->graphics_queue
    );
    return static_cast<jlong>(reinterpret_cast<intptr_t>(context));
  } catch (const std::exception& error) {
    __android_log_print(
      ANDROID_LOG_ERROR, kLogTag, "Vulkan setup failed: %s", error.what()
    );
    destroy_context(context);
    throw_java_exception(env, error.what());
    return 0;
  }
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
    return reinterpret_cast<intptr_t>(context_from_handle(handle)->instance);
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
    return reinterpret_cast<intptr_t>(context_from_handle(handle)->surface);
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
    return reinterpret_cast<intptr_t>(
      context_from_handle(handle)->physical_device
    );
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
    return reinterpret_cast<intptr_t>(context_from_handle(handle)->device);
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
    return reinterpret_cast<intptr_t>(
      context_from_handle(handle)->graphics_queue
    );
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
  return reinterpret_cast<intptr_t>(&vkGetInstanceProcAddr);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidVulkanNativeBridge_getDeviceProcAddr(
  JNIEnv*, jobject
) {
  return reinterpret_cast<intptr_t>(&vkGetDeviceProcAddr);
}
