#pragma once

#include <mbgl/map/map_observer.hpp>

#include "jni_map_observer.hpp"

#include "java_classes.hpp"

namespace maplibre_jni {
JniMapObserver::JniMapObserver(
  smjni::auto_java_ref<jMapObserver> kotlinObserver
)
    : observer(kotlinObserver) {}

JniMapObserver::~JniMapObserver() {}

void JniMapObserver::onCameraWillChange(
  mbgl::MapObserver::CameraChangeMode mode
) {
  JNIEnv* env = smjni::jni_provider::get_jni();
  auto jMode =
    java_classes::get<CameraChangeMode_class>().fromNativeValue(env, (int)mode);
  java_classes::get<MapObserver_class>().onCameraWillChange(
    env, observer, jMode
  );
}

void JniMapObserver::onCameraIsChanging() {
  JNIEnv* env = smjni::jni_provider::get_jni();
  java_classes::get<MapObserver_class>().onCameraIsChanging(env, observer);
}

void JniMapObserver::onCameraDidChange(
  mbgl::MapObserver::CameraChangeMode mode
) {
  JNIEnv* env = smjni::jni_provider::get_jni();
  auto jMode =
    java_classes::get<CameraChangeMode_class>().fromNativeValue(env, (int)mode);
  java_classes::get<MapObserver_class>().onCameraDidChange(
    env, observer, jMode
  );
}

void JniMapObserver::onWillStartLoadingMap() {
  JNIEnv* env = smjni::jni_provider::get_jni();
  java_classes::get<MapObserver_class>().onWillStartLoadingMap(env, observer);
}

void JniMapObserver::onDidFinishLoadingMap() {
  JNIEnv* env = smjni::jni_provider::get_jni();
  java_classes::get<MapObserver_class>().onDidFinishLoadingMap(env, observer);
}

void JniMapObserver::onDidFailLoadingMap(
  mbgl::MapLoadError error, const std::string& message
) {
  JNIEnv* env = smjni::jni_provider::get_jni();
  auto jError =
    java_classes::get<MapLoadError_class>().fromNativeValue(env, (int)error);
  auto jMessage = smjni::java_string_create(env, message);
  java_classes::get<MapObserver_class>().onDidFailLoadingMap(
    env, observer, jError, jMessage
  );
}

void JniMapObserver::onWillStartRenderingFrame() {
  JNIEnv* env = smjni::jni_provider::get_jni();
  java_classes::get<MapObserver_class>().onWillStartRenderingFrame(
    env, observer
  );
}

void JniMapObserver::onDidFinishRenderingFrame(
  const mbgl::MapObserver::RenderFrameStatus& status
) {
  JNIEnv* env = smjni::jni_provider::get_jni();
  auto jMode = java_classes::get<RenderMode_class>().fromNativeValue(
    env, (int)status.mode
  );
  auto jStatus = java_classes::get<RenderFrameStatus_class>().ctor(
    env, jMode, static_cast<jboolean>(status.needsRepaint),
    static_cast<jboolean>(status.placementChanged)
  );
  java_classes::get<MapObserver_class>().onDidFinishRenderingFrame(
    env, observer, jStatus
  );
}

void JniMapObserver::onWillStartRenderingMap() {
  JNIEnv* env = smjni::jni_provider::get_jni();
  java_classes::get<MapObserver_class>().onWillStartRenderingMap(env, observer);
}

void JniMapObserver::onDidFinishRenderingMap(
  mbgl::MapObserver::RenderMode mode
) {
  JNIEnv* env = smjni::jni_provider::get_jni();
  auto jMode =
    java_classes::get<RenderMode_class>().fromNativeValue(env, (int)mode);
  java_classes::get<MapObserver_class>().onDidFinishRenderingMap(
    env, observer, jMode
  );
}

void JniMapObserver::onDidFinishLoadingStyle() {
  JNIEnv* env = smjni::jni_provider::get_jni();
  java_classes::get<MapObserver_class>().onDidFinishLoadingStyle(env, observer);
}

void JniMapObserver::onStyleImageMissing(const std::string& imageId) {
  JNIEnv* env = smjni::jni_provider::get_jni();
  auto jImageId = smjni::java_string_create(env, imageId);
  java_classes::get<MapObserver_class>().onStyleImageMissing(
    env, observer, jImageId
  );
}

void JniMapObserver::onDidBecomeIdle() {
  JNIEnv* env = smjni::jni_provider::get_jni();
  java_classes::get<MapObserver_class>().onDidBecomeIdle(env, observer);
}
}  // namespace maplibre_jni
