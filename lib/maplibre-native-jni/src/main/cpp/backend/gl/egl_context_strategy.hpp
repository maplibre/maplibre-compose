#pragma once

#ifdef USE_EGL_BACKEND

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include "gl_context_strategy.hpp"

#ifdef _WIN32
#include <windows.h>
#endif

namespace maplibre_jni {

class EGLContextStrategy : public GLContextStrategy {
 public:
  EGLContextStrategy() = default;
  ~EGLContextStrategy() override;

  void create(JNIEnv *env, jobject canvas) override;
  void destroy() override;

  void makeCurrent() override;
  void releaseCurrent() override;
  void swapBuffers() override;

  void *getProcAddress(const char *name) override;

 private:
  void extractNativeHandles(
    JNIEnv *env, jobject canvas, void *&nativeDisplay, void *&nativeWindow
  );

  void *nativeDisplay = nullptr;
  void *nativeWindow = nullptr;

#ifdef _WIN32
  HWND hwnd = nullptr;
#endif

  // EGL handles
  EGLDisplay eglDisplay = EGL_NO_DISPLAY;
  EGLContext eglContext = EGL_NO_CONTEXT;
  EGLSurface eglSurface = EGL_NO_SURFACE;
  EGLConfig eglConfig = nullptr;
};

}  // namespace maplibre_jni

#endif
