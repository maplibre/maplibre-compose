#include <mbgl/map/map.hpp>
#include <mbgl/map/map_options.hpp>
#include <mbgl/storage/file_source.hpp>
#include <mbgl/storage/file_source_manager.hpp>
#include <mbgl/storage/resource_options.hpp>
#include <mbgl/style/style.hpp>
#include <mbgl/util/client_options.hpp>

#include <jni.h>
#include <jni_md.h>
#include <smjni/java_exception.h>

#include <MapLibreMap_class.h>

#include "conversions.hpp"
#include "java_classes.hpp"
#include "jni_map_observer.hpp"

struct MapWrapper {
  std::unique_ptr<mbgl::Map> map;
  std::unique_ptr<maplibre_jni::JniMapObserver> observer;

  MapWrapper(mbgl::Map* m, maplibre_jni::JniMapObserver* o)
      : map(m), observer(o) {}
};

MapWrapper* getWrapper(JNIEnv* env, jMapLibreMap map) {
  auto ptr = java_classes::get<MapLibreMap_class>().getNativePointer(env, map);
  return reinterpret_cast<MapWrapper*>(ptr);
}

// Map Options continued
void JNICALL
MapLibreMap_class::setSize(JNIEnv* env, jMapLibreMap map, jSize size) {
  try {
    auto* wrapper = getWrapper(env, map);
    auto cSize = maplibre_jni::convertSize(env, size);
    if (cSize.width > 0 && cSize.height > 0) wrapper->map->setSize(cSize);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::triggerRepaint(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->triggerRepaint();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL
MapLibreMap_class::loadStyleURL(JNIEnv* env, jMapLibreMap map, jstring url) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->getStyle().loadURL(smjni::java_string_to_cpp(env, url));
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL
MapLibreMap_class::loadStyleJSON(JNIEnv* env, jMapLibreMap map, jstring json) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->getStyle().loadJSON(smjni::java_string_to_cpp(env, json));
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL
MapLibreMap_class::cancelTransitions(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->cancelTransitions();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

// Camera
jCameraOptions JNICALL
MapLibreMap_class::getCameraOptions(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::CameraOptions c = wrapper->map->getCameraOptions();
    return maplibre_jni::convertCameraOptions(env, c);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return nullptr;
  }
}

void JNICALL MapLibreMap_class::jumpTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions
) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::CameraOptions options =
      maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->jumpTo(options);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::easeTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions, jint duration
) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::CameraOptions options =
      maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->easeTo(
      options, mbgl::AnimationOptions{static_cast<mbgl::Duration>(
                 std::chrono::milliseconds(duration)
               )}
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::flyTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions, jint duration
) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::CameraOptions options =
      maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->flyTo(
      options, mbgl::AnimationOptions{static_cast<mbgl::Duration>(
                 std::chrono::milliseconds(duration)
               )}
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::moveBy(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate screenCoordinate
) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::ScreenCoordinate coord =
      maplibre_jni::convertScreenCoordinate(env, screenCoordinate);
    wrapper->map->moveBy(coord);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::scaleBy(
  JNIEnv* env, jMapLibreMap map, jdouble scale, jScreenCoordinate anchor
) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::ScreenCoordinate anchorCoord =
      maplibre_jni::convertScreenCoordinate(env, anchor);
    wrapper->map->scaleBy(scale, anchorCoord);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::rotateBy(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate first,
  jScreenCoordinate second
) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::ScreenCoordinate firstCoord =
      maplibre_jni::convertScreenCoordinate(env, first);
    mbgl::ScreenCoordinate secondCoord =
      maplibre_jni::convertScreenCoordinate(env, second);
    wrapper->map->rotateBy(firstCoord, secondCoord);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL
MapLibreMap_class::pitchBy(JNIEnv* env, jMapLibreMap map, jdouble pitch) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->pitchBy(pitch);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::setGestureInProgressNative(
  JNIEnv* env, jMapLibreMap map, jboolean inProgress
) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->setGestureInProgress(inProgress);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

jboolean JNICALL
MapLibreMap_class::isGestureInProgressNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->isGestureInProgress();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return false;
  }
}

jboolean JNICALL
MapLibreMap_class::isRotatingNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->isRotating();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return false;
  }
}

jboolean JNICALL
MapLibreMap_class::isScalingNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->isScaling();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return false;
  }
}

jboolean JNICALL
MapLibreMap_class::isPanningNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->isPanning();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return false;
  }
}

// Map Options
jMapOptions JNICALL
MapLibreMap_class::getMapOptionsNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    const mbgl::MapOptions opts = wrapper->map->getMapOptions();
    return maplibre_jni::convertMapOptions(env, opts);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return nullptr;
  }
}

