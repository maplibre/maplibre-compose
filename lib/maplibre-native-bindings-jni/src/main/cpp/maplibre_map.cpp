#include <mbgl/map/map.hpp>
#include <mbgl/map/map_options.hpp>
#include <mbgl/storage/file_source.hpp>
#include <mbgl/storage/file_source_manager.hpp>
#include <mbgl/storage/resource_options.hpp>
#include <mbgl/style/style.hpp>
#include <mbgl/util/client_options.hpp>

#include <jni.h>
#include <smjni/java_exception.h>

#include <MapLibreMap_class.h>

#include "conversions.hpp"
#include "java_classes.hpp"
#include "jni_map_observer.hpp"

#pragma mark - Helpers

struct MapWrapper {
  std::unique_ptr<mbgl::Map> map;
  std::unique_ptr<maplibre_jni::JniMapObserver> observer;

  MapWrapper(mbgl::Map* map, maplibre_jni::JniMapObserver* observer)
      : map(map), observer(observer) {}
};

template <typename Func>
auto withMapWrapper(JNIEnv* env, jMapLibreMap map, Func&& func)
  -> decltype(func(std::declval<MapWrapper*>())) {
  using ReturnType = decltype(func(std::declval<MapWrapper*>()));
  try {
    auto ptr =
      java_classes::get<MapLibreMap_class>().getNativePointer(env, map);
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    auto* wrapper = reinterpret_cast<MapWrapper*>(ptr);
    return std::forward<Func>(func)(wrapper);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    if constexpr (!std::is_void_v<ReturnType>) return ReturnType{};
  }
}

#pragma mark - Rendering

// TODO: wrap renderStill
// using StillImageCallback = std::function<void(std::exception_ptr)>;
// void renderStill(StillImageCallback);
// void renderStill(const CameraOptions&, MapDebugOptions, StillImageCallback);

void JNICALL MapLibreMap_class::triggerRepaint(JNIEnv* env, jMapLibreMap map) {
  withMapWrapper(env, map, [](auto wrapper) {
    wrapper->map->triggerRepaint();
  });
}

#pragma mark - Style

// TODO: wrap get/set style
// style::Style& getStyle();
// const style::Style& getStyle() const;
// void setStyle(std::unique_ptr<style::Style>);

void JNICALL
MapLibreMap_class::loadStyleURL(JNIEnv* env, jMapLibreMap map, jstring url) {
  withMapWrapper(env, map, [env, url](auto wrapper) {
    wrapper->map->getStyle().loadURL(smjni::java_string_to_cpp(env, url));
  });
}

void JNICALL
MapLibreMap_class::loadStyleJSON(JNIEnv* env, jMapLibreMap map, jstring json) {
  withMapWrapper(env, map, [env, json](auto wrapper) {
    wrapper->map->getStyle().loadJSON(smjni::java_string_to_cpp(env, json));
  });
}

#pragma mark - Transitions

void JNICALL
MapLibreMap_class::cancelTransitions(JNIEnv* env, jMapLibreMap map) {
  withMapWrapper(env, map, [](auto wrapper) {
    wrapper->map->cancelTransitions();
  });
}

void JNICALL MapLibreMap_class::setGestureInProgressNative(
  JNIEnv* env, jMapLibreMap map, jboolean inProgress
) {
  withMapWrapper(env, map, [inProgress](auto wrapper) {
    wrapper->map->setGestureInProgress(inProgress != JNI_FALSE);
  });
}

auto JNICALL
MapLibreMap_class::isGestureInProgressNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isGestureInProgress());
  });
}

auto JNICALL MapLibreMap_class::isRotatingNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isRotating());
  });
}

auto JNICALL MapLibreMap_class::isScalingNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isScaling());
  });
}

auto JNICALL MapLibreMap_class::isPanningNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isPanning());
  });
}

#pragma mark - Camera

auto JNICALL MapLibreMap_class::getCameraOptions(JNIEnv* env, jMapLibreMap map)
  -> jCameraOptions {
  return withMapWrapper(env, map, [env](auto wrapper) {
    auto opts = wrapper->map->getCameraOptions();
    return maplibre_jni::convertCameraOptions(env, opts);
  });
}

void JNICALL MapLibreMap_class::jumpTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions
) {
  withMapWrapper(env, map, [env, cameraOptions](auto wrapper) {
    auto opts = maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->jumpTo(opts);
  });
}

void JNICALL MapLibreMap_class::easeTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions, jint duration
) {
  withMapWrapper(env, map, [env, cameraOptions, duration](auto wrapper) {
    auto opts = maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->easeTo(
      opts, mbgl::AnimationOptions{
              static_cast<mbgl::Duration>(std::chrono::milliseconds(duration))
            }
    );
  });
}

