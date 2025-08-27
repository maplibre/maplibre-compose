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
#endif

#ifdef USE_VULKAN_BACKEND
#include <mbgl/vulkan/renderable_resource.hpp>
#include <mbgl/vulkan/renderer_backend.hpp>
#if defined(__linux__)
#include <vulkan/vulkan_xlib.h>
#elif defined(__linux__)
#include <X11/Xlib.h>
// X11 defines None and Always, which conflict with MapLibre Native
#undef None
#undef Always
#endif
#endif

#ifdef USE_OPENGL_BACKEND
#include <mbgl/gl/renderer_backend.hpp>
#endif

#include <mbgl/actor/scheduler.hpp>
#include <mbgl/gfx/backend_scope.hpp>
#include <mbgl/gfx/renderable.hpp>
#include <mbgl/gfx/renderer_backend.hpp>
#include <mbgl/renderer/renderer_frontend.hpp>
#include <mbgl/renderer/renderer_observer.hpp>
#include <mbgl/renderer/update_parameters.hpp>
#include <mbgl/util/run_loop.hpp>

#include <jni.h>
#include <smjni/java_ref.h>
#include <type_mapping.h>

#include "jawt_info.hpp"

namespace mbgl {
class Renderer;
}

namespace maplibre_jni {

#ifdef USE_OPENGL_BACKEND
class CanvasBackend : public mbgl::gl::RendererBackend,
                      public mbgl::gfx::Renderable {
 public:
  explicit CanvasBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void setSize(mbgl::Size);

 protected:
  void activate() override;
  void deactivate() override;
  mbgl::gl::ProcAddress getExtensionFunctionPointer(const char* name) override;
  void updateAssumedState() override;
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
  std::unique_ptr<CanvasBackend> backend_;
  std::unique_ptr<mbgl::Renderer> renderer_;
  std::unique_ptr<mbgl::RendererObserver> observer_;
  std::unique_ptr<mbgl::UpdateParameters> updateParameters_;

 public:
  void render();
};

}  // namespace maplibre_jni
