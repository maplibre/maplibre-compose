#ifdef USE_OPENGL_BACKEND

#include <mbgl/gl/renderable_resource.hpp>

#include "canvas_renderer.hpp"
#include "java_classes.hpp"
#include "jawt_context.hpp"
#include "utils.hpp"

#if defined(__linux__)
#include <GL/glx.h>
#include <GL/glxext.h>
#elif defined(_WIN32)
#include <windows.h>
#endif

namespace maplibre_jni {

class OpenGLRenderableResource final : public mbgl::gl::RenderableResource {
 public:
  OpenGLRenderableResource(const OpenGLRenderableResource&) = delete;
  OpenGLRenderableResource(OpenGLRenderableResource&&) = delete;
  auto operator=(const OpenGLRenderableResource&)
    -> OpenGLRenderableResource& = delete;
  auto operator=(OpenGLRenderableResource&&)
    -> OpenGLRenderableResource& = delete;

  explicit OpenGLRenderableResource(
    maplibre_jni::CanvasBackend& backend_, JNIEnv* env, jCanvas canvas_
  )
      : backend(backend_), jawtContext(env, canvas_) {}

  ~OpenGLRenderableResource() {
    // glContext is null if initGL() was never called (e.g. destroyed before first render).
    if (glContext == nullptr) return;
#if defined(__linux__)
    // Pass None as drawable: releasing a context doesn't need a valid window.
    // display is the JVM's persistent X11 connection and remains valid here.
    if (jawtContext.getDisplay() != nullptr) {
      glXMakeCurrent(jawtContext.getDisplay(), None, nullptr);
      glXDestroyContext(jawtContext.getDisplay(), glContext);
    }
#elif defined(_WIN32)
    wglMakeCurrent(nullptr, nullptr);
    wglDeleteContext(glContext);
#endif
  }

  void activate() {
    jint lockResult = jawtContext.lock();
    (void)lockResult;  // JAWT_LOCK_SURFACE_CHANGED: drawable was refreshed above;
                       // existing GLX context remains valid for the new window as
                       // long as the AWT peer uses the same X11 visual (typical).
    if (glContext == nullptr) initGL();
#if defined(__linux__)
    if (glXMakeCurrent(
          jawtContext.getDisplay(), jawtContext.getDrawable(), glContext
        ) == False) {
      jawtContext.unlock();
      throw std::runtime_error("glXMakeCurrent failed");
    }
#elif defined(_WIN32)
    wglMakeCurrent(hdc, glContext);
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
    SwapBuffers(hdc);
#endif
  }

  void deactivate() {
#if defined(__linux__)
    // Use None as drawable when releasing so that a stale drawable doesn't cause
    // a crash if the surface was just recreated (JAWT_LOCK_SURFACE_CHANGED).
    glXMakeCurrent(jawtContext.getDisplay(), None, nullptr);
#elif defined(_WIN32)
    wglMakeCurrent(nullptr, nullptr);
#endif
    jawtContext.unlock();
  }

 private:
#if defined(__linux__)

  void initGL() {
    int fbCount = 0;
    GLXFBConfig* configs = glXChooseFBConfig(
      jawtContext.getDisplay(), DefaultScreen(jawtContext.getDisplay()),
      (int[]){GLX_X_RENDERABLE,
              1,
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
              1,
              0},
      &fbCount
    );
    check(
      configs != nullptr && fbCount > 0, "configs is nullptr or fbCount is 0"
    );

    GLXFBConfig config = configs[0];
    XFree(configs);

    auto glXCreateContextAttribsARB =
      reinterpret_cast<PFNGLXCREATECONTEXTATTRIBSARBPROC>(glXGetProcAddressARB(
        reinterpret_cast<const GLubyte*>("glXCreateContextAttribsARB")
      ));
    check(
      glXCreateContextAttribsARB != nullptr,
      "glXCreateContextAttribsARB is nullptr"
    );

    glContext = glXCreateContextAttribsARB(
      jawtContext.getDisplay(), config, nullptr, 1,
      (int[]){GLX_CONTEXT_MAJOR_VERSION_ARB, 3, GLX_CONTEXT_MINOR_VERSION_ARB,
              0, GLX_CONTEXT_PROFILE_MASK_ARB, GLX_CONTEXT_CORE_PROFILE_BIT_ARB,
              0}
    );
    check(glContext != nullptr, "glContext is nullptr");
  }

#elif defined(_WIN32)

