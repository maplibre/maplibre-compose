#ifdef USE_VULKAN_BACKEND
#ifdef __APPLE__

#include "awt_vulkan_backend.hpp"
#import <Cocoa/Cocoa.h>
#import <QuartzCore/CAMetalLayer.h>
#include <jawt.h>
#include <jawt_md.h>
#include <mbgl/util/logging.hpp>

// Forward declaration of the Vulkan surface creation function
extern "C" {
typedef struct VkMetalSurfaceCreateInfoEXT {
  VkStructureType sType;
  const void *pNext;
  VkFlags flags;
  const CAMetalLayer *pLayer;
} VkMetalSurfaceCreateInfoEXT;

typedef VkResult (*PFN_vkCreateMetalSurfaceEXT)(
  VkInstance instance, const VkMetalSurfaceCreateInfoEXT *pCreateInfo,
  const VkAllocationCallbacks *pAllocator, VkSurfaceKHR *pSurface
);
}

#define VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT 1000217000
#define VK_EXT_METAL_SURFACE_EXTENSION_NAME "VK_EXT_metal_surface"

namespace maplibre_jni {

// Store the CAMetalLayer for the Vulkan backend
static CAMetalLayer *g_metalLayer = nullptr;
static JAWT_DrawingSurface *g_jawtDrawingSurface = nullptr;
static JAWT_DrawingSurfaceInfo *g_jawtDrawingSurfaceInfo = nullptr;

void VulkanBackend::setupMacOSLayer(JNIEnv *env, jobject canvas) {
  // Get JAWT
  JAWT awt;
  awt.version = JAWT_VERSION_9;

  jboolean result = JAWT_GetAWT(env, &awt);
  if (result == JNI_FALSE) {
    mbgl::Log::Error(mbgl::Event::General, "JAWT_GetAWT failed");
    return;
  }

  // Get the drawing surface
  JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
  if (!ds) {
    mbgl::Log::Error(mbgl::Event::General, "GetDrawingSurface returned null");
    return;
  }

  // Lock the drawing surface
  jint lock = ds->Lock(ds);
  if ((lock & JAWT_LOCK_ERROR) != 0) {
    mbgl::Log::Error(mbgl::Event::General, "Error locking drawing surface");
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Get the drawing surface info
  JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
  if (!dsi) {
    mbgl::Log::Error(
      mbgl::Event::General, "GetDrawingSurfaceInfo returned null"
    );
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Get the platform-specific drawing info
  id<JAWT_SurfaceLayers> surfaceLayers =
    (id<JAWT_SurfaceLayers>)dsi->platformInfo;
  if (!surfaceLayers) {
    mbgl::Log::Error(mbgl::Event::General, "Platform info is null");
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Create a CAMetalLayer for MoltenVK to use
  @autoreleasepool {
    g_metalLayer = [CAMetalLayer layer];

    // Get the backing scale factor for proper Retina display support
    NSScreen *screen = [NSScreen mainScreen];
    CGFloat scale = screen.backingScaleFactor;

    // Set the frame accounting for the scale factor
    g_metalLayer.frame =
      CGRectMake(0, 0, size.width / scale, size.height / scale);
    g_metalLayer.contentsScale = scale;

    // Set the pixel format (BGRA8Unorm is standard for MoltenVK)
    g_metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;

    // Set opaque for better performance
    g_metalLayer.opaque = YES;

    // Assign the layer to the JAWT surface
    surfaceLayers.layer = g_metalLayer;

    // Store references for cleanup
    g_jawtDrawingSurface = ds;
    g_jawtDrawingSurfaceInfo = dsi;
  }
}

void VulkanBackend::cleanupMacOSLayer() {
  if (g_jawtDrawingSurfaceInfo && g_jawtDrawingSurface) {
    JAWT_DrawingSurface *ds = g_jawtDrawingSurface;
    ds->FreeDrawingSurfaceInfo(g_jawtDrawingSurfaceInfo);
    ds->Unlock(ds);

    // Get JAWT to free the surface
    JAWT awt;
    awt.version = JAWT_VERSION_9;
    JNIEnv *env = getEnv();
    if (env && JAWT_GetAWT(env, &awt) != JNI_FALSE) {
      awt.FreeDrawingSurface(ds);
    }

    g_jawtDrawingSurface = nullptr;
    g_jawtDrawingSurfaceInfo = nullptr;
  }

  if (g_metalLayer) {
    [g_metalLayer release];
    g_metalLayer = nullptr;
  }
}

void VulkanBackend::updateMacOSLayerSize(mbgl::Size newSize) {
  if (g_metalLayer) {
    @autoreleasepool {
      NSScreen *screen = [NSScreen mainScreen];
      CGFloat scale = screen.backingScaleFactor;
      g_metalLayer.frame =
        CGRectMake(0, 0, newSize.width / scale, newSize.height / scale);
      g_metalLayer.contentsScale = scale;
      g_metalLayer.drawableSize = CGSizeMake(newSize.width, newSize.height);
    }
  }
}

void *VulkanBackend::getMetalLayer() { return g_metalLayer; }

void VulkanBackend::createMacOSSurface(
  const vk::UniqueInstance &instance, vk::UniqueSurfaceKHR &surface
) {
  if (!g_metalLayer) {
    throw std::runtime_error("CAMetalLayer not initialized");
  }

  // Get the function pointer for vkCreateMetalSurfaceEXT
  auto vkCreateMetalSurfaceEXT = reinterpret_cast<PFN_vkCreateMetalSurfaceEXT>(
    vkGetInstanceProcAddr(instance.get(), "vkCreateMetalSurfaceEXT")
  );

  if (!vkCreateMetalSurfaceEXT) {
    throw std::runtime_error(
      "Failed to get vkCreateMetalSurfaceEXT function pointer"
    );
  }

  VkMetalSurfaceCreateInfoEXT createInfo{};
  createInfo.sType = static_cast<VkStructureType>(
    VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT
  );
  createInfo.pNext = nullptr;
  createInfo.flags = 0;
  createInfo.pLayer = g_metalLayer;

  VkSurfaceKHR surface_;
  VkResult result =
    vkCreateMetalSurfaceEXT(instance.get(), &createInfo, nullptr, &surface_);

  if (result != VK_SUCCESS) {
    throw std::runtime_error("Failed to create Metal surface for MoltenVK");
  }

  surface = vk::UniqueSurfaceKHR(
    surface_, vk::ObjectDestroy<vk::Instance, vk::DispatchLoaderDynamic>(
                instance.get(), nullptr, getDispatcher()
              )
  );
}

}  // namespace maplibre_jni

#endif  // __APPLE__
#endif  // USE_VULKAN_BACKEND