void JNICALL MapLibreMap_class::flyTo(
  JNIEnv* env, jMapLibreMap map, jCameraOptions cameraOptions, jint duration
) {
  withMapWrapper(env, map, [env, cameraOptions, duration](auto wrapper) {
    auto opts = maplibre_jni::convertCameraOptions(env, cameraOptions);
    wrapper->map->flyTo(
      opts, mbgl::AnimationOptions{
              static_cast<mbgl::Duration>(std::chrono::milliseconds(duration))
            }
    );
  });
}

void JNICALL MapLibreMap_class::moveBy(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate screenCoordinate
) {
  withMapWrapper(env, map, [env, screenCoordinate](auto wrapper) {
    auto coord = maplibre_jni::convertScreenCoordinate(env, screenCoordinate);
    wrapper->map->moveBy(coord);
  });
}

void JNICALL MapLibreMap_class::scaleBy(
  JNIEnv* env, jMapLibreMap map, jdouble scale, jScreenCoordinate anchor
) {
  withMapWrapper(env, map, [env, scale, anchor](auto wrapper) {
    auto anchorCoord = maplibre_jni::convertScreenCoordinate(env, anchor);
    wrapper->map->scaleBy(scale, anchorCoord);
  });
}

void JNICALL
MapLibreMap_class::pitchBy(JNIEnv* env, jMapLibreMap map, jdouble pitch) {
  withMapWrapper(env, map, [pitch](auto wrapper) {
    wrapper->map->pitchBy(pitch);
  });
}

void JNICALL MapLibreMap_class::rotateBy(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate first,
  jScreenCoordinate second
) {
  withMapWrapper(env, map, [env, first, second](auto wrapper) {
    auto firstCoord = maplibre_jni::convertScreenCoordinate(env, first);
    auto secondCoord = maplibre_jni::convertScreenCoordinate(env, second);
    wrapper->map->rotateBy(firstCoord, secondCoord);
  });
}

auto JNICALL MapLibreMap_class::cameraForLatLngBounds(
  JNIEnv* env, jMapLibreMap map, jLatLngBounds bounds, jEdgeInsets padding,
  jDouble bearing, jDouble pitch
) -> jCameraOptions {
  return withMapWrapper(
    env, map, [env, bounds, padding, bearing, pitch](auto wrapper) {
      auto cppBounds = maplibre_jni::convertLatLngBounds(env, bounds);
      auto cppPadding = maplibre_jni::convertEdgeInsets(env, padding);

      std::optional<double> cppBearing = std::nullopt;
      if (bearing != nullptr) {
        cppBearing =
          java_classes::get<Double_class>().doubleValue(env, bearing);
      }

      std::optional<double> cppPitch = std::nullopt;
      if (pitch != nullptr) {
        cppPitch = java_classes::get<Double_class>().doubleValue(env, pitch);
      }

      auto opts = wrapper->map->cameraForLatLngBounds(
        cppBounds, cppPadding, cppBearing, cppPitch
      );
      return maplibre_jni::convertCameraOptions(env, opts);
    }
  );
}

auto JNICALL MapLibreMap_class::latLngBoundsForCamera(
  JNIEnv* env, jMapLibreMap map, jCameraOptions camera
) -> jLatLngBounds {
  return withMapWrapper(env, map, [env, camera](auto wrapper) {
    auto cppCamera = maplibre_jni::convertCameraOptions(env, camera);
    auto bounds = wrapper->map->latLngBoundsForCamera(cppCamera);
    return maplibre_jni::convertLatLngBounds(env, bounds);
  });
}

auto JNICALL MapLibreMap_class::latLngBoundsForCameraUnwrapped(
  JNIEnv* env, jMapLibreMap map, jCameraOptions camera
) -> jLatLngBounds {
  return withMapWrapper(env, map, [env, camera](auto wrapper) {
    auto cppCamera = maplibre_jni::convertCameraOptions(env, camera);
    auto bounds = wrapper->map->latLngBoundsForCameraUnwrapped(cppCamera);
    return maplibre_jni::convertLatLngBounds(env, bounds);
  });
}

// TODO: wrap remaining camera methods
// CameraOptions cameraForLatLngs(const std::vector<LatLng>&,
// const EdgeInsets&,
// const std::optional<double>& bearing = std::nullopt,
// const std::optional<double>& pitch = std::nullopt) const;
// CameraOptions cameraForGeometry(const Geometry<double>&,
// const EdgeInsets&,
// const std::optional<double>& bearing = std::nullopt,
// const std::optional<double>& pitch = std::nullopt) const;

