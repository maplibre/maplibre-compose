#ifdef __APPLE__
#ifdef USE_VULKAN_BACKEND

#define VK_USE_PLATFORM_METAL_EXT

#include <mbgl/vulkan/context.hpp>
#include <mbgl/vulkan/renderable_resource.hpp>

#import <Cocoa/Cocoa.h>
#import <QuartzCore/CAMetalLayer.h>
#include <jawt_md.h>
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_metal.h>

#include "canvas_renderer.hpp"
#include "java_classes.hpp"

namespace maplibre_jni {

class VulkanRenderableResource final
    : public mbgl::vulkan::SurfaceRenderableResource {
  CAMetalLayer* metalLayer;

 public:
  VulkanRenderableResource(mbgl::vulkan::RendererBackend& backend)
      : mbgl::vulkan::SurfaceRenderableResource(backend) {}

  std::vector<const char*> getDeviceExtensions() override {
    return {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
  };

  void bind() override {};

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
  }

  ~VulkanRenderableResource() {
    if (!metalLayer) return;
    [metalLayer release];
  }

  void setSize(mbgl::Size size) {
    if (!metalLayer) return;
    metalLayer.drawableSize = CGSizeMake(size.width, size.height);
  }
};

#endif  // USE_VULKAN_BACKEND
#endif  // __APPLE__
