#pragma once

#include <all_classes.h>

class Double_class : public smjni::java_runtime::simple_java_class<jDouble> {
 public:
  Double_class(JNIEnv *env);

  smjni::local_java_ref<jDouble> valueOf(JNIEnv *env, jdouble value) const {
    return m_valueOf(env, *this, value);
  }

  jdouble doubleValue(
    JNIEnv *env, const smjni::auto_java_ref<jDouble> &self
  ) const {
    return m_doubleValue(env, self);
  }

 private:
  const smjni::java_static_method<jDouble, jDouble, jdouble> m_valueOf;
  const smjni::java_method<jdouble, jDouble> m_doubleValue;
};

inline Double_class::Double_class(JNIEnv *env)
    : simple_java_class(env),
      m_valueOf(env, *this, "valueOf"),
      m_doubleValue(env, *this, "doubleValue") {}

typedef smjni::java_class_table<JNIGEN_ALL_GENERATED_CLASSES, Double_class>
  java_classes;
