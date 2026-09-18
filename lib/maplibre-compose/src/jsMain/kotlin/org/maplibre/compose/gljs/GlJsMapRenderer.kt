package org.maplibre.compose.gljs

import org.maplibre.compose.map.MapExtent

/** The surface's view of the map session. */
internal interface GlJsMapRenderer : AutoCloseable {
  val maximumFps: Int?
    get() = null

  /** Geometry used to display a retained composited image before overlay placement. */
  fun presentFrame(target: GlJsRenderTarget?, extent: MapExtent) {}

  /** Called again after a recoverable failure, so an implementation must take a second surface. */
  fun onSurfaceAvailable(surface: GlJsSurfaceSession)

  /** The surface is going away; drop everything that points into it. */
  fun onSurfaceLost()

  /**
   * Renders one frame into [target], before Compose overlay placement.
   *
   * @return whether anything was rendered. False is ordinary: before the style loads there is
   *   nothing to draw.
   */
  fun render(target: GlJsFrameTarget, extent: MapExtent): Boolean
}

/** The map session's view of its surface. */
internal interface GlJsSurfaceSession {
  fun requestFrame()
}
