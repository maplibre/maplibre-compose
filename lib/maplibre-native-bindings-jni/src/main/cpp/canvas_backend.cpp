#include <jawt.h>
#include <jni.h>
#include <mbgl/gfx/renderer_backend.hpp>
#include "canvas_renderer.hpp"

namespace maplibre_jni {

CanvasBackend::CanvasBackend(JNIEnv* env, jCanvas canvas)
    : SUPER_BACKEND_TYPE(mbgl::gfx::ContextMode::Unique) {
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

void CanvasBackend::activate() { drawingSurface_->Lock(drawingSurface_); }

void CanvasBackend::deactivate() { drawingSurface_->Unlock(drawingSurface_); }

}  // namespace maplibre_jni
