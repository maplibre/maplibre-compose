#ifdef USE_EGL_BACKEND

#include "egl_context_strategy.hpp"
#include <jawt.h>
#include <jawt_md.h>
#include <mbgl/util/logging.hpp>

#ifdef __linux__
#include <X11/Xlib.h>
#endif

namespace maplibre_jni {

EGLContextStrategy::~EGLContextStrategy() { destroy(); }

void EGLContextStrategy::create(JNIEnv *env, jobject canvas) {
  // Extract platform-specific native handles
  extractNativeHandles(env, canvas, nativeDisplay, nativeWindow);

  if (!nativeWindow) {
    mbgl::Log::Error(
      mbgl::Event::OpenGL, "Failed to extract native window handle"
    );
    return;
  }

  // Initialize EGL - platform-agnostic from here
#ifdef _WIN32
  // On Windows with ANGLE, use EGL_DEFAULT_DISPLAY
  eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
#else
  // On Linux, use the native display
  eglDisplay = eglGetDisplay(static_cast<EGLNativeDisplayType>(nativeDisplay));
#endif

  if (eglDisplay == EGL_NO_DISPLAY) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to get EGL display");
    return;
  }

  EGLint major, minor;
  if (!eglInitialize(eglDisplay, &major, &minor)) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to initialize EGL");
    return;
  }

  if (!eglBindAPI(EGL_OPENGL_ES_API)) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to bind OpenGL ES API");
    return;
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
  if (!eglChooseConfig(eglDisplay, configAttribs, &eglConfig, 1, &numConfigs) ||
      numConfigs == 0) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to choose EGL config");
    return;
  }

  eglSurface = eglCreateWindowSurface(
    eglDisplay, eglConfig, reinterpret_cast<EGLNativeWindowType>(nativeWindow),
    nullptr
  );
  if (eglSurface == EGL_NO_SURFACE) {
    EGLint error = eglGetError();
    mbgl::Log::Error(
      mbgl::Event::OpenGL,
      std::string("Failed to create EGL surface, error: 0x") +
        std::to_string(error)
    );
    return;
  }

  const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};

  eglContext =
    eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, contextAttribs);
  if (eglContext == EGL_NO_CONTEXT) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to create EGL context");
    return;
  }

  if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Failed to make EGL context current");
    return;
  }
}

void EGLContextStrategy::destroy() {
  if (eglDisplay != EGL_NO_DISPLAY) {
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (eglContext != EGL_NO_CONTEXT) {
      eglDestroyContext(eglDisplay, eglContext);
      eglContext = EGL_NO_CONTEXT;
    }

    if (eglSurface != EGL_NO_SURFACE) {
      eglDestroySurface(eglDisplay, eglSurface);
      eglSurface = EGL_NO_SURFACE;
    }

    eglTerminate(eglDisplay);
    eglDisplay = EGL_NO_DISPLAY;
  }
}

void EGLContextStrategy::makeCurrent() {
  if (eglDisplay != EGL_NO_DISPLAY && eglContext != EGL_NO_CONTEXT &&
      eglSurface != EGL_NO_SURFACE) {
    if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      mbgl::Log::Error(
        mbgl::Event::OpenGL, "Failed to make EGL context current"
      );
    }
  }
}

void EGLContextStrategy::releaseCurrent() {
  if (eglDisplay != EGL_NO_DISPLAY) {
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  }
}

void EGLContextStrategy::swapBuffers() {
  if (eglDisplay != EGL_NO_DISPLAY && eglSurface != EGL_NO_SURFACE) {
    eglSwapBuffers(eglDisplay, eglSurface);
  }
}

void *EGLContextStrategy::getProcAddress(const char *name) {
  return reinterpret_cast<void *>(eglGetProcAddress(name));
}

void EGLContextStrategy::extractNativeHandles(
  JNIEnv *env, jobject canvas, void *&nativeDisplay, void *&nativeWindow
) {
  // Get JAWT
  JAWT awt;
  awt.version = JAWT_VERSION_9;

  jboolean result = JAWT_GetAWT(env, &awt);
  if (result == JNI_FALSE) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "JAWT_GetAWT failed");
    return;
  }

  // Get the drawing surface
  JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
  if (!ds) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "GetDrawingSurface returned null");
    return;
  }

  // Lock the drawing surface
  jint lock = ds->Lock(ds);
  if ((lock & JAWT_LOCK_ERROR) != 0) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Error locking drawing surface");
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Get the drawing surface info
  JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
  if (!dsi) {
    mbgl::Log::Error(
      mbgl::Event::OpenGL, "GetDrawingSurfaceInfo returned null"
    );
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Platform-specific native handle extraction
#ifdef __linux__
  // Get the X11 drawing surface info
  JAWT_X11DrawingSurfaceInfo *x11Info =
    (JAWT_X11DrawingSurfaceInfo *)dsi->platformInfo;
  if (!x11Info) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Platform info is null");
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Store native handles for EGL
  nativeDisplay = x11Info->display;
  // X11 drawable is already the correct type (Window/XID)
  nativeWindow = reinterpret_cast<void *>(x11Info->drawable);

#elif _WIN32
  // Get the Windows drawing surface info
  JAWT_Win32DrawingSurfaceInfo *win32Info =
    (JAWT_Win32DrawingSurfaceInfo *)dsi->platformInfo;
  if (!win32Info) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Platform info is null");
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Store native handles for EGL
  hwnd = win32Info->hwnd;
  nativeWindow = hwnd;
  // On Windows with ANGLE, we use EGL_DEFAULT_DISPLAY
  nativeDisplay = EGL_DEFAULT_DISPLAY;

#elif __APPLE__
  // Future macOS support with ANGLE or native EGL
  // JAWT_MacOSXDrawingSurfaceInfo *macInfo = (JAWT_MacOSXDrawingSurfaceInfo
  // *)dsi->platformInfo; if (!macInfo) { ... } nativeDisplay =
  // EGL_DEFAULT_DISPLAY; nativeWindow = macInfo->nsView; // or similar
  mbgl::Log::Error(
    mbgl::Event::OpenGL, "macOS EGL support not yet implemented"
  );
#endif

  // We must unlock the surface immediately after getting the handles
  // Otherwise AWT event processing will be blocked
  ds->FreeDrawingSurfaceInfo(dsi);
  ds->Unlock(ds);
  awt.FreeDrawingSurface(ds);
}

}  // namespace maplibre_jni

#endif
