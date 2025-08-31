#ifdef USE_OPENGL_BACKEND

#include <mbgl/gl/renderable_resource.hpp>

#include "canvas_renderer.hpp"
#include "java_classes.hpp"
#include "jawt_context.hpp"
#include "utils.hpp"

#if defined(__linux__)
#include <GL/glx.h>
#include <GL/glxext.h>
#endif

namespace maplibre_jni {

class OpenGLRenderableResource final : public mbgl::gl::RenderableResource {
 public:
  explicit OpenGLRenderableResource(
    maplibre_jni::CanvasBackend &backend_, JNIEnv *env, jCanvas canvas_
  )
      : backend(backend_), jawtContext(env, canvas_) {}

  ~OpenGLRenderableResource() {
    check(glContext != nullptr, "glContext is nullptr");
#if defined(__linux__)
    glXMakeCurrent(
      jawtContext.getDisplay(), jawtContext.getDrawable(), nullptr
    );
    glXDestroyContext(jawtContext.getDisplay(), glContext);
#elif defined(_WIN32)
    wglMakeCurrent(jawtContext.getHdc(), nullptr);
    wglDeleteContext(glContext);
#endif
  }

  void bind() override {
    backend.setFramebufferBinding(0);
    backend.setViewport(0, 0, backend.getSize());
  }

  void swap() override {
#if defined(__linux__)
    glXSwapBuffers(jawtContext.getDisplay(), jawtContext.getDrawable());
#elif defined(_WIN32)
    wglSwapBuffers(jawtContext.getHdc());
#endif
  }

  void activate() {
    jawtContext.lock();
    if (glContext == nullptr) initGL();
#if defined(__linux__)
    glXMakeCurrent(
      jawtContext.getDisplay(), jawtContext.getDrawable(), glContext
    );
#elif defined(_WIN32)
    wglMakeCurrent(jawtContext.getHdc(), glContext);
#endif
  }

  void deactivate() {
    jawtContext.unlock();
#if defined(__linux__)
    glXMakeCurrent(
      jawtContext.getDisplay(), jawtContext.getDrawable(), nullptr
    );
#elif defined(_WIN32)
    wglMakeCurrent(jawtContext.getHdc(), nullptr);
#endif
  }

 private:
#if defined(__linux__)

  void initGL() {
    int fbCount = 0;
    GLXFBConfig *configs = glXChooseFBConfig(
      jawtContext.getDisplay(), DefaultScreen(jawtContext.getDisplay()),
      (int[]){GLX_X_RENDERABLE,
              True,
              GLX_DRAWABLE_TYPE,
              GLX_WINDOW_BIT,
              GLX_RENDER_TYPE,
              GLX_RGBA_BIT,
              GLX_X_VISUAL_TYPE,
              GLX_TRUE_COLOR,
              GLX_RED_SIZE,
              8,
              GLX_GREEN_SIZE,
              8,
              GLX_BLUE_SIZE,
              8,
              GLX_ALPHA_SIZE,
              8,
              GLX_DEPTH_SIZE,
              24,
              GLX_STENCIL_SIZE,
              8,
              GLX_DOUBLEBUFFER,
              True,
              None},
      &fbCount
    );
    check(
      configs != nullptr && fbCount > 0, "configs is nullptr or fbCount is 0"
    );

    GLXFBConfig config = configs[0];
    XFree(configs);

    auto glXCreateContextAttribsARB =
      reinterpret_cast<PFNGLXCREATECONTEXTATTRIBSARBPROC>(glXGetProcAddressARB(
        reinterpret_cast<const GLubyte *>("glXCreateContextAttribsARB")
      ));
    check(
      glXCreateContextAttribsARB != nullptr,
      "glXCreateContextAttribsARB is nullptr"
    );

    glContext = glXCreateContextAttribsARB(
      jawtContext.getDisplay(), config, nullptr, True,
      (int[]){GLX_CONTEXT_MAJOR_VERSION_ARB, 3, GLX_CONTEXT_MINOR_VERSION_ARB,
              0, GLX_CONTEXT_PROFILE_MASK_ARB, GLX_CONTEXT_CORE_PROFILE_BIT_ARB,
              None}
    );
    check(glContext != nullptr, "glContext is nullptr");
  }

#elif defined(_WIN32)

  void initGL() {
#error "TODO"
  }

#endif

  maplibre_jni::CanvasBackend &backend;
  JawtContext jawtContext;
#if defined(__linux__)
  GLXContext glContext = nullptr;
#elif defined(_WIN32)
  HGLRC glContext = nullptr;
#endif
};

CanvasBackend::CanvasBackend(JNIEnv *env, jCanvas canvas)
    : mbgl::gl::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::gfx::Renderable(
        mbgl::Size(
          java_classes::get<Canvas_class>().getWidth(env, canvas),
          java_classes::get<Canvas_class>().getHeight(env, canvas)
        ),
        std::make_unique<OpenGLRenderableResource>(*this, env, canvas)
      ) {}

mbgl::gfx::Renderable &CanvasBackend::getDefaultRenderable() { return *this; }

void CanvasBackend::setSize(mbgl::Size size) { this->size = size; }

void CanvasBackend::activate() {
  auto &resource = getResource<OpenGLRenderableResource>();
  resource.activate();
}

void CanvasBackend::deactivate() {
  auto &resource = getResource<OpenGLRenderableResource>();
  resource.deactivate();
}

mbgl::gl::ProcAddress CanvasBackend::getExtensionFunctionPointer(
  const char *name
) {
#if defined(__linux__)
  return glXGetProcAddressARB((const GLubyte *)name);
#elif defined(_WIN32)
  return wglGetProcAddress(name);
#endif
}

void CanvasBackend::updateAssumedState() {}

}  // namespace maplibre_jni

#endif
