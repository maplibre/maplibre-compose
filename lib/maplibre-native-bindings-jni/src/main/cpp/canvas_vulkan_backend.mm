#if defined(__APPLE__) && defined(USE_VULKAN_BACKEND)

#include <mbgl/vulkan/context.hpp>
#include <mbgl/vulkan/renderable_resource.hpp>

#import <Cocoa/Cocoa.h>
#include <Foundation/Foundation.hpp>
#include <Metal/Metal.hpp>
#import <QuartzCore/CAMetalLayer.h>
#include <QuartzCore/QuartzCore.hpp>
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
    metalLayer = (CAMetalLayer*)backendImpl.getSurfaceInfo().createMetalLayer();

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

}  // namespace maplibre_jni

#endif
