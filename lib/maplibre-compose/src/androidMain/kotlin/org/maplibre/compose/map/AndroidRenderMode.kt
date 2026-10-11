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
   * A surface behind the window, with Compose overlays drawn on top. On API 33 and later, when
   * hardware rendering and coordinated buffers are available, geographic overlay placement uses the
   * displayed map buffer's projection. The map buffer and Compose overlays appear together in the
   * window draw. Otherwise a SurfaceView presents the map independently of Compose.
   *
   * Some Compose graphics modifiers, including arbitrary clipping and alpha, do not apply to this
   * mode. Use [Texture] when those modifiers are required.
   */
  Surface,
}
