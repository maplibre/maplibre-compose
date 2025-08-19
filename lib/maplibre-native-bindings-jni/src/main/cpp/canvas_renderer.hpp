#pragma once

#include <cstddef>
#include <jawt.h>
#include <jni.h>
#include <mbgl/actor/scheduler.hpp>
#include <mbgl/gfx/backend_scope.hpp>
#include <mbgl/gfx/renderable.hpp>
#include <mbgl/gfx/renderer_backend.hpp>
#include <mbgl/mtl/renderable_resource.hpp>
#include <mbgl/renderer/renderer.hpp>
#include <mbgl/renderer/renderer_frontend.hpp>
#include <mbgl/renderer/renderer_observer.hpp>
#include <mbgl/renderer/update_parameters.hpp>
#include <mbgl/util/run_loop.hpp>
#include <smjni/java_ref.h>
#include <type_mapping.h>

#ifdef USE_METAL_BACKEND
#include <mbgl/mtl/renderer_backend.hpp>
#define SUPER_BACKEND_TYPE mbgl::mtl::RendererBackend
#define BACKEND_TYPE CanvasMetalBackend
#endif
#ifdef USE_VULKAN_BACKEND
#include <mbgl/vulkan/renderable_resource.hpp>
#include <mbgl/vulkan/renderer_backend.hpp>
#define SUPER_BACKEND_TYPE mbgl::vulkan::RendererBackend
#define BACKEND_TYPE CanvasVulkanBackend
#endif
#ifdef USE_OPENGL_BACKEND
#include <mbgl/gl/renderer_backend.hpp>
#define SUPER_BACKEND_TYPE mbgl::gl::RendererBackend
#define BACKEND_TYPE CanvasOpenGLBackend
#endif

namespace maplibre_jni {

class CanvasBackend : public SUPER_BACKEND_TYPE {
 public:
  explicit CanvasBackend(JNIEnv* env, jCanvas canvas);
  virtual ~CanvasBackend() override;
  virtual void setSize(mbgl::Size) = 0;
  inline void* getPlatformInfo() { return platformInfo_; }

 protected:
  virtual void activate() override;
  virtual void deactivate() override;

#ifdef USE_VULKAN_BACKEND
  virtual std::vector<const char*> getInstanceExtensions() override = 0;
#endif

  JAWT jawt_;
  JAWT_DrawingSurface* drawingSurface_ = nullptr;
  JAWT_DrawingSurfaceInfo* drawingSurfaceInfo_ = nullptr;
  void* platformInfo_ = nullptr;
};

#ifdef USE_METAL_BACKEND

class CanvasMetalBackend : public CanvasBackend, public mbgl::gfx::Renderable {
 public:
  explicit CanvasMetalBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void wait() override;
  void setSize(mbgl::Size) override;

 protected:
  std::unique_ptr<mbgl::gfx::Context> createContext() override;
  void updateAssumedState() override;
};

#endif

#ifdef USE_VULKAN_BACKEND

class CanvasVulkanBackend : public CanvasBackend,
                            public mbgl::vulkan::Renderable {
 public:
  explicit CanvasVulkanBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void wait() override;
  void setSize(mbgl::Size) override;

 protected:
  std::vector<const char*> getInstanceExtensions() override;
};

#endif

#ifdef USE_OPENGL_BACKEND

class CanvasOpenGLBackend : public CanvasBackend {
 public:
  explicit CanvasOpenGLBackend(JNIEnv* env, jCanvas canvas);
  mbgl::gfx::Renderable& getDefaultRenderable() override;
  void wait() override;
  void setSize(mbgl::Size) override;

 protected:
  std::unique_ptr<mbgl::gfx::Context> createContext() override;
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
