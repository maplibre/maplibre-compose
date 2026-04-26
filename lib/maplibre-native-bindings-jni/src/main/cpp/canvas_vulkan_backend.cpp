#ifdef USE_VULKAN_BACKEND

#include <stdexcept>

#include <mbgl/vulkan/context.hpp>
#include <mbgl/vulkan/renderable_resource.hpp>

// clang-format off
#include "fix_x11_pollution.h"
// clang-format on

#include "canvas_renderer.hpp"
#include "java_classes.hpp"
#include "jawt_context.hpp"
#include "utils.hpp"

#if defined(__linux__)
#include <vulkan/vulkan_xlib.h>
#elif defined(_WIN32)
#include <vulkan/vulkan_win32.h>
#endif

namespace maplibre_jni {

class VulkanRenderableResource final
    : public mbgl::vulkan::SurfaceRenderableResource {
 public:
  VulkanRenderableResource(CanvasBackend& backend_, JNIEnv* env, jCanvas canvas)
      : mbgl::vulkan::SurfaceRenderableResource(backend_),
        jawtContext(env, canvas) {}

  auto getDeviceExtensions() -> std::vector<const char*> override {
    return {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
  }

  void createPlatformSurface() override {
    auto& backendImpl = static_cast<CanvasBackend&>(backend);

#if defined(__linux__)
    check(jawtContext.getDisplay() != nullptr, "X11 display is nullptr");
    check(jawtContext.getDrawable() != 0, "X11 drawable is 0");

    VkXlibSurfaceCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR;
    createInfo.dpy = jawtContext.getDisplay();
    createInfo.window = jawtContext.getDrawable();

    auto createXlibSurface = reinterpret_cast<PFN_vkCreateXlibSurfaceKHR>(
      backendImpl.getDispatcher().vkGetInstanceProcAddr(
        backendImpl.getInstance().get(), "vkCreateXlibSurfaceKHR"
      )
    );
    check(
      createXlibSurface != nullptr, "vkCreateXlibSurfaceKHR is unavailable"
    );

    VkSurfaceKHR rawSurface = VK_NULL_HANDLE;
    auto result = createXlibSurface(
      backendImpl.getInstance().get(), &createInfo, nullptr, &rawSurface
    );
    check(result == VK_SUCCESS, "vkCreateXlibSurfaceKHR failed");

    surface = vk::UniqueSurfaceKHR(
      rawSurface,
      vk::ObjectDestroy<vk::Instance, vk::DispatchLoaderDynamic>(
        backendImpl.getInstance().get(), nullptr, backendImpl.getDispatcher()
      )
    );
#elif defined(_WIN32)
    check(jawtContext.getHwnd() != nullptr, "HWND is nullptr");

    VkWin32SurfaceCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR;
    createInfo.hinstance = GetModuleHandle(nullptr);
    createInfo.hwnd = jawtContext.getHwnd();

    auto createWin32Surface = reinterpret_cast<PFN_vkCreateWin32SurfaceKHR>(
      backendImpl.getDispatcher().vkGetInstanceProcAddr(
        backendImpl.getInstance().get(), "vkCreateWin32SurfaceKHR"
      )
    );
    check(
      createWin32Surface != nullptr, "vkCreateWin32SurfaceKHR is unavailable"
    );

    VkSurfaceKHR rawSurface = VK_NULL_HANDLE;
    auto result = createWin32Surface(
      backendImpl.getInstance().get(), &createInfo, nullptr, &rawSurface
    );
    check(result == VK_SUCCESS, "vkCreateWin32SurfaceKHR failed");

    surface = vk::UniqueSurfaceKHR(
      rawSurface,
      vk::ObjectDestroy<vk::Instance, vk::DispatchLoaderDynamic>(
        backendImpl.getInstance().get(), nullptr, backendImpl.getDispatcher()
      )
    );
#else
    throw std::runtime_error(
      "Vulkan CanvasBackend is unsupported on this platform"
    );
#endif
  }

  void bind() override {}

  bool lockForRender() { return jawtContext.tryLock(); }

  void unlockAfterRender() { jawtContext.unlock(); }

 private:
  JawtContext jawtContext;
};

CanvasBackend::CanvasBackend(JNIEnv* env, jCanvas canvas)
    : mbgl::vulkan::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::vulkan::Renderable(
        mbgl::Size(
          java_classes::get<Canvas_class>().getWidth(env, canvas),
          java_classes::get<Canvas_class>().getHeight(env, canvas)
        ),
        std::make_unique<VulkanRenderableResource>(*this, env, canvas)
      ) {
  init();
}

auto CanvasBackend::getDefaultRenderable() -> mbgl::gfx::Renderable& {
  return *this;
}

bool CanvasBackend::lockSurfaceForRender() {
  return getResource<VulkanRenderableResource>().lockForRender();
}

void CanvasBackend::unlockSurfaceAfterRender() {
  getResource<VulkanRenderableResource>().unlockAfterRender();
}

void CanvasBackend::setSize(mbgl::Size newSize) {
  mbgl::vulkan::Renderable::setSize(newSize);

  if (context) {
    static_cast<mbgl::vulkan::Context&>(*context).requestSurfaceUpdate();
  }
}

auto CanvasBackend::getInstanceExtensions() -> std::vector<const char*> {
  auto extensions = mbgl::vulkan::RendererBackend::getInstanceExtensions();
  extensions.push_back(VK_KHR_SURFACE_EXTENSION_NAME);

#if defined(__linux__)
  extensions.push_back(VK_KHR_XLIB_SURFACE_EXTENSION_NAME);
#elif defined(_WIN32)
  extensions.push_back(VK_KHR_WIN32_SURFACE_EXTENSION_NAME);
#endif

  return extensions;
}

}  // namespace maplibre_jni

#endif
