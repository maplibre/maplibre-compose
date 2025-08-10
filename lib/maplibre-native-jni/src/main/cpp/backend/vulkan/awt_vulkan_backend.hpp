#pragma once

#ifdef USE_VULKAN_BACKEND

#include <jni.h>
#include <mbgl/util/size.hpp>
#include <mbgl/vulkan/renderable_resource.hpp>
#include <mbgl/vulkan/renderer_backend.hpp>

namespace maplibre_jni {

class VulkanBackend final : public mbgl::vulkan::RendererBackend,
                            public mbgl::vulkan::Renderable {
 public:
  VulkanBackend(JNIEnv *env, jobject canvas, int width, int height);
  ~VulkanBackend() override;

  // mbgl::gfx::RendererBackend implementation
  mbgl::gfx::Renderable &getDefaultRenderable() override;

  // Size management
  void setSize(mbgl::Size size);

  // Platform-specific getters for surface creation
  void *getNativeDisplay() const { return nativeDisplay; }
  void *getNativeWindow() const { return nativeWindow; }

#ifdef __APPLE__
  // macOS-specific methods for MoltenVK
  void setupMacOSLayer(JNIEnv *env, jobject canvas);
  void cleanupMacOSLayer();
  void updateMacOSLayerSize(mbgl::Size newSize);
  void *getMetalLayer();
  void createMacOSSurface(
    const vk::UniqueInstance &instance, vk::UniqueSurfaceKHR &surface
  );
#endif

 protected:
  // mbgl::vulkan::RendererBackend overrides
  void activate() override {}
  void deactivate() override {}
  std::vector<const char *> getInstanceExtensions() override;

 private:
  // JAWT window handle extraction
  void setupVulkanSurface(JNIEnv *env, jobject canvas);
  JNIEnv *getEnv();

  mbgl::Size size;

  // Platform-specific native handles
  void *nativeDisplay = nullptr;
  void *nativeWindow = nullptr;

  // JNI references
  JavaVM *javaVM = nullptr;
  jobject canvasRef = nullptr;
};

}  // namespace maplibre_jni

#endif
