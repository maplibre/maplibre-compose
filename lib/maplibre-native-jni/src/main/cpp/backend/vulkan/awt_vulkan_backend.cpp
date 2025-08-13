#ifdef USE_VULKAN_BACKEND

#include "awt_vulkan_backend.hpp"
#include <jawt.h>
#include <jawt_md.h>
#include <mbgl/util/logging.hpp>
#include <mbgl/vulkan/context.hpp>
#include <mbgl/vulkan/renderable_resource.hpp>

#ifdef __linux__
#include <X11/Xlib.h>
#define VK_USE_PLATFORM_XLIB_KHR
#include <vulkan/vulkan_xlib.h>
#elif _WIN32
#include <windows.h>
#define VK_USE_PLATFORM_WIN32_KHR
#include <vulkan/vulkan_win32.h>
#elif __APPLE__
#define VK_USE_PLATFORM_METAL_EXT
#include <vulkan/vulkan_metal.h>
#endif

// VulkanRenderableResource must be in global namespace to match GLFW pattern
class VulkanRenderableResource final
    : public mbgl::vulkan::SurfaceRenderableResource {
 public:
  explicit VulkanRenderableResource(maplibre_jni::VulkanBackend &backend_)
      : mbgl::vulkan::SurfaceRenderableResource(backend_),
        vulkanBackend(backend_) {}

  void createPlatformSurface() override {
    auto &backendImpl = static_cast<maplibre_jni::VulkanBackend &>(backend);

#ifdef __APPLE__
    // Use the macOS-specific surface creation
    backendImpl.createMacOSSurface(backendImpl.getInstance(), surface);
#elif __linux__
    if (!backendImpl.getNativeDisplay() || !backendImpl.getNativeWindow()) {
      throw std::runtime_error("X11 display or window not available");
    }

    VkXlibSurfaceCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR;
    createInfo.dpy = static_cast<Display *>(backendImpl.getNativeDisplay());
    createInfo.window = static_cast<Window>(
      reinterpret_cast<uintptr_t>(backendImpl.getNativeWindow())
    );

    VkSurfaceKHR surface_;
    VkResult result = vkCreateXlibSurfaceKHR(
      backendImpl.getInstance().get(), &createInfo, nullptr, &surface_
    );

    if (result != VK_SUCCESS) {
      throw std::runtime_error("Failed to create X11 surface");
    }

    surface = vk::UniqueSurfaceKHR(
      surface_,
      vk::ObjectDestroy<vk::Instance, vk::DispatchLoaderDynamic>(
        backendImpl.getInstance().get(), nullptr, backendImpl.getDispatcher()
      )
    );

#elif _WIN32
    if (!backendImpl.getNativeWindow()) {
      throw std::runtime_error("Win32 window handle not available");
    }

    VkWin32SurfaceCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR;
    createInfo.hinstance = GetModuleHandle(NULL);
    createInfo.hwnd = static_cast<HWND>(backendImpl.getNativeWindow());

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

  void bind() override {
    // Stub - binding is handled by Vulkan command buffers
  }

  std::vector<const char *> getDeviceExtensions() override {
    return {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
  }

 private:
  maplibre_jni::VulkanBackend &vulkanBackend;
};

namespace maplibre_jni {

VulkanBackend::VulkanBackend(JNIEnv *env, jobject canvas, int width, int height)
    : mbgl::vulkan::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::vulkan::Renderable(
        mbgl::Size{static_cast<uint32_t>(width), static_cast<uint32_t>(height)},
        std::make_unique<VulkanRenderableResource>(*this)
      ),
      size({static_cast<uint32_t>(width), static_cast<uint32_t>(height)}) {
  env->GetJavaVM(&javaVM);
  canvasRef = env->NewGlobalRef(canvas);
#ifdef __APPLE__
  setupMacOSLayer(env, canvas);
#else
  setupVulkanSurface(env, canvas);
#endif
  init();
}

VulkanBackend::~VulkanBackend() {
#ifdef __APPLE__
  cleanupMacOSLayer();
#endif
  if (canvasRef) {
    JNIEnv *env = getEnv();
    if (env) {
      env->DeleteGlobalRef(canvasRef);
    }
    canvasRef = nullptr;
  }
}

mbgl::gfx::Renderable &VulkanBackend::getDefaultRenderable() { return *this; }

void VulkanBackend::setSize(mbgl::Size newSize) {
  // Update both our local size and the Renderable's size
  size = newSize;
  this->mbgl::vulkan::Renderable::size = newSize;

#ifdef __APPLE__
  updateMacOSLayerSize(newSize);
#endif

  if (context) {
    auto &contextImpl = static_cast<mbgl::vulkan::Context &>(*context);
    contextImpl.requestSurfaceUpdate();
  }
}

JNIEnv *VulkanBackend::getEnv() {
  JNIEnv *env = nullptr;
  if (javaVM) {
    javaVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_21);
  }
  return env;
}

std::vector<const char *> VulkanBackend::getInstanceExtensions() {
  auto extensions = mbgl::vulkan::RendererBackend::getInstanceExtensions();

  // Add surface extensions
  extensions.push_back(VK_KHR_SURFACE_EXTENSION_NAME);

#ifdef __linux__
  extensions.push_back(VK_KHR_XLIB_SURFACE_EXTENSION_NAME);
#elif _WIN32
  extensions.push_back(VK_KHR_WIN32_SURFACE_EXTENSION_NAME);
#elif __APPLE__
  extensions.push_back(VK_EXT_METAL_SURFACE_EXTENSION_NAME);
  extensions.push_back("VK_KHR_portability_enumeration");
#endif

  return extensions;
}

void VulkanBackend::setupVulkanSurface(JNIEnv *env, jobject canvas) {
#ifndef __APPLE__  // This method is not used on macOS
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

  // Platform-specific native handle extraction
#ifdef __linux__
  // Get the X11 drawing surface info
  JAWT_X11DrawingSurfaceInfo *x11Info =
    (JAWT_X11DrawingSurfaceInfo *)dsi->platformInfo;
  if (!x11Info) {
    mbgl::Log::Error(mbgl::Event::General, "Platform info is null");
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Store X11 window handles
  nativeDisplay = x11Info->display;
  nativeWindow = reinterpret_cast<void *>(x11Info->drawable);

#elif _WIN32
  // Get the Windows drawing surface info
  JAWT_Win32DrawingSurfaceInfo *win32Info =
    (JAWT_Win32DrawingSurfaceInfo *)dsi->platformInfo;
  if (!win32Info) {
    mbgl::Log::Error(mbgl::Event::General, "Platform info is null");
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Store native handles for Vulkan
  nativeWindow = win32Info->hwnd;
  nativeDisplay = nullptr;  // Not needed for Vulkan on Windows

#endif

  // We must unlock the surface immediately after getting the handles
  // Otherwise AWT event processing will be blocked
  ds->FreeDrawingSurfaceInfo(dsi);
  ds->Unlock(ds);
  awt.FreeDrawingSurface(ds);
#endif  // !__APPLE__
}

}  // namespace maplibre_jni

#endif  // USE_VULKAN_BACKEND
