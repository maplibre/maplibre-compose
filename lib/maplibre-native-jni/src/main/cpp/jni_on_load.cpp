#include <jni.h>
#include "java_classes.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  JNIEnv *env;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_21) != JNI_OK) {
    return JNI_ERR;
  }
  java_classes::get<MapLibreMap_class>().register_methods(env);
  return JNI_VERSION_21;
}
