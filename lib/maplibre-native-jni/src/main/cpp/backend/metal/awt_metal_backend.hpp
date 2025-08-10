#pragma once

#ifdef USE_METAL_BACKEND

#include <jni.h>
#include <mbgl/gfx/renderable.hpp>
#include <mbgl/mtl/renderer_backend.hpp>
#include <mbgl/util/size.hpp>

namespace maplibre_jni {

class MetalBackend final : public mbgl::mtl::RendererBackend,
                           public mbgl::gfx::Renderable {
 public:
  MetalBackend(JNIEnv *env, jobject canvas, int width, int height);
  ~MetalBackend() override;

  // mbgl::gfx::RendererBackend implementation
  mbgl::gfx::Renderable &getDefaultRenderable() override;

  // mbgl::mtl::RendererBackend implementation
  void activate() override {}
  void deactivate() override {}
  void updateAssumedState() override {}

  // Size management
  void setSize(mbgl::Size size);
  mbgl::Size getSize() const;

 private:
  void setupMetalLayer(JNIEnv *env, jobject canvas);
  void releaseNativeWindow();
  JNIEnv *getEnv();

  // Size
  mbgl::Size size;

  // JAWT structures
  void *jawtDrawingSurface = nullptr;
  void *jawtDrawingSurfaceInfo = nullptr;

  // JNI references
  JavaVM *jvm = nullptr;
  jobject canvasRef = nullptr;
};

}  // namespace maplibre_jni

#endif  // USE_METAL_BACKEND
