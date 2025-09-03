#pragma once

#include <all_classes.h>

#include "smjni/java_method.h"
#include "type_mapping.h"

class Double_class : public smjni::java_runtime::simple_java_class<jDouble> {
 public:
  Double_class(JNIEnv* env);

  auto valueOf(JNIEnv* env, jdouble value) const
    -> smjni::local_java_ref<jDouble> {
    return m_valueOf(env, *this, value);
  }

  auto doubleValue(JNIEnv* env, const smjni::auto_java_ref<jDouble>& self) const
    -> jdouble {
    return m_doubleValue(env, self);
  }

 private:
  smjni::java_static_method<jDouble, jDouble, jdouble> m_valueOf;
  smjni::java_method<jdouble, jDouble> m_doubleValue;
};

inline Double_class::Double_class(JNIEnv* env)
    : simple_java_class(env),
      m_valueOf(env, *this, "valueOf"),
      m_doubleValue(env, *this, "doubleValue") {}

class Canvas_class : public smjni::java_runtime::simple_java_class<jCanvas> {
 public:
  Canvas_class(JNIEnv* env);

  auto getWidth(JNIEnv* env, const smjni::auto_java_ref<jCanvas>& self) const
    -> jint {
    return m_getWidth(env, self);
  }

  auto getHeight(JNIEnv* env, const smjni::auto_java_ref<jCanvas>& self) const
    -> jint {
    return m_getHeight(env, self);
  }

 private:
  smjni::java_method<jint, jCanvas> m_getWidth;
  smjni::java_method<jint, jCanvas> m_getHeight;
};

inline Canvas_class::Canvas_class(JNIEnv* env)
    : simple_java_class(env),
      m_getWidth(env, *this, "getWidth"),
      m_getHeight(env, *this, "getHeight") {}

using java_classes = smjni::java_class_table<
  JNIGEN_ALL_GENERATED_CLASSES, Double_class, Canvas_class>;
