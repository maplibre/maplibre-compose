#pragma once

#include "jni_map_observer.hpp"
#include <jni.h>
#include <mbgl/map/map_observer.hpp>
#include <CameraChangeMode_class.h>
#include "java_classes.hpp"

namespace maplibre_jni {
JniMapObserver::JniMapObserver(
  JNIEnv *env, smjni::auto_java_ref<jMapObserver> kotlinObserver
)
    : env(env), observer(std::move(kotlinObserver)) {}

JniMapObserver::~JniMapObserver() {}

void JniMapObserver::onCameraWillChange(
  mbgl::MapObserver::CameraChangeMode mode
) {
  auto jMode =
    java_classes::get<CameraChangeMode_class>().fromNativeValue(env, (int)mode);
  java_classes::get<MapObserver_class>().onCameraWillChange(
    env, observer, jMode
  );
}

void JniMapObserver::onCameraIsChanging() {
  java_classes::get<MapObserver_class>().onCameraIsChanging(env, observer);
}

void JniMapObserver::onCameraDidChange(
  mbgl::MapObserver::CameraChangeMode mode
) {
  auto jMode =
    java_classes::get<CameraChangeMode_class>().fromNativeValue(env, (int)mode);
  java_classes::get<MapObserver_class>().onCameraDidChange(
    env, observer, jMode
  );
}

void JniMapObserver::onWillStartLoadingMap() {
  java_classes::get<MapObserver_class>().onWillStartLoadingMap(env, observer);
}

void JniMapObserver::onDidFinishLoadingMap() {
  java_classes::get<MapObserver_class>().onDidFinishLoadingMap(env, observer);
}

void JniMapObserver::onDidFailLoadingMap(
  mbgl::MapLoadError error, const std::string &message
) {
  auto jError =
    java_classes::get<MapLoadError_class>().fromNativeValue(env, (int)error);
  auto jMessage = smjni::java_string_create(env, message);
  java_classes::get<MapObserver_class>().onDidFailLoadingMap(
    env, observer, jError, jMessage
  );
}

void JniMapObserver::onWillStartRenderingFrame() {
  java_classes::get<MapObserver_class>().onWillStartRenderingFrame(
    env, observer
  );
}

void JniMapObserver::onDidFinishRenderingFrame(
  const mbgl::MapObserver::RenderFrameStatus &status
) {
  auto jMode = java_classes::get<RenderMode_class>().fromNativeValue(
    env, (int)status.mode
  );
  auto jStatus = java_classes::get<RenderFrameStatus_class>().ctor(
    env, jMode, status.needsRepaint, status.placementChanged
  );
  java_classes::get<MapObserver_class>().onDidFinishRenderingFrame(
    env, observer, jStatus
  );
}

void JniMapObserver::onWillStartRenderingMap() {
  java_classes::get<MapObserver_class>().onWillStartRenderingMap(env, observer);
}

void JniMapObserver::onDidFinishRenderingMap(
  mbgl::MapObserver::RenderMode mode
) {
  auto jMode =
    java_classes::get<RenderMode_class>().fromNativeValue(env, (int)mode);
  java_classes::get<MapObserver_class>().onDidFinishRenderingMap(
    env, observer, jMode
  );
}

void JniMapObserver::onDidFinishLoadingStyle() {
  java_classes::get<MapObserver_class>().onDidFinishLoadingStyle(env, observer);
}

void JniMapObserver::onStyleImageMissing(const std::string &imageId) {
  auto jImageId = smjni::java_string_create(env, imageId);
  java_classes::get<MapObserver_class>().onStyleImageMissing(
    env, observer, jImageId
  );
}

void JniMapObserver::onDidBecomeIdle() {
  java_classes::get<MapObserver_class>().onDidBecomeIdle(env, observer);
}
}  // namespace maplibre_jni
