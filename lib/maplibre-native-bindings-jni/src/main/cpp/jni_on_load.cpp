#include <print>

#include <smjni/jni_provider.h>

#include "java_classes.hpp"

JNIEXPORT auto JNICALL JNI_OnLoad(JavaVM* javaVM, void* /*unused*/) -> jint {
  try {
    smjni::jni_provider::init(javaVM);
    JNIEnv* env = smjni::jni_provider::get_jni();
    smjni::java_runtime::init(env);
    java_classes::init(env);
    return JNI_VERSION_1_6;
  } catch (std::exception& ex) {
    // If we are here, there is no way to communicate with Java.
    // Let's just log to report failure.
    std::println(stderr, "{}", ex.what());
  }
  return 0;
}
