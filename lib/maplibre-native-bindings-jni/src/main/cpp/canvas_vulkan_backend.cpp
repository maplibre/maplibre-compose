#ifdef USE_VULKAN_BACKEND

#include <mbgl/vulkan/context.hpp>
#include <mbgl/vulkan/renderable_resource.hpp>

#include <vulkan/vulkan_core.h>

#include "canvas_renderer.hpp"
#include "java_classes.hpp"

namespace maplibre_jni {

#ifdef __APPLE__
// Implemented in canvas_vulkan_backend.mm
class VulkanRenderableResource final
    : public mbgl::vulkan::SurfaceRenderableResource {
 public:
  VulkanRenderableResource(mbgl::vulkan::RendererBackend& backend);
  ~VulkanRenderableResource();
  std::vector<const char*> getDeviceExtensions() override;
  void bind() override {};
  void createPlatformSurface() override;
  void setSize(mbgl::Size);
};
#else
class VulkanRenderableResource final
    : public mbgl::vulkan::SurfaceRenderableResource {
 public:
  VulkanRenderableResource(mbgl::vulkan::RendererBackend& backend)
      : mbgl::vulkan::SurfaceRenderableResource(backend) {}

  std::vector<const char*> getDeviceExtensions() override {
    return {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
  };

  void bind() override {};

  void createPlatformSurface() override {
    auto& backendImpl = static_cast<CanvasVulkanBackend&>(backend);
    auto& surfaceInfo = backendImpl.getSurfaceInfo();
#if defined(__linux__)
    if (!surfaceInfo.getNativeDisplay() || !surfaceInfo.getNativeDrawable()) {
      throw std::runtime_error("X11 display or window not available");
    }

    VkXlibSurfaceCreateInfoKHR createInfo{
      .sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR,
      .pNext = nullptr,
      .flags = 0,
      .dpy = surfaceInfo.getNativeDisplay(),
      .window = surfaceInfo.getNativeDrawable(),
    };

    VkSurfaceKHR surface_;

    if (vkCreateXlibSurfaceKHR(
          backendImpl.getInstance().get(), &createInfo, nullptr, &surface_
        ) != VK_SUCCESS) {
      throw std::runtime_error("Failed to create X11 surface");
    }

    surface = vk::UniqueSurfaceKHR(
      surface_,
      vk::ObjectDestroy<vk::Instance, vk::DispatchLoaderDynamic>(
        backendImpl.getInstance().get(), nullptr, backendImpl.getDispatcher()
      )
    );
#elif defined(_WIN32)
    if (!surfaceInfo.getNativeWindow()) {
      throw std::runtime_error("Win32 window handle not available");
    }

    VkWin32SurfaceCreateInfoKHR createInfo{
      .sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR,
      .pNext = nullptr,
      .flags = 0,
      .hinstance = GetModuleHandle(NULL),
      .hwnd = surfaceInfo.getNativeWindow(),
    };

    VkSurfaceKHR surface_;
    VkResult result = vkCreateWin32SurfaceKHR(
      backendImpl.getInstance().get(), &createInfo, nullptr, &surface_
    );

    if (result != VK_SUCCESS) {
      throw std::runtime_error("Failed to create Win32 surface");
    }

    surface = vk::UniqueSurfaceKHR(
      surface_,
      vk::ObjectDestroy<vk::Instance, vk::DispatchLoaderDynamic>(
        backendImpl.getInstance().get(), nullptr, backendImpl.getDispatcher()
      )
    );
#endif
  }

  ~VulkanRenderableResource() {}
  void setSize(mbgl::Size) {}
};
#endif

CanvasVulkanBackend::CanvasVulkanBackend(JNIEnv* env, jCanvas canvas)
    : mbgl::vulkan::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::vulkan::Renderable(
        mbgl::Size(
          java_classes::get<Canvas_class>().getWidth(env, canvas),
          java_classes::get<Canvas_class>().getHeight(env, canvas)
        ),
        std::make_unique<VulkanRenderableResource>(*this)
      ),
      surfaceInfo_(env, canvas) {
  init();
}

mbgl::gfx::Renderable& CanvasVulkanBackend::getDefaultRenderable() {
  return *this;
}

void CanvasVulkanBackend::setSize(mbgl::Size size) {
  this->mbgl::vulkan::Renderable::setSize(size);
  if (context && size.width && size.height) {
    static_cast<mbgl::vulkan::Context&>(*context).requestSurfaceUpdate();
  }
}

std::vector<const char*> CanvasVulkanBackend::getInstanceExtensions() {
  return {
#if defined(__APPLE__)
    VK_EXT_METAL_SURFACE_EXTENSION_NAME,
    VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME,
#elif defined(__linux__)
    VK_KHR_XLIB_SURFACE_EXTENSION_NAME,
#elif defined(_WIN32)
    VK_KHR_WIN32_SURFACE_EXTENSION_NAME,
#endif
    VK_KHR_SURFACE_EXTENSION_NAME
  };
}

}  // namespace maplibre_jni

#endif  // USE_VULKAN_BACKEND
