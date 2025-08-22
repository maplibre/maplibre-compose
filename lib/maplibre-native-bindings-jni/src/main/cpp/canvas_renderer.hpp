#pragma once

#ifdef _WIN32
#include <windows.h>
#elif defined(__linux__)
#include <X11/Xlib.h>
// X11 defines None and Always, which conflict with MapLibre Native
#undef None
#undef Always
#endif

#ifdef USE_METAL_BACKEND
#include <mbgl/mtl/renderer_backend.hpp>
#define BACKEND_TYPE CanvasMetalBackend
#endif

#ifdef USE_VULKAN_BACKEND
#include <mbgl/vulkan/renderable_resource.hpp>
#include <mbgl/vulkan/renderer_backend.hpp>
#define BACKEND_TYPE CanvasVulkanBackend
#if defined(__linux__)
#include <vulkan/vulkan_xlib.h>
#elif defined(_WIN32)
#include <vulkan/vulkan_win32.h>
#elif defined(__APPLE__)
#include <vulkan/vulkan_metal.h>
#endif
#endif

#ifdef USE_OPENGL_BACKEND
#include <mbgl/gl/renderer_backend.hpp>
#define BACKEND_TYPE CanvasOpenGLBackend
#endif

#include <mbgl/actor/scheduler.hpp>
#include <mbgl/gfx/backend_scope.hpp>
#include <mbgl/gfx/renderable.hpp>
#include <mbgl/gfx/renderer_backend.hpp>
#include <mbgl/renderer/renderer_frontend.hpp>
#include <mbgl/renderer/renderer_observer.hpp>
#include <mbgl/renderer/update_parameters.hpp>
#include <mbgl/util/run_loop.hpp>

#include <jawt.h>
#include <jawt_md.h>
#include <jni.h>
#include <smjni/java_ref.h>
#include <type_mapping.h>

namespace mbgl {
class Renderer;
}

namespace maplibre_jni {

class CanvasSurfaceInfo {
 public:
  explicit CanvasSurfaceInfo(JNIEnv* env, jCanvas canvas);
  ~CanvasSurfaceInfo();

  void lock();
  void unlock();

  void* getPlatformInfo();

#if defined(_WIN32)
  HWND getNativeWindow();
#elif defined(__linux__)
  Display* getNativeDisplay();
  Drawable getNativeDrawable();
#elif defined(__APPLE__)
  void* createMetalLayer();
#endif

 private:
  JAWT jawt_{};
  JAWT_DrawingSurface* drawingSurface_ = nullptr;
  JAWT_DrawingSurfaceInfo* drawingSurfaceInfo_ = nullptr;
  void* platformInfo_ = nullptr;
#ifdef __APPLE__
  void* metalLayer_ = nullptr;
#endif
};

#ifdef USE_METAL_BACKEND
class CanvasMetalBackend : public mbgl::mtl::RendererBackend,
                           public mbgl::gfx::Renderable {
 public:
  explicit CanvasMetalBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void wait() override {}
  void setSize(mbgl::Size);
  inline CanvasSurfaceInfo& getSurfaceInfo() { return surfaceInfo_; }

 protected:
  void activate() override { surfaceInfo_.lock(); }
  void deactivate() override { surfaceInfo_.unlock(); }
  std::unique_ptr<mbgl::gfx::Context> createContext() override;
  void updateAssumedState() override {}

 private:
  CanvasSurfaceInfo surfaceInfo_;
};
#endif

#ifdef USE_VULKAN_BACKEND
class CanvasVulkanBackend : public mbgl::vulkan::RendererBackend,
                            public mbgl::vulkan::Renderable {
 public:
  explicit CanvasVulkanBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void wait() override {}
  void setSize(mbgl::Size);
  inline CanvasSurfaceInfo& getSurfaceInfo() { return surfaceInfo_; }

 protected:
  void activate() override { surfaceInfo_.lock(); }
  void deactivate() override { surfaceInfo_.unlock(); }
  std::vector<const char*> getInstanceExtensions() override;

 private:
  CanvasSurfaceInfo surfaceInfo_;
};
#endif

#ifdef USE_OPENGL_BACKEND
class CanvasOpenGLBackend : public mbgl::gl::RendererBackend,
                            public mbgl::gfx::Renderable {
 public:
  explicit CanvasOpenGLBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void setSize(mbgl::Size);
  inline CanvasSurfaceInfo& getSurfaceInfo() { return surfaceInfo_; }

 protected:
  void activate() override;
  void deactivate() override;
  mbgl::gl::ProcAddress getExtensionFunctionPointer(const char* name) override;
  void updateAssumedState() override;

 private:
  CanvasSurfaceInfo surfaceInfo_;
};
#endif

class CanvasRenderer : public mbgl::RendererFrontend {
 public:
  explicit CanvasRenderer(
    JNIEnv* env, jCanvasRenderer canvasFrontend, float pixelRatio
  );
  void reset() override;
  void setObserver(mbgl::RendererObserver& observer) override;
  void update(std::shared_ptr<mbgl::UpdateParameters> params) override;
  const mbgl::TaggedScheduler& getThreadPool() const override;
  void setSize(mbgl::Size size);
  std::unique_ptr<mbgl::util::RunLoop> runLoop_;

 private:
  smjni::global_java_ref<jCanvasRenderer> canvasRenderer_;
  std::unique_ptr<BACKEND_TYPE> backend_;
  std::unique_ptr<mbgl::Renderer> renderer_;
  std::unique_ptr<mbgl::RendererObserver> observer_;
  std::unique_ptr<mbgl::UpdateParameters> updateParameters_;

 public:
  void render();
};

}  // namespace maplibre_jni
