#pragma once

#include <mbgl/actor/scheduler.hpp>
#include <mbgl/gfx/backend_scope.hpp>
#include <mbgl/gfx/renderable.hpp>
#include <mbgl/gfx/renderer_backend.hpp>
#include <mbgl/renderer/renderer_frontend.hpp>
#include <mbgl/renderer/renderer_observer.hpp>
#include <mbgl/util/run_loop.hpp>

#include <jawt.h>
#include <jawt_md.h>
#include <smjni/java_ref.h>
#include <type_mapping.h>

// clang-format off
#include "fix_x11_pollution.h"
// clang-format on

#if defined(USE_METAL_BACKEND)
#include <mbgl/mtl/renderer_backend.hpp>
#elif defined(USE_OPENGL_BACKEND)
#include <mbgl/gl/renderer_backend.hpp>
#endif

namespace mbgl {
class Renderer;
}

namespace maplibre_jni {

#if defined(USE_METAL_BACKEND)
class CanvasBackend : public mbgl::mtl::RendererBackend,
                      public mbgl::gfx::Renderable {
 public:
  explicit CanvasBackend(JNIEnv* env, jCanvas canvas);
  auto getDefaultRenderable() -> mbgl::gfx::Renderable& override;
  void setSize(mbgl::Size);

 protected:
  void activate() override;
  void deactivate() override;
  void updateAssumedState() override {}
};

#elif defined(USE_OPENGL_BACKEND)

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

#elif defined(USE_VULKAN_BACKEND)
#error "TODO: Implement Vulkan Backend"
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
  void runOnce();

 private:
  std::unique_ptr<mbgl::util::RunLoop> runLoop_;
  smjni::global_java_ref<jCanvasRenderer> canvasRenderer_;
  std::unique_ptr<CanvasBackend> backend_;
  std::unique_ptr<mbgl::Renderer> renderer_;
  std::unique_ptr<mbgl::RendererObserver> observer_;
  std::shared_ptr<mbgl::UpdateParameters> updateParameters_;

 public:
  void render();
};

}  // namespace maplibre_jni