void JNICALL MapLibreMap_class::setNorthOrientationNative(
  JNIEnv* env, jMapLibreMap map, jNorthOrientation value
) {
  try {
    auto* wrapper = getWrapper(env, map);
    jint nativeValue =
      java_classes::get<NorthOrientation_class>().getNativeValue(env, value);
    wrapper->map->setNorthOrientation(
      static_cast<mbgl::NorthOrientation>(nativeValue)
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::setConstrainModeNative(
  JNIEnv* env, jMapLibreMap map, jConstrainMode value
) {
  try {
    auto* wrapper = getWrapper(env, map);
    jint nativeValue =
      java_classes::get<ConstrainMode_class>().getNativeValue(env, value);
    wrapper->map->setConstrainMode(
      static_cast<mbgl::ConstrainMode>(nativeValue)
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL MapLibreMap_class::setViewportModeNative(
  JNIEnv* env, jMapLibreMap map, jViewportMode value
) {
  try {
    auto* wrapper = getWrapper(env, map);
    jint nativeValue =
      java_classes::get<ViewportMode_class>().getNativeValue(env, value);
    wrapper->map->setViewportMode(static_cast<mbgl::ViewportMode>(nativeValue));
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

jScreenCoordinate JNICALL MapLibreMap_class::pixelForLatLng(
  JNIEnv* env, jMapLibreMap map, jLatLng latLng
) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::LatLng cLatLng = maplibre_jni::convertLatLng(env, latLng);
    return maplibre_jni::convertScreenCoordinate(
      env, wrapper->map->pixelForLatLng(cLatLng)
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return nullptr;
  }
}

jLatLng JNICALL MapLibreMap_class::latLngForPixel(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate pixel
) {
  try {
    auto* wrapper = getWrapper(env, map);
    mbgl::ScreenCoordinate cPixel =
      maplibre_jni::convertScreenCoordinate(env, pixel);
    return maplibre_jni::convertLatLng(
      env, wrapper->map->latLngForPixel(cPixel)
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return nullptr;
  }
}

void JNICALL MapLibreMap_class::setDebugNative(
  JNIEnv* env, jMapLibreMap map, jint debugOptions
) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->setDebug(static_cast<mbgl::MapDebugOptions>(debugOptions));
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

jint JNICALL MapLibreMap_class::getDebugNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return static_cast<jint>(wrapper->map->getDebug());
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0;
  }
}

// Debug / Status
void JNICALL MapLibreMap_class::enableRenderingStatsViewNative(
  JNIEnv* env, jMapLibreMap map, jboolean enabled
) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->enableRenderingStatsView(enabled);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

jboolean JNICALL MapLibreMap_class::isRenderingStatsViewEnabledNative(
  JNIEnv* env, jMapLibreMap map
) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->isRenderingStatsViewEnabled();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return false;
  }
}

jboolean JNICALL
MapLibreMap_class::isFullyLoadedNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->isFullyLoaded();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return false;
  }
}

// Tile LOD controls
jdouble JNICALL
MapLibreMap_class::getTileLodMinRadiusNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->getTileLodMinRadius();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0.0;
  }
}

void JNICALL MapLibreMap_class::setTileLodMinRadiusNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->setTileLodMinRadius(value);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

jdouble JNICALL
MapLibreMap_class::getTileLodScaleNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->getTileLodScale();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 1.0;
  }
}

void JNICALL MapLibreMap_class::setTileLodScaleNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->setTileLodScale(value);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

jdouble JNICALL MapLibreMap_class::getTileLodPitchThresholdNative(
  JNIEnv* env, jMapLibreMap map
) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->getTileLodPitchThreshold();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0.0;
  }
}

void JNICALL MapLibreMap_class::setTileLodPitchThresholdNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->setTileLodPitchThreshold(value);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

jdouble JNICALL
MapLibreMap_class::getTileLodZoomShiftNative(JNIEnv* env, jMapLibreMap map) {
  try {
    auto* wrapper = getWrapper(env, map);
    return wrapper->map->getTileLodZoomShift();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0.0;
  }
}

void JNICALL MapLibreMap_class::setTileLodZoomShiftNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  try {
    auto* wrapper = getWrapper(env, map);
    wrapper->map->setTileLodZoomShift(value);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

jlong JNICALL MapLibreMap_class::nativeInit(
  JNIEnv* env, jclass, jlong rendererPtr, jMapObserver observerObj,
  jMapOptions optionsObj, jResourceOptions resourceOptionsObj,
  jClientOptions clientOptionsObj
) {
  try {
    auto renderer = reinterpret_cast<mbgl::RendererFrontend*>(rendererPtr);
    auto observer = std::make_unique<maplibre_jni::JniMapObserver>(observerObj);
    mbgl::MapOptions mapOptions =
      maplibre_jni::convertMapOptions(env, optionsObj);
    mbgl::ResourceOptions resourceOptions =
      maplibre_jni::convertResourceOptions(env, resourceOptionsObj);
    mbgl::ClientOptions clientOptions =
      maplibre_jni::convertClientOptions(env, clientOptionsObj);
    auto map = std::make_unique<mbgl::Map>(
      *renderer, *observer, mapOptions, resourceOptions, clientOptions
    );

    // Get network file source for HTTP downloads
    std::shared_ptr<mbgl::FileSource> networkFileSource =
      mbgl::FileSourceManager::get()->getFileSource(
        mbgl::FileSourceType::Network, resourceOptions, clientOptions
      );

    // Get resource loader for request management
    std::shared_ptr<mbgl::FileSource> resourceLoader =
      mbgl::FileSourceManager::get()->getFileSource(
        mbgl::FileSourceType::ResourceLoader, resourceOptions, clientOptions
      );

    // Get database file source for caching
    std::shared_ptr<mbgl::FileSource> databaseFileSource =
      mbgl::FileSourceManager::get()->getFileSource(
        mbgl::FileSourceType::Database, resourceOptions, clientOptions
      );

    auto* wrapper = new MapWrapper(map.release(), observer.release());
    return reinterpret_cast<jlong>(wrapper);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0;
  }
}

void JNICALL MapLibreMap_class::nativeDestroy(JNIEnv* env, jclass, jlong ptr) {
  try {
    auto* wrapper = reinterpret_cast<MapWrapper*>(ptr);
    delete wrapper;
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}
