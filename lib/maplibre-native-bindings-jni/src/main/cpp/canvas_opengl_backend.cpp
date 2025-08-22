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
#elif defined(_WIN32)
#include <windows.h>
#endif

namespace maplibre_jni {

class OpenGLRenderableResource final : public mbgl::gl::RenderableResource {
 public:
  explicit OpenGLRenderableResource(maplibre_jni::CanvasOpenGLBackend &backend_)
      : backend(backend_) {}

  void init(CanvasSurfaceInfo &canvasInfo) {
#if defined(__linux__)
    eglDisplay = eglGetDisplay(canvasInfo.getNativeDisplay());
    if (eglDisplay == EGL_NO_DISPLAY) {
      throw std::runtime_error("Failed to get EGL display");
    }
    EGLint major, minor;
    if (!eglInitialize(eglDisplay, &major, &minor)) {
      throw std::runtime_error("Failed to initialize EGL");
    }
    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
      throw std::runtime_error("Failed to bind EGL API");
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
      EGL_OPENGL_ES2_BIT,
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
      eglDisplay, eglConfig, canvasInfo.getNativeDrawable(), nullptr
    );
    if (eglSurface == EGL_NO_SURFACE) {
      throw std::runtime_error("Failed to create EGL surface");
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};

    eglContext =
      eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext == EGL_NO_CONTEXT) {
      throw std::runtime_error("Failed to create EGL context");
    }

    if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      auto error = eglGetError();
      throw std::runtime_error(
        "Failed to make EGL context current: " + std::to_string(error)
      );
    }
#elif defined(_WIN32)
    hwnd = canvasInfo.getNativeWindow();
    hdc = GetDC(hwnd);
    if (!hdc) {
      throw std::runtime_error("Failed to get device context");
    }
    PIXELFORMATDESCRIPTOR pfd = {
      .nSize = sizeof(pfd),
      .nVersion = 1,
      .dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER,
      .iPixelType = PFD_TYPE_RGBA,
      .cColorBits = 24,
      .cAlphaBits = 8,
      .cDepthBits = 24,
      .cStencilBits = 8,
      .iLayerType = PFD_MAIN_PLANE,
      .dwFlagsEx = 0,
    };

    int pixelFormat = ChoosePixelFormat(hdc, &pfd);
    if (pixelFormat == 0) {
      throw std::runtime_error("Failed to choose pixel format");
    }

    if (!SetPixelFormat(hdc, pixelFormat, &pfd)) {
      throw std::runtime_error("Failed to set pixel format");
    }

    HGLRC tempContext = wglCreateContext(hdc);
    if (!tempContext) {
      throw std::runtime_error("Failed to create temporary WGL context");
    }

    if (!wglMakeCurrent(hdc, tempContext)) {
      wglDeleteContext(tempContext);
      throw std::runtime_error("Failed to make temporary context current");
    }

    const int contextAttribs[] = {
      WGL_CONTEXT_MAJOR_VERSION_ARB,
      3,
      WGL_CONTEXT_MINOR_VERSION_ARB,
      0,
      WGL_CONTEXT_PROFILE_MASK_ARB,
      WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB,
      0
    };

    hglrc =
      mbgl::platform::wglCreateContextAttribsARB(hdc, nullptr, contextAttribs);

    wglMakeCurrent(nullptr, nullptr);
    wglDeleteContext(tempContext);

    if (!hglrc) {
      throw std::runtime_error("Failed to create OpenGL 3.0 context");
    }

    if (!wglMakeCurrent(hdc, hglrc)) {
      throw std::runtime_error("Failed to make WGL context current");
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
#elif defined(_WIN32)
    if (hglrc) {
      wglMakeCurrent(nullptr, nullptr);
      wglDeleteContext(hglrc);
    }
    if (hdc && hwnd) {
      ReleaseDC(hwnd, hdc);
    }
#endif
  }

  void bind() override {
    backend.setFramebufferBinding(0);
    backend.setViewport(0, 0, backend.getSize());
#if defined(__linux__)
    if (eglDisplay != EGL_NO_DISPLAY && eglContext != EGL_NO_CONTEXT &&
        eglSurface != EGL_NO_SURFACE) {
      if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
        throw std::runtime_error("Failed to make EGL context current");
      }
    }
#elif defined(_WIN32)
    if (hdc && hglrc) {
      wglMakeCurrent(hdc, hglrc);
    }
#endif
  }

  void swap() override {
#if defined(__linux__)
    if (eglDisplay != EGL_NO_DISPLAY) {
      eglMakeCurrent(
        eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT
      );
      if (eglSurface != EGL_NO_SURFACE) {
        eglSwapBuffers(eglDisplay, eglSurface);
      }
    }
#elif defined(_WIN32)
    if (hdc) {
      wglMakeCurrent(nullptr, nullptr);
      SwapBuffers(hdc)
    }
#endif
  }

 private:
  maplibre_jni::CanvasOpenGLBackend &backend;

#if defined(__linux__)
  EGLDisplay eglDisplay = EGL_NO_DISPLAY;
  EGLContext eglContext = EGL_NO_CONTEXT;
  EGLSurface eglSurface = EGL_NO_SURFACE;
  EGLConfig eglConfig = nullptr;
#elif defined(_WIN32)
  HWND hwnd = nullptr;
  HDC hdc = nullptr;
  HGLRC hglrc = nullptr;
#endif
};

CanvasOpenGLBackend::CanvasOpenGLBackend(JNIEnv *env, jCanvas canvas)
    : mbgl::gl::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::gfx::Renderable(
        mbgl::Size(
          java_classes::get<Canvas_class>().getWidth(env, canvas),
          java_classes::get<Canvas_class>().getHeight(env, canvas)
        ),
        std::make_unique<OpenGLRenderableResource>(*this)
      ),
      surfaceInfo_(env, canvas) {
  getResource<OpenGLRenderableResource>().init(surfaceInfo_);
}

mbgl::gfx::Renderable &CanvasOpenGLBackend::getDefaultRenderable() {
  return *this;
}

void CanvasOpenGLBackend::setSize(mbgl::Size size) { this->size = size; }

void CanvasOpenGLBackend::activate() { this->surfaceInfo_.lock(); }

void CanvasOpenGLBackend::deactivate() { this->surfaceInfo_.unlock(); }

mbgl::gl::ProcAddress CanvasOpenGLBackend::getExtensionFunctionPointer(
  const char *name
) {
#if defined(__linux__)
  return eglGetProcAddress(name);
#elif defined(_WIN32)
  return wglGetProcAddress(name);
#endif
}

void CanvasOpenGLBackend::updateAssumedState() {
  assumeFramebufferBinding(0);
  setViewport(0, 0, size);
}

}  // namespace maplibre_jni

#endif

#endif
