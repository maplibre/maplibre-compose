#include <jawt_md.h>
#include <mbgl/vulkan/renderable_resource.hpp>
#include <vulkan/vulkan_core.h>
#include "canvas_renderer.hpp"
#include "java_classes.hpp"

#ifdef __linux__
#include <X11/Xlib.h>
#define VK_USE_PLATFORM_XLIB_KHR
#include <vulkan/vulkan_xlib.h>
#elif _WIN32
#include <windows.h>
#define VK_USE_PLATFORM_WIN32_KHR
#include <vulkan/vulkan_win32.h>
#elif __APPLE__
#import <Cocoa/Cocoa.h>
#import <QuartzCore/CAMetalLayer.h>
#define VK_USE_PLATFORM_METAL_EXT
#include <vulkan/vulkan_metal.h>
#endif

namespace maplibre_jni {

class VulkanRenderableResource final
    : public mbgl::vulkan::SurfaceRenderableResource {
 public:
  VulkanRenderableResource(mbgl::vulkan::RendererBackend& backend)
      : mbgl::vulkan::SurfaceRenderableResource(backend) {}

  std::vector<const char*> getDeviceExtensions() override {
    return {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
  };

  void bind() override {
    // no-op?
  };

#ifdef __APPLE__
 private:
  CAMetalLayer* metalLayer;

 public:
  void createPlatformSurface() override {
    auto& backendImpl = static_cast<CanvasVulkanBackend&>(backend);

    // Set the Metal layer in the platform info
    auto scale = [NSScreen mainScreen].backingScaleFactor;

    metalLayer = [CAMetalLayer layer];
    metalLayer.bounds = CGRectMake(0, 0, 1, 1);
    metalLayer.contentsScale = scale;
    auto platformInfo = (id<JAWT_SurfaceLayers>)backendImpl.getPlatformInfo();
    platformInfo.layer = metalLayer;

    // Create the Vulkan surface
    auto& instance = backendImpl.getInstance().get();

    auto vkCreateMetalSurfaceEXT =
      reinterpret_cast<PFN_vkCreateMetalSurfaceEXT>(
        vkGetInstanceProcAddr(instance, "vkCreateMetalSurfaceEXT")
      );

    VkMetalSurfaceCreateInfoEXT createInfo{
      .sType = static_cast<VkStructureType>(
        VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT
      ),
      .pNext = nullptr,
      .flags = 0,
      .pLayer = metalLayer,
    };

    VkSurfaceKHR localSurface;

    if (vkCreateMetalSurfaceEXT(
          instance, &createInfo, nullptr, &localSurface
        ) != VK_SUCCESS) {
      throw std::runtime_error("Failed to create Metal surface for MoltenVK");
    }

    this->surface = vk::UniqueSurfaceKHR(
      localSurface, vk::ObjectDestroy<vk::Instance, vk::DispatchLoaderDynamic>(
                      instance, nullptr, backendImpl.getDispatcher()
                    )
    );
  };

  ~VulkanRenderableResource() {
    if (!metalLayer) return;
    [metalLayer release];
  }

  void setSize(mbgl::Size size) {
    if (!metalLayer) return;
    metalLayer.drawableSize = CGSizeMake(size.width, size.height);
  }

#elifdef __linux__
// TODO
#elifdef _WIN32
// TODO
#endif
};

CanvasVulkanBackend::CanvasVulkanBackend(JNIEnv* env, jCanvas canvas)
    : CanvasBackend(env, canvas),
      mbgl::vulkan::Renderable(
        mbgl::Size(
          java_classes::get<Canvas_class>().getWidth(env, canvas),
          java_classes::get<Canvas_class>().getHeight(env, canvas)
        ),
        std::make_unique<VulkanRenderableResource>(*this)
      ) {}

mbgl::gfx::Renderable& CanvasVulkanBackend::getDefaultRenderable() {
  return *this;
}

void CanvasVulkanBackend::wait() {
  // TODO figure out what to do here
}

void CanvasVulkanBackend::setSize(mbgl::Size size) {
  this->mbgl::vulkan::Renderable::setSize(size);
  getResource<VulkanRenderableResource>().setSize(size);
  // TODO request surface update?
}

std::vector<const char*> CanvasVulkanBackend::getInstanceExtensions() {
#ifdef __APPLE__
  auto platformSurfaceExtension = VK_EXT_METAL_SURFACE_EXTENSION_NAME;
#elifdef __linux__
  auto platformSurfaceExtension = VK_KHR_XLIB_SURFACE_EXTENSION_NAME;
#elifdef _WIN32
  auto platformSurfaceExtension = VK_KHR_WIN32_SURFACE_EXTENSION_NAME;
#endif
  return {
    VK_KHR_SURFACE_EXTENSION_NAME, platformSurfaceExtension,
    VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
  };
}

}  // namespace maplibre_jni
