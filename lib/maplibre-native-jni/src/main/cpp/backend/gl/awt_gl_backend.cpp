#if USE_EGL_BACKEND || USE_WGL_BACKEND || USE_GLX_BACKEND

#include "awt_gl_backend.hpp"
#include <mbgl/gl/context.hpp>
#include <mbgl/gl/renderable_resource.hpp>
#include <mbgl/util/logging.hpp>

// Forward declaration
namespace maplibre_jni {
class GLBackend;
}

// GLRenderableResource in global namespace to match GLFW pattern
class GLRenderableResource final : public mbgl::gl::RenderableResource {
 public:
  explicit GLRenderableResource(maplibre_jni::GLBackend &backend_)
      : backend(backend_) {}

  void bind() override;
  void swap() override;

 private:
  maplibre_jni::GLBackend &backend;
};

namespace maplibre_jni {

GLBackend::GLBackend(
  JNIEnv *env, jobject canvas, int width, int height,
  std::unique_ptr<GLContextStrategy> strategy
)
    : mbgl::gl::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::gfx::Renderable(
        mbgl::Size{static_cast<uint32_t>(width), static_cast<uint32_t>(height)},
        std::make_unique<GLRenderableResource>(*this)
      ),
      size({static_cast<uint32_t>(width), static_cast<uint32_t>(height)}),
      contextStrategy(std::move(strategy)) {
  env->GetJavaVM(&javaVM);
  canvasRef = env->NewGlobalRef(canvas);
  contextStrategy->create(env, canvas);
}

GLBackend::~GLBackend() {
  contextStrategy->destroy();

  if (canvasRef) {
    JNIEnv *env = getEnv();
    if (env) {
      env->DeleteGlobalRef(canvasRef);
    }
    canvasRef = nullptr;
  }
}

mbgl::gfx::Renderable &GLBackend::getDefaultRenderable() { return *this; }

void GLBackend::setSize(mbgl::Size newSize) {
  // Update both our local size and the Renderable's size
  size = newSize;
  this->mbgl::gfx::Renderable::size = newSize;
}

void GLBackend::activate() { contextStrategy->makeCurrent(); }

void GLBackend::deactivate() { contextStrategy->releaseCurrent(); }

mbgl::gl::ProcAddress GLBackend::getExtensionFunctionPointer(const char *name) {
  return reinterpret_cast<mbgl::gl::ProcAddress>(
    contextStrategy->getProcAddress(name)
  );
}

void GLBackend::updateAssumedState() {
  // Reset GL state assumptions
  assumeFramebufferBinding(0);
  setViewport(0, 0, size);
}

void GLBackend::swapBuffers() { contextStrategy->swapBuffers(); }

JNIEnv *GLBackend::getEnv() {
  JNIEnv *env = nullptr;
  if (javaVM) {
    javaVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  }
  return env;
}

}  // namespace maplibre_jni

void GLRenderableResource::bind() {
  backend.setFramebufferBinding(0);
  backend.setViewport(0, 0, backend.getSize());
}

void GLRenderableResource::swap() {
  const auto &swapBehaviour = backend.getSwapBehavior();
  if (swapBehaviour == mbgl::gfx::Renderable::SwapBehaviour::Flush) {
    static_cast<mbgl::gl::Context &>(backend.getContext()).finish();
  }
  backend.swapBuffers();
}

#endif
