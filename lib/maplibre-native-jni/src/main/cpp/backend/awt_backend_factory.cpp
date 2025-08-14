#include "awt_backend_factory.hpp"
#include <mbgl/util/logging.hpp>

namespace maplibre_jni {
// Factory function to create platform-specific backend
std::unique_ptr<PlatformBackend> createPlatformBackend(
  JNIEnv *env, jCanvas canvas, jdouble canvasX, jdouble canvasY,
  jdouble canvasWidth, jdouble canvasHeight
) {
#ifdef USE_METAL_BACKEND
  return std::make_unique<MetalBackend>(
    env, canvas, canvasX, canvasY, canvasWidth, canvasHeight
  );
#else
  mbgl::Log::Error(mbgl::Event::General, "No backend implementation available");
  return nullptr;
#endif
}

}  // namespace maplibre_jni
