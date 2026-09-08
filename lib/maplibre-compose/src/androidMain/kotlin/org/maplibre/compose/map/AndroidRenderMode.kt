package org.maplibre.compose.map

/** Which Android view [MaplibreMap] draws the map through. */
public enum class AndroidRenderMode {
  /**
   * A TextureView. Compose clipping, alpha, and other graphics modifiers apply to the map as they
   * do to other content.
   */
  Texture,

  /**
   * A SurfaceView. Preferred for performance. The surface sits behind the window, so Compose
   * overlays draw on top, and some Compose graphics modifiers do not apply to it.
   */
  Surface,
}

/** Which Android view draws the map. */
public var MapUiOptions.Builder.renderMode: AndroidRenderMode
  get() = platform.renderMode
  set(value) {
    platform = PlatformUiOptions(value)
  }

/** Which Android view draws the map. */
public val MapUiOptions.renderMode: AndroidRenderMode
  get() = platform.renderMode

internal actual data class PlatformUiOptions(val renderMode: AndroidRenderMode) {
  actual constructor() : this(AndroidRenderMode.Surface)
}
