package org.maplibre.kmp.native.map

import org.maplibre.kmp.native.camera.CameraChangeMode
import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public interface MapObserver {

  // Camera events

  /** Called once when camera position is set or an transition animation is started. */
  @CalledByNative public fun onCameraWillChange(mode: CameraChangeMode) {}

  /** Called for each camera position change during a transition animation. */
  @CalledByNative public fun onCameraIsChanging() {}

  /** Called once when camera position is set or transition animation has ended. */
  @CalledByNative public fun onCameraDidChange(mode: CameraChangeMode) {}

  // Map loading events
  @CalledByNative public fun onWillStartLoadingMap() {}

  @CalledByNative public fun onDidFinishLoadingMap() {}

  @CalledByNative public fun onDidFailLoadingMap(error: MapLoadError, message: String) {}

  // Rendering events - most important for MVP
  @CalledByNative public fun onWillStartRenderingFrame() {}

  @CalledByNative public fun onDidFinishRenderingFrame(status: RenderFrameStatus) {}

  @CalledByNative public fun onWillStartRenderingMap() {}

  @CalledByNative public fun onDidFinishRenderingMap(mode: RenderMode) {}

  // Style events
  @CalledByNative public fun onDidFinishLoadingStyle() {}

  @CalledByNative public fun onStyleImageMissing(imageId: String) {}

  // Idle state
  @CalledByNative public fun onDidBecomeIdle() {}

  // TODO: Add these callbacks when we have the necessary types:
  // - onSourceChanged(source: Source)
  // - onCanRemoveUnusedStyleImage(imageId: String): Boolean
  // - onRegisterShaders(registry: ShaderRegistry)
  // - Shader compilation callbacks
  // - Glyph loading callbacks
  // - Tile loading callbacks
  // - Sprite loading callbacks
}
