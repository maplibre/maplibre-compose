package org.maplibre.compose.gljs

import org.maplibre.compose.map.MapExtent

/** The surface's view of the map session. */
internal interface GlJsMapRenderer : AutoCloseable {
  /** Called again after a recoverable failure, so an implementation must take a second surface. */
  fun onSurfaceAvailable(surface: GlJsSurfaceSession)

  /** The surface is going away; drop everything that points into it. */
  fun onSurfaceLost()

  /**
   * Renders one frame into [target], inside Compose's draw. [extent] carries the resize, so the map
   * is never asked to render at a size it has not been told about.
   *
   * @return whether anything was rendered. False is ordinary: before the style loads there is
   *   nothing to draw, and the surface then shows whatever the target already held.
   */
  fun render(target: GlJsFrameTarget, extent: MapExtent): Boolean
}

/** The map session's view of its surface. */
internal interface GlJsSurfaceSession {
  /**
   * MapLibre's own repaint requests arrive here once [GlJsRuntime.interceptRepaintRequests] has
   * taken its scheduling away, so this runs for every tile that lands too.
   */
  fun requestFrame()
}
