package org.maplibre.compose.map

/**
 * Which Android view [MaplibreMap] draws the map through.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
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
