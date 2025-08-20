#pragma once

#ifdef USE_METAL_BACKEND
#include <mbgl/mtl/renderer_backend.hpp>
#define BACKEND_TYPE CanvasMetalBackend
#endif
#ifdef USE_VULKAN_BACKEND
#include <mbgl/vulkan/renderable_resource.hpp>
#include <mbgl/vulkan/renderer_backend.hpp>
#define BACKEND_TYPE CanvasVulkanBackend
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

#ifdef __linux__
// X11 defines None and Always, which conflict with MapLibre Native
#include <X11/Xlib.h>
#ifdef None
#undef None
#endif
#ifdef Always
#undef Always
#endif
#endif

namespace mbgl {
class Renderer;
}

namespace maplibre_jni {

class CanvasSurfaceInfo {
 public:
  explicit CanvasSurfaceInfo(JNIEnv* env, jCanvas canvas);
  ~CanvasSurfaceInfo();

  inline void lock() { drawingSurface_->Lock(drawingSurface_); }
  inline void unlock() { drawingSurface_->Unlock(drawingSurface_); }

  inline void* getPlatformInfo() { return platformInfo_; }

#if defined(_WIN32)
  inline HWND getNativeWindow() {
    auto win32DrawingSurfaceInfo =
      reinterpret_cast<JAWT_Win32DrawingSurfaceInfo*>(platformInfo_);
    return win32DrawingSurfaceInfo->hwnd;
  }
#elif defined(__linux__)
  inline Display* getNativeDisplay() {
    auto x11DrawingSurfaceInfo =
      reinterpret_cast<JAWT_X11DrawingSurfaceInfo*>(platformInfo_);
    return x11DrawingSurfaceInfo->display;
  }
  inline Drawable getNativeDrawable() {
    auto x11DrawingSurfaceInfo =
      reinterpret_cast<JAWT_X11DrawingSurfaceInfo*>(platformInfo_);
    return x11DrawingSurfaceInfo->drawable;
  }
#endif

 private:
  JAWT jawt_{};
  JAWT_DrawingSurface* drawingSurface_ = nullptr;
  JAWT_DrawingSurfaceInfo* drawingSurfaceInfo_ = nullptr;
  void* platformInfo_ = nullptr;
};

#ifdef USE_METAL_BACKEND

class CanvasMetalBackend : public mbgl::mtl::RendererBackend,
                           public mbgl::gfx::Renderable {
 public:
  explicit CanvasMetalBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void wait() override {}
  void setSize(mbgl::Size);

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
  inline Drawable getNativeDrawable() {
    return surfaceInfo_.getNativeDrawable();
  }
  inline Display* getNativeDisplay() { return surfaceInfo_.getNativeDisplay(); }

 protected:
  void activate() override { surfaceInfo_.lock(); }
  void deactivate() override { surfaceInfo_.unlock(); }
  std::vector<const char*> getInstanceExtensions() override;

 private:
  CanvasSurfaceInfo surfaceInfo_;
};

#endif

#ifdef USE_OPENGL_BACKEND

class CanvasOpenGLBackend : public mbgl::gl::RendererBackend {
 public:
  explicit CanvasOpenGLBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void wait() override;
  void setSize(mbgl::Size);

 protected:
  void activate() override { surfaceInfo_.lock(); }
  void deactivate() override { surfaceInfo_.unlock(); }
  std::unique_ptr<mbgl::gfx::Context> createContext() override;

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
