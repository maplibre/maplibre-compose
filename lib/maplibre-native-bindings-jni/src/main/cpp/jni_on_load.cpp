#include <jni.h>
#include <smjni/jni_provider.h>

#include "java_classes.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  try {
    smjni::jni_provider::init(vm);
    JNIEnv* env = smjni::jni_provider::get_jni();
    smjni::java_runtime::init(env);
    java_classes::init(env);
    return JNI_VERSION_1_6;
  } catch (std::exception& ex) {
    // If we are here there is no way to communicate with
    // Java - something really bad happened.
    // Let's just log and report failure
    fprintf(stderr, "%s\n", ex.what());
  }
  return 0;
}
