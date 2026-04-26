#pragma once

#include <all_classes.h>

#include "smjni/java_method.h"

class JavaDouble_class
    : public smjni::java_runtime::simple_java_class<jDouble> {
 public:
  JavaDouble_class(JNIEnv* env);

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

inline JavaDouble_class::JavaDouble_class(JNIEnv* env)
    : simple_java_class(env),
      m_valueOf(env, *this, "valueOf"),
      m_doubleValue(env, *this, "doubleValue") {}

class JavaCanvas_class
    : public smjni::java_runtime::simple_java_class<jCanvas> {
 public:
  JavaCanvas_class(JNIEnv* env);

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

inline JavaCanvas_class::JavaCanvas_class(JNIEnv* env)
    : simple_java_class(env),
      m_getWidth(env, *this, "getWidth"),
      m_getHeight(env, *this, "getHeight") {}

// java.lang.Double is exposed to JniGen for exact JVM signatures, but its
// generated class registers JDK native methods that this library must not own.
using java_classes = smjni::java_class_table<
  AutoCleanPointer_class, BoundOptions_class, CameraChangeMode_class,
  CameraOptions_class, CanvasRenderer_class, ClientOptions_class,
  ConstrainMode_class, EdgeInsets_class, LatLngBounds_class, LatLng_class,
  MapLibreMap_class, MapLoadError_class, MapMode_class, MapObserver_class,
  MapOptions_class, NorthOrientation_class, Projection_class,
  RenderFrameStatus_class, RenderMode_class, RenderingStats_class,
  ResourceOptions_class, ScreenCoordinate_class, Size_class,
  TileServerOptions_class, TileServerTemplate_class, ViewportMode_class,
  JavaDouble_class, JavaCanvas_class>;