#pragma mark - Bounds

// TODO: wrap bounds
// void setBounds(const BoundOptions& options);
// BoundOptions getBounds() const;

#pragma mark - Map Options

void JNICALL MapLibreMap_class::setNorthOrientationNative(
  JNIEnv* env, jMapLibreMap map, jNorthOrientation value
) {
  withMapWrapper(env, map, [env, value](auto wrapper) {
    jint nativeValue =
      java_classes::get<NorthOrientation_class>().getNativeValue(env, value);
    wrapper->map->setNorthOrientation(
      static_cast<mbgl::NorthOrientation>(nativeValue)
    );
  });
}

void JNICALL MapLibreMap_class::setConstrainModeNative(
  JNIEnv* env, jMapLibreMap map, jConstrainMode value
) {
  withMapWrapper(env, map, [env, value](auto wrapper) {
    jint nativeValue =
      java_classes::get<ConstrainMode_class>().getNativeValue(env, value);
    wrapper->map->setConstrainMode(
      static_cast<mbgl::ConstrainMode>(nativeValue)
    );
  });
}

void JNICALL MapLibreMap_class::setViewportModeNative(
  JNIEnv* env, jMapLibreMap map, jViewportMode value
) {
  withMapWrapper(env, map, [env, value](auto wrapper) {
    jint nativeValue =
      java_classes::get<ViewportMode_class>().getNativeValue(env, value);
    wrapper->map->setViewportMode(static_cast<mbgl::ViewportMode>(nativeValue));
  });
}

void JNICALL
MapLibreMap_class::setSize(JNIEnv* env, jMapLibreMap map, jSize size) {
  withMapWrapper(env, map, [env, size](auto wrapper) {
    auto cSize = maplibre_jni::convertSize(env, size);
    if (cSize.width > 0 && cSize.height > 0) wrapper->map->setSize(cSize);
  });
}

auto JNICALL
MapLibreMap_class::getMapOptionsNative(JNIEnv* env, jMapLibreMap map)
  -> jMapOptions {
  return withMapWrapper(env, map, [env](auto wrapper) {
    const mbgl::MapOptions opts = wrapper->map->getMapOptions();
    return maplibre_jni::convertMapOptions(env, opts);
  });
}

#pragma mark - Projection Mode

// TODO: wrap projection mode
// void setProjectionMode(const ProjectionMode&);
// ProjectionMode getProjectionMode() const;
#pragma mark - Projection

auto JNICALL
MapLibreMap_class::pixelForLatLng(JNIEnv* env, jMapLibreMap map, jLatLng latLng)
  -> jScreenCoordinate {
  return withMapWrapper(env, map, [env, latLng](auto wrapper) {
    auto cLatLng = maplibre_jni::convertLatLng(env, latLng);
    return maplibre_jni::convertScreenCoordinate(
      env, wrapper->map->pixelForLatLng(cLatLng)
    );
  });
}

auto JNICALL MapLibreMap_class::latLngForPixel(
  JNIEnv* env, jMapLibreMap map, jScreenCoordinate pixel
) -> jLatLng {
  return withMapWrapper(env, map, [env, pixel](auto wrapper) {
    auto cPixel = maplibre_jni::convertScreenCoordinate(env, pixel);
    return maplibre_jni::convertLatLng(
      env, wrapper->map->latLngForPixel(cPixel)
    );
  });
}

// TODO: wrap pixelsForLatLngs
// std::vector<ScreenCoordinate> pixelsForLatLngs(const std::vector<LatLng>&)
// const; std::vector<LatLng> latLngsForPixels(const
// std::vector<ScreenCoordinate>&) const;

#pragma mark - Transform

// TODO: wrap getTransfromState
// TransformState getTransfromState() const;

#pragma mark - Annotations

// TODO: wrap addAnnotationImage
// void addAnnotationImage(std::unique_ptr<style::Image>);
// void removeAnnotationImage(const std::string&);
// double getTopOffsetPixelsForAnnotationImage(const std::string&);
// AnnotationID addAnnotation(const Annotation&);
// void updateAnnotation(AnnotationID, const Annotation&);
// void removeAnnotation(AnnotationID);

#pragma mark - Tile prefetching

// TODO: wrap prefetch zoom delta
// void setPrefetchZoomDelta(uint8_t delta);
// uint8_t getPrefetchZoomDelta() const;

#pragma mark - Debug