  void initGL() {
    hdc = GetDC(jawtContext.getHwnd());

    // Create pixel format descriptor
    PIXELFORMATDESCRIPTOR pfd = {
      .nSize = sizeof(PIXELFORMATDESCRIPTOR),
      .nVersion = 1,
      .dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER,
      .iPixelType = PFD_TYPE_RGBA,
      .cColorBits = 24,
      .cAlphaBits = 8,
      .cDepthBits = 24,
      .cStencilBits = 8,
    };

    // Choose pixel format
    auto pixelFormat = ChoosePixelFormat(hdc, &pfd);
    if (pixelFormat == 0) {
      auto error = GetLastError();
      throw std::runtime_error(
        "ChoosePixelFormat failed: " + std::to_string(error)
      );
    }
    check(pixelFormat != 0, "ChoosePixelFormat failed");
    check(SetPixelFormat(hdc, pixelFormat, &pfd) != 0, "SetPixelFormat failed");

    // Create temporary context
    auto* tempContext = wglCreateContext(hdc);
    check(tempContext != nullptr, "wglCreateContext failed");
    check(wglMakeCurrent(hdc, tempContext) != 0, "wglMakeCurrent failed");

    // Get function pointer for wglCreateContextAttribsARB
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    auto wglCreateContextAttribsARB = reinterpret_cast<
      HGLRC(WINAPI*)(HDC hDC, HGLRC hShareContext, const int* attribList)>(
      wglGetProcAddress("wglCreateContextAttribsARB")
    );
    check(
      wglCreateContextAttribsARB != nullptr,
      "wglCreateContextAttribsARB not available"
    );

    // Create modern OpenGL context
    std::array<int, 7> attribs = {
      0x2091,  // WGL_CONTEXT_MAJOR_VERSION_ARB
      3,
      0x2092,  // WGL_CONTEXT_MINOR_VERSION_ARB
      0,
      0x9126,  // WGL_CONTEXT_PROFILE_MASK_ARB
      1,       // WGL_CONTEXT_CORE_PROFILE_BIT_ARB,
      0,
    };
    glContext = wglCreateContextAttribsARB(hdc, nullptr, attribs.data());
    check(glContext != nullptr, "failed to create WGL context");

    // Clean up temporary context
    wglMakeCurrent(hdc, nullptr);
    wglDeleteContext(tempContext);
  }

#endif

  maplibre_jni::CanvasBackend& backend;
  JawtContext jawtContext;
#if defined(__linux__)
  GLXContext glContext = nullptr;
#elif defined(_WIN32)
  HDC hdc = nullptr;
  HGLRC glContext = nullptr;
#endif
};

CanvasBackend::CanvasBackend(JNIEnv* env, jCanvas canvas)
    : mbgl::gl::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::gfx::Renderable(
        mbgl::Size(
          java_classes::get<Canvas_class>().getWidth(env, canvas),
          java_classes::get<Canvas_class>().getHeight(env, canvas)
        ),
        std::make_unique<OpenGLRenderableResource>(*this, env, canvas)
      ) {}

auto CanvasBackend::getDefaultRenderable() -> mbgl::gfx::Renderable& {
  return *this;
}

void CanvasBackend::setSize(mbgl::Size size) { this->size = size; }

void CanvasBackend::activate() {
  auto& resource = getResource<OpenGLRenderableResource>();
  resource.activate();
}

void CanvasBackend::deactivate() {
  auto& resource = getResource<OpenGLRenderableResource>();
  resource.deactivate();
}

mbgl::gl::ProcAddress CanvasBackend::getExtensionFunctionPointer(
  const char* name
) {
#if defined(__linux__)
  return glXGetProcAddressARB((const GLubyte*)name);
#elif defined(_WIN32)
  // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
  return reinterpret_cast<mbgl::gl::ProcAddress>(
    GetProcAddress(LoadLibraryA("opengl32.dll"), name)
  );
#endif
}

void CanvasBackend::updateAssumedState() {}

}  // namespace maplibre_jni

#endif
