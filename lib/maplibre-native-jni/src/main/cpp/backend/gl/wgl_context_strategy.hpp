#pragma once

#ifdef USE_WGL_BACKEND

#include <windows.h>
#include "gl_context_strategy.hpp"

namespace maplibre_jni {

class WGLContextStrategy : public GLContextStrategy {
 public:
  WGLContextStrategy() = default;
  ~WGLContextStrategy() override;

  void create(JNIEnv *env, jobject canvas) override;
  void destroy() override;

  void makeCurrent() override;
  void releaseCurrent() override;
  void swapBuffers() override;

  void *getProcAddress(const char *name) override;

 private:
  HWND hwnd = nullptr;
  HDC hdc = nullptr;
  HGLRC hglrc = nullptr;
};

}  // namespace maplibre_jni

#endif
