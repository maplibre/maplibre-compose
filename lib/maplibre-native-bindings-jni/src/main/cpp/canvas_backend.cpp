#include <jawt.h>
#include <jni.h>
#include <mbgl/gfx/renderer_backend.hpp>
#include "canvas_renderer.hpp"
#include "java_classes.hpp"

namespace maplibre_jni {

void CanvasRenderable::wait() {
  // TODO: what do we do here?
}

CanvasBackend::CanvasBackend(
  JNIEnv* env, jCanvas canvas,
  std::unique_ptr<mbgl::gfx::RenderableResource> resource
)
    : SUPER_BACKEND_TYPE(mbgl::gfx::ContextMode::Unique) {
  renderable_ = std::make_unique<CanvasRenderable>(
    mbgl::Size(
      java_classes::get<Canvas_class>().getWidth(env, canvas),
      java_classes::get<Canvas_class>().getHeight(env, canvas)
    ),
    std::move(resource)
  );

  jawt_.version = JAWT_VERSION_9;
  if (JAWT_GetAWT(env, &jawt_) == JNI_FALSE) {
    throw std::runtime_error("Failed to get AWT");
  }

  JAWT_DrawingSurface* drawingSurface = jawt_.GetDrawingSurface(env, canvas);
  if (!drawingSurface) {
    throw std::runtime_error("Failed to get drawing surface");
  }

  drawingSurface->Lock(drawingSurface);

  JAWT_DrawingSurfaceInfo* drawingSurfaceInfo =
    drawingSurface->GetDrawingSurfaceInfo(drawingSurface);
  if (!drawingSurfaceInfo) {
    drawingSurface->Unlock(drawingSurface);
    throw std::runtime_error("Failed to get drawing surface info");
  }

  drawingSurface->Unlock(drawingSurface);

  if (!drawingSurfaceInfo->platformInfo) {
    throw std::runtime_error("Failed to get platform info");
  }

  drawingSurface_ = drawingSurface;
  drawingSurfaceInfo_ = drawingSurfaceInfo;
  platformInfo_ = drawingSurfaceInfo->platformInfo;
}

CanvasBackend::~CanvasBackend() {
  if (drawingSurface_) {
    if (drawingSurfaceInfo_) {
      drawingSurface_->FreeDrawingSurfaceInfo(drawingSurfaceInfo_);
      drawingSurfaceInfo_ = nullptr;
    }
    jawt_.FreeDrawingSurface(drawingSurface_);
    drawingSurface_ = nullptr;
  }
}

mbgl::gfx::Renderable& CanvasBackend::getDefaultRenderable() {
  return *renderable_;
}

void CanvasBackend::activate() { drawingSurface_->Lock(drawingSurface_); }

void CanvasBackend::deactivate() { drawingSurface_->Unlock(drawingSurface_); }

}  // namespace maplibre_jni
