package org.maplibre.compose.map

/**
 * How Android presents the map in [MaplibreMap].
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
   * A surface behind the window, with Compose overlays drawn on top. Preferred for performance.
   *
   * On API 33 and later, each map frame appears in the same window draw as the Compose overlays
   * placed for it, so overlays at geographic positions stay on their map features while the camera
   * moves. On older versions, or when the device cannot share map buffers with the window, a
   * SurfaceView shows the map independently of Compose, and overlays can lag the map by a frame.
   *
   * Some Compose graphics modifiers, including arbitrary clipping and alpha, do not apply to this
   * mode. Use [Texture] when those modifiers are required.
   */
  Surface,
}
