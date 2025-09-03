#pragma once

#include <mbgl/map/map_observer.hpp>

#include <smjni/java_ref.h>
#include <type_mapping.h>

namespace maplibre_jni {

// C++ implementation that forwards MapObserver callbacks to a Kotlin
// MapObserver
class JniMapObserver : public mbgl::MapObserver {
 public:
  JniMapObserver(const JniMapObserver&) = delete;
  JniMapObserver(JniMapObserver&&) = delete;
  JniMapObserver& operator=(const JniMapObserver&) = delete;
  JniMapObserver& operator=(JniMapObserver&&) = delete;

  JniMapObserver(smjni::auto_java_ref<jMapObserver> kotlinObserver);
  ~JniMapObserver() override;

  // Camera events
  void onCameraWillChange(mbgl::MapObserver::CameraChangeMode mode) override;
  void onCameraIsChanging() override;
  void onCameraDidChange(mbgl::MapObserver::CameraChangeMode mode) override;

  // Map loading events
  void onWillStartLoadingMap() override;
  void onDidFinishLoadingMap() override;
  void onDidFailLoadingMap(
    mbgl::MapLoadError error, const std::string& message
  ) override;

  // Rendering events
  void onWillStartRenderingFrame() override;
  void onDidFinishRenderingFrame(
    const mbgl::MapObserver::RenderFrameStatus& status
  ) override;
  void onWillStartRenderingMap() override;
  void onDidFinishRenderingMap(mbgl::MapObserver::RenderMode mode) override;

  // Style events
  void onDidFinishLoadingStyle() override;
  void onStyleImageMissing(const std::string& imageId) override;

  // Idle state
  void onDidBecomeIdle() override;

  // TODO: Implement these when we have the necessary type wrappers:
  // - onSourceChanged
  // - onCanRemoveUnusedStyleImage
  // - onRegisterShaders
  // - Shader compilation callbacks
  // - Glyph/Tile/Sprite loading callbacks

 private:
  smjni::global_java_ref<jMapObserver> observer;
};

}  // namespace maplibre_jni
