#include <mbgl/util/projection.hpp>

#include <jni.h>

#include <Projection_class.h>

auto JNICALL Projection_class::getMetersPerPixelAtLatitude(
  JNIEnv* env, jclass /* unused */, jdouble lat, jdouble zoom
) -> jdouble {
  try {
    return mbgl::Projection::getMetersPerPixelAtLatitude(lat, zoom);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0.0;
  }
}
