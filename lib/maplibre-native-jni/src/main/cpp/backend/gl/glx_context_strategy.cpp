#ifdef USE_GLX_BACKEND

#include "glx_context_strategy.hpp"
#include <cstring>
#include <jawt.h>
#include <jawt_md.h>
#include <mbgl/util/logging.hpp>
#include <stdexcept>

namespace maplibre_jni {

// Function pointer types for GLX extensions
typedef GLXContext (*PFNGLXCREATECONTEXTATTRIBSARBPROC)(
  Display *, GLXFBConfig, GLXContext, Bool, const int *
);

GLXContextStrategy::~GLXContextStrategy() { destroy(); }

void GLXContextStrategy::create(JNIEnv *env, jobject canvas) {
  // Get the AWT interface
  JAWT awt;
  awt.version = JAWT_VERSION_9;
  if (!JAWT_GetAWT(env, &awt)) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to get AWT");
    return;
  }

  // Get the drawing surface
  JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
  if (!ds) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to get drawing surface");
    return;
  }

  // Lock the drawing surface
  jint lock = ds->Lock(ds);
  if ((lock & JAWT_LOCK_ERROR) != 0) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to lock drawing surface");
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Get the drawing surface info
  JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
  if (!dsi) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to get drawing surface info");
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Get the X11 drawing info
  JAWT_X11DrawingSurfaceInfo *x11Info =
    (JAWT_X11DrawingSurfaceInfo *)dsi->platformInfo;
  display = x11Info->display;
  window = x11Info->drawable;

  // Important: Release the drawing surface ASAP to avoid blocking AWT
  ds->FreeDrawingSurfaceInfo(dsi);
  ds->Unlock(ds);
  awt.FreeDrawingSurface(ds);

  if (!display || !window) {
    mbgl::Log::Error(
      mbgl::Event::OpenGL, "Failed to get X11 display or window"
    );
    return;
  }

  // Check for GLX extension
  const char *extensions =
    glXQueryServerString(display, DefaultScreen(display), GLX_EXTENSIONS);
  if (!extensions) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Cannot read GLX extensions");
    return;
  }

  bool hasCreateContext =
    strstr(extensions, "GLX_ARB_create_context") != nullptr;
  if (!hasCreateContext) {
    mbgl::Log::Error(
      mbgl::Event::OpenGL, "GLX_ARB_create_context not supported"
    );
    return;
  }

  // Choose framebuffer configuration
  static const int visualAttribs[] = {
    GLX_X_RENDERABLE,
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
    None
  };

  int fbcount;
  GLXFBConfig *fbConfigs =
    glXChooseFBConfig(display, DefaultScreen(display), visualAttribs, &fbcount);
  if (!fbConfigs || fbcount == 0) {
    mbgl::Log::Error(
      mbgl::Event::OpenGL, "Failed to choose framebuffer config"
    );
    return;
  }

  fbConfig = fbConfigs[0];
  XFree(fbConfigs);

  // Get the GLX_ARB_create_context function
  PFNGLXCREATECONTEXTATTRIBSARBPROC glXCreateContextAttribsARB =
    (PFNGLXCREATECONTEXTATTRIBSARBPROC)glXGetProcAddressARB(
      (const GLubyte *)"glXCreateContextAttribsARB"
    );

  if (!glXCreateContextAttribsARB) {
    mbgl::Log::Error(
      mbgl::Event::OpenGL, "glXCreateContextAttribsARB not found"
    );
    return;
  }

  // Create OpenGL 3.0 compatibility context (matching WGL implementation)
  int contextAttribs[] = {
    GLX_CONTEXT_MAJOR_VERSION_ARB,
    3,
    GLX_CONTEXT_MINOR_VERSION_ARB,
    0,
    GLX_CONTEXT_PROFILE_MASK_ARB,
    GLX_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB,
    0
  };

  context = glXCreateContextAttribsARB(
    display, fbConfig, nullptr, True, contextAttribs
  );
  if (!context) {
    mbgl::Log::Warning(
      mbgl::Event::OpenGL,
      "Failed to create OpenGL 3.0 compatibility context, "
      "trying without profile"
    );

    // Try without profile specification (some drivers don't support the profile
    // mask)
    int fallbackAttribs[] = {
      GLX_CONTEXT_MAJOR_VERSION_ARB, 3, GLX_CONTEXT_MINOR_VERSION_ARB, 0, 0
    };

    context = glXCreateContextAttribsARB(
      display, fbConfig, nullptr, True, fallbackAttribs
    );

    if (!context) {
      mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to create OpenGL context");
      return;
    }
  }

  // Make context current to verify it works
  if (!glXMakeCurrent(display, window, context)) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to make GLX context current");
    glXDestroyContext(display, context);
    context = nullptr;
    return;
  }

  // Get OpenGL version info
  const GLubyte *renderer = glGetString(GL_RENDERER);
  const GLubyte *version = glGetString(GL_VERSION);
}

void GLXContextStrategy::destroy() {
  if (display && context) {
    glXMakeCurrent(display, None, nullptr);
    glXDestroyContext(display, context);
    context = nullptr;
  }
  display = nullptr;
  window = 0;
}

void GLXContextStrategy::makeCurrent() {
  if (display && window && context) {
    glXMakeCurrent(display, window, context);
  }
}

void GLXContextStrategy::releaseCurrent() {
  if (display) {
    glXMakeCurrent(display, None, nullptr);
  }
}

void GLXContextStrategy::swapBuffers() {
  if (display && window) {
    glXSwapBuffers(display, window);
  }
}

void *GLXContextStrategy::getProcAddress(const char *name) {
  return (void *)glXGetProcAddressARB((const GLubyte *)name);
}

}  // namespace maplibre_jni

#endif
