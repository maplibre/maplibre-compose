#pragma once

#include "smjni/java_ref.h"
#include "type_mapping.h"
#ifdef USE_METAL_BACKEND

#include <jni.h>
#include <mbgl/gfx/renderable.hpp>
#include <mbgl/mtl/renderer_backend.hpp>
#include <mbgl/util/size.hpp>

namespace maplibre_jni {

class MetalBackend final : public mbgl::mtl::RendererBackend,
                           public mbgl::gfx::Renderable {
 public:
  MetalBackend(JNIEnv *env, jCanvas canvas);
  ~MetalBackend() override = default;

  // mbgl::gfx::RendererBackend implementation
  mbgl::gfx::Renderable &getDefaultRenderable() override;

  // mbgl::mtl::RendererBackend implementation
  void activate() override {}
  void deactivate() override {}
  void updateAssumedState() override {}

  // Size management
  void setSize(mbgl::Size size);

 private:
  void setupMetalLayer(JNIEnv *env, jCanvas canvas);
  smjni::global_java_ref<jCanvas> canvasRef;
};

}  // namespace maplibre_jni

#endif  // USE_METAL_BACKEND
