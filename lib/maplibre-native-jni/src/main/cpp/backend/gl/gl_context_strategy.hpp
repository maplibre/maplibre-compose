#pragma once

#include <jni.h>

namespace maplibre_jni {

// Abstract strategy interface for platform-specific OpenGL context management
class GLContextStrategy {
 public:
  virtual ~GLContextStrategy() = default;

  // Context lifecycle
  virtual void create(JNIEnv *env, jobject canvas) = 0;
  virtual void destroy() = 0;

  // Context management
  virtual void makeCurrent() = 0;
  virtual void releaseCurrent() = 0;
  virtual void swapBuffers() = 0;

  // GL function loading
  virtual void *getProcAddress(const char *name) = 0;
};

}  // namespace maplibre_jni