void JNICALL MapLibreMap_class::setDebugNative(
  JNIEnv* env, jMapLibreMap map, jint debugOptions
) {
  withMapWrapper(env, map, [debugOptions](auto wrapper) {
    wrapper->map->setDebug(static_cast<mbgl::MapDebugOptions>(debugOptions));
  });
}

auto JNICALL MapLibreMap_class::getDebugNative(JNIEnv* env, jMapLibreMap map)
  -> jint {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jint>(wrapper->map->getDebug());
  });
}

auto JNICALL MapLibreMap_class::isRenderingStatsViewEnabledNative(
  JNIEnv* env, jMapLibreMap map
) -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isRenderingStatsViewEnabled());
  });
}

void JNICALL MapLibreMap_class::enableRenderingStatsViewNative(
  JNIEnv* env, jMapLibreMap map, jboolean enabled
) {
  withMapWrapper(env, map, [enabled](auto wrapper) {
    wrapper->map->enableRenderingStatsView(enabled != JNI_FALSE);
  });
}

auto JNICALL
MapLibreMap_class::isFullyLoadedNative(JNIEnv* env, jMapLibreMap map)
  -> jboolean {
  return withMapWrapper(env, map, [](auto wrapper) {
    return static_cast<jboolean>(wrapper->map->isFullyLoaded());
  });
}

// TODO: wrap dump debug logs
// void dumpDebugLogs() const;

#pragma mark - Free Camera

// TODO: wrap free camera
// void setFreeCameraOptions(const FreeCameraOptions& camera);
// FreeCameraOptions getFreeCameraOptions() const;

#pragma mark - Tile LOD controls

void JNICALL MapLibreMap_class::setTileLodMinRadiusNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  withMapWrapper(env, map, [value](auto wrapper) {
    wrapper->map->setTileLodMinRadius(value);
  });
}

auto JNICALL
MapLibreMap_class::getTileLodMinRadiusNative(JNIEnv* env, jMapLibreMap map)
  -> jdouble {
  return withMapWrapper(env, map, [](auto wrapper) {
    return wrapper->map->getTileLodMinRadius();
  });
}

void JNICALL MapLibreMap_class::setTileLodScaleNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  withMapWrapper(env, map, [value](auto wrapper) {
    wrapper->map->setTileLodScale(value);
  });
}

auto JNICALL
MapLibreMap_class::getTileLodScaleNative(JNIEnv* env, jMapLibreMap map)
  -> jdouble {
  return withMapWrapper(env, map, [](auto wrapper) {
    return wrapper->map->getTileLodScale();
  });
}

void JNICALL MapLibreMap_class::setTileLodPitchThresholdNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  withMapWrapper(env, map, [value](auto wrapper) {
    wrapper->map->setTileLodPitchThreshold(value);
  });
}

auto JNICALL
MapLibreMap_class::getTileLodPitchThresholdNative(JNIEnv* env, jMapLibreMap map)
  -> jdouble {
  return withMapWrapper(env, map, [](auto wrapper) {
    return wrapper->map->getTileLodPitchThreshold();
  });
}

void JNICALL MapLibreMap_class::setTileLodZoomShiftNative(
  JNIEnv* env, jMapLibreMap map, jdouble value
) {
  withMapWrapper(env, map, [value](auto wrapper) {
    wrapper->map->setTileLodZoomShift(value);
  });
}

auto JNICALL
MapLibreMap_class::getTileLodZoomShiftNative(JNIEnv* env, jMapLibreMap map)
  -> jdouble {
  return withMapWrapper(env, map, [](auto wrapper) {
    return wrapper->map->getTileLodZoomShift();
  });
}

#pragma mark - Other

// TODO: wrap these
// ClientOptions getClientOptions() const;
// const std::unique_ptr<util::ActionJournal>& getActionJournal();

#pragma mark - Allocation

auto JNICALL MapLibreMap_class::nativeInit(
  JNIEnv* env, jclass /*unused*/, jlong frontendPointer,
  jMapObserver observerObj, jMapOptions optionsObj,
  jResourceOptions resourceOptionsObj, jClientOptions clientOptionsObj
) -> jlong {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    auto* renderer = reinterpret_cast<mbgl::RendererFrontend*>(frontendPointer);
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

    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    return reinterpret_cast<jlong>(
      new MapWrapper(map.release(), observer.release())
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0;
  }
}

void JNICALL
MapLibreMap_class::nativeDestroy(JNIEnv* env, jclass /*unused*/, jlong ptr) {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast,cppcoreguidelines-owning-memory)
    delete reinterpret_cast<MapWrapper*>(ptr);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}
