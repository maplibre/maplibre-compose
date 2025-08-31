#pragma once

#include <all_classes.h>

#include "smjni/java_method.h"
#include "type_mapping.h"

class Double_class : public smjni::java_runtime::simple_java_class<jDouble> {
 public:
  Double_class(JNIEnv* env);

  smjni::local_java_ref<jDouble> valueOf(JNIEnv* env, jdouble value) const {
    return m_valueOf(env, *this, value);
  }

  jdouble doubleValue(
    JNIEnv* env, const smjni::auto_java_ref<jDouble>& self
  ) const {
    return m_doubleValue(env, self);
  }

 private:
  const smjni::java_static_method<jDouble, jDouble, jdouble> m_valueOf;
  const smjni::java_method<jdouble, jDouble> m_doubleValue;
};

inline Double_class::Double_class(JNIEnv* env)
    : simple_java_class(env),
      m_valueOf(env, *this, "valueOf"),
      m_doubleValue(env, *this, "doubleValue") {}

class Canvas_class : public smjni::java_runtime::simple_java_class<jCanvas> {
 public:
  Canvas_class(JNIEnv* env);

  jint getWidth(JNIEnv* env, const smjni::auto_java_ref<jCanvas>& self) const {
    return m_getWidth(env, self);
  }

  jint getHeight(JNIEnv* env, const smjni::auto_java_ref<jCanvas>& self) const {
    return m_getHeight(env, self);
  }

 private:
  const smjni::java_method<jint, jCanvas> m_getWidth;
  const smjni::java_method<jint, jCanvas> m_getHeight;
};

inline Canvas_class::Canvas_class(JNIEnv* env)
    : simple_java_class(env),
      m_getWidth(env, *this, "getWidth"),
      m_getHeight(env, *this, "getHeight") {}

class SwingUtilities_class
    : public smjni::java_runtime::simple_java_class<jSwingUtilities> {
 public:
  SwingUtilities_class(JNIEnv* env);

  void invokeLater(JNIEnv* env, jRunnable doRun) const {
    m_invokeLater(env, *this, doRun);
  }

 private:
  const smjni::java_static_method<void, jSwingUtilities, jRunnable>
    m_invokeLater;
};

inline SwingUtilities_class::SwingUtilities_class(JNIEnv* env)
    : simple_java_class(env), m_invokeLater(env, *this, "invokeLater") {}

typedef smjni::java_class_table<
  JNIGEN_ALL_GENERATED_CLASSES, Double_class, Canvas_class,
  SwingUtilities_class>
  java_classes;
