#include <iostream>
#include <memory>
#ifdef USE_OPENGL_BACKEND

#include <string>

#include <EGL/eglplatform.h>
#ifdef USE_OPENGL_BACKEND

#include <mbgl/gl/renderable_resource.hpp>

#include "canvas_renderer.hpp"
#include "java_classes.hpp"

#if defined(__linux__)
#include <EGL/egl.h>
#include <EGL/eglext.h>
#endif

namespace maplibre_jni {

class OpenGLRenderableResource final : public mbgl::gl::RenderableResource {
 public:
  explicit OpenGLRenderableResource(
    maplibre_jni::CanvasBackend &backend_, jCanvas canvas_
  )
      : backend(backend_), canvas(canvas_) {}

  void activate() {
    std::cout << "OpenGLRenderableResource::activate" << std::endl;
    try {
      activeJawtInfo =
        std::make_unique<JawtInfo>(smjni::jni_provider::get_jni(), canvas);
      auto changed = activeJawtInfo->lock();
      if (changed) {
        deinitGL();
        initGL();
      }
    } catch (const std::exception &e) {
      std::cout
        << "OpenGLRenderableResource::activate: waiting for drawing surface: "
        << e.what() << std::endl;
      activeJawtInfo.reset();
      // Skip this frame; next platform callback will retry
    }
  }

  void deactivate() {
    std::cout << "OpenGLRenderableResource::deactivate" << std::endl;
    if (activeJawtInfo) {
      activeJawtInfo->unlock();
    }
    activeJawtInfo.reset();
  }

  void deinitGL() {
    std::cout << "OpenGLRenderableResource::deinitGL" << std::endl;
#if defined(__linux__)
    if (eglContext != EGL_NO_CONTEXT) {
      eglMakeCurrent(
        eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT
      );
      eglDestroyContext(eglDisplay, eglContext);
      eglContext = EGL_NO_CONTEXT;
    }
    if (eglSurface != EGL_NO_SURFACE) {
      eglDestroySurface(eglDisplay, eglSurface);
      eglSurface = EGL_NO_SURFACE;
    }
    if (eglDisplay != EGL_NO_DISPLAY) {
      eglTerminate(eglDisplay);
      eglDisplay = EGL_NO_DISPLAY;
    }
    eglConfig = nullptr;
#elif defined(_WIN32)
    if (hglrc) {
      wglMakeCurrent(nullptr, nullptr);
      wglDeleteContext(hglrc);
      hglrc = nullptr;
    }
    if (hdc && hwnd) {
      ReleaseDC(hwnd, hdc);
      hdc = nullptr;
    }
    hwnd = nullptr;
#endif
  }

  void initGL() {
    std::cout << "OpenGLRenderableResource::initGL" << std::endl;
#if defined(__linux__)
    if (!activeJawtInfo) {
      throw std::runtime_error("JAWT info not set before initGL");
    }
    auto *x11 = activeJawtInfo->getPlatformInfo();
    if (!x11 || !x11->display || x11->drawable == 0) {
      throw std::runtime_error("Invalid X11 platform info for EGL");
    }
    eglDisplay = eglGetDisplay((EGLNativeDisplayType)x11->display);
    if (eglDisplay == EGL_NO_DISPLAY) {
      throw std::runtime_error("Failed to get EGL display");
    }
    EGLint major, minor;
    if (!eglInitialize(eglDisplay, &major, &minor)) {
      throw std::runtime_error("Failed to initialize EGL");
    }
    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
      throw std::runtime_error("Failed to bind EGL API (GLES)");
    }

    const EGLint configAttribs[] = {
      EGL_SURFACE_TYPE,
      EGL_WINDOW_BIT,
      EGL_RED_SIZE,
      8,
      EGL_GREEN_SIZE,
      8,
      EGL_BLUE_SIZE,
      8,
      EGL_ALPHA_SIZE,
      8,
      EGL_DEPTH_SIZE,
      24,
      EGL_STENCIL_SIZE,
      8,
      EGL_RENDERABLE_TYPE,
      EGL_OPENGL_ES3_BIT,
      EGL_NONE
    };

    EGLint numConfigs;
    if (!eglChooseConfig(
          eglDisplay, configAttribs, &eglConfig, 1, &numConfigs
        ) ||
        numConfigs == 0) {
      throw std::runtime_error("Failed to choose EGL config");
    }

    eglSurface = eglCreateWindowSurface(
      eglDisplay, eglConfig, (EGLNativeWindowType)x11->drawable, nullptr
    );
    if (eglSurface == EGL_NO_SURFACE) {
      throw std::runtime_error("Failed to create EGL surface");
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};

    eglContext =
      eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext == EGL_NO_CONTEXT) {
      throw std::runtime_error("Failed to create EGL context");
    }
#endif
  }

  ~OpenGLRenderableResource() {
#if defined(__linux__)
    if (eglDisplay != EGL_NO_DISPLAY) {
      eglMakeCurrent(
        eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT
      );
      if (eglContext != EGL_NO_CONTEXT) {
        eglDestroyContext(eglDisplay, eglContext);
      }
      if (eglSurface != EGL_NO_SURFACE) {
        eglDestroySurface(eglDisplay, eglSurface);
      }
      eglTerminate(eglDisplay);
    }
#endif
  }

  void bind() override {
    std::cout << "OpenGLRenderableResource::bind" << std::endl;
    backend.setFramebufferBinding(0);
    backend.setViewport(0, 0, backend.getSize());
#if defined(__linux__)
    if (eglDisplay == EGL_NO_DISPLAY || eglContext == EGL_NO_CONTEXT ||
        eglSurface == EGL_NO_SURFACE) {
      // Not initialized yet; skip this frame
      return;
    }
    auto ret = eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    if (ret == EGL_FALSE) {
      auto error = eglGetError();
      throw std::runtime_error(
        "Failed to make EGL context current: " + std::to_string(error)
      );
    }
#endif
  }

  void swap() override {
    std::cout << "OpenGLRenderableResource::swap" << std::endl;
#if defined(__linux__)
    if (eglDisplay == EGL_NO_DISPLAY || eglContext == EGL_NO_CONTEXT ||
        eglSurface == EGL_NO_SURFACE) {
      // Not initialized yet; skip
      return;
    }
    eglSwapBuffers(eglDisplay, eglSurface);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
#endif
  }

 private:
  maplibre_jni::CanvasBackend &backend;
  jCanvas canvas;
  std::shared_ptr<JawtInfo> activeJawtInfo;

#if defined(__linux__)
  EGLDisplay eglDisplay = EGL_NO_DISPLAY;
  EGLContext eglContext = EGL_NO_CONTEXT;
  EGLSurface eglSurface = EGL_NO_SURFACE;
  EGLConfig eglConfig = nullptr;
#endif
};

CanvasBackend::CanvasBackend(JNIEnv *env, jCanvas canvas)
    : mbgl::gl::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::gfx::Renderable(
        mbgl::Size(
          java_classes::get<Canvas_class>().getWidth(env, canvas),
          java_classes::get<Canvas_class>().getHeight(env, canvas)
        ),
        std::make_unique<OpenGLRenderableResource>(*this, canvas)
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
  return eglGetProcAddress(name);
#endif
}

void CanvasBackend::updateAssumedState() {
  assumeFramebufferBinding(0);
  setViewport(0, 0, size);
}

}  // namespace maplibre_jni

#endif

#endif
