#include "awt_backend_factory.hpp"
#include <mbgl/util/logging.hpp>

#if USE_EGL_BACKEND || USE_WGL_BACKEND || USE_GLX_BACKEND
#include "gl_context_strategy.hpp"
#ifdef USE_EGL_BACKEND
#include "egl_context_strategy.hpp"
#elif USE_WGL_BACKEND
#include "wgl_context_strategy.hpp"
#elif USE_GLX_BACKEND
#include "glx_context_strategy.hpp"
#endif
#endif

namespace maplibre_jni {
// Factory function to create platform-specific backend
std::unique_ptr<PlatformBackend> createPlatformBackend(
  JNIEnv *env, jobject canvas, int width, int height
) {
#ifdef USE_METAL_BACKEND
  return std::make_unique<MetalBackend>(env, canvas, width, height);
#elif USE_VULKAN_BACKEND
  return std::make_unique<VulkanBackend>(env, canvas, width, height);
#elif USE_EGL_BACKEND
  auto strategy = std::make_unique<EGLContextStrategy>();
  return std::make_unique<GLBackend>(
    env, canvas, width, height, std::move(strategy)
  );
#elif USE_WGL_BACKEND
  auto strategy = std::make_unique<WGLContextStrategy>();
  return std::make_unique<GLBackend>(
    env, canvas, width, height, std::move(strategy)
  );
#elif USE_GLX_BACKEND
  auto strategy = std::make_unique<GLXContextStrategy>();
  return std::make_unique<GLBackend>(
    env, canvas, width, height, std::move(strategy)
  );
#else
  mbgl::Log::Error(mbgl::Event::General, "No backend implementation available");
  return nullptr;
#endif
}

}  // namespace maplibre_jni
