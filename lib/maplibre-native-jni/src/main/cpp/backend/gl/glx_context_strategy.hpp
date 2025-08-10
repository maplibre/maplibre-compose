#pragma once

#ifdef USE_GLX_BACKEND

#include <GL/glx.h>
#include <X11/Xlib.h>
#include "gl_context_strategy.hpp"

namespace maplibre_jni {

class GLXContextStrategy : public GLContextStrategy {
 public:
  GLXContextStrategy() = default;
  ~GLXContextStrategy() override;

  // Context lifecycle
  void create(JNIEnv *env, jobject canvas) override;
  void destroy() override;

  // Context management
  void makeCurrent() override;
  void releaseCurrent() override;
  void swapBuffers() override;

  // GL function loading
  void *getProcAddress(const char *name) override;

 private:
  Display *display = nullptr;
  Window window = 0;
  GLXContext context = nullptr;
  GLXFBConfig fbConfig = nullptr;
};

}  // namespace maplibre_jni

#endif
