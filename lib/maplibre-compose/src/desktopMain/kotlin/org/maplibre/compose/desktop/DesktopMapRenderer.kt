package org.maplibre.compose.desktop

/**
 * The outcome of one [DesktopMapRenderer.render] call.
 *
 * MapLibre only redraws when something changed, so a renderer that had nothing to do reports
 * [Skipped] and the host presents its previously completed target instead of an empty one.
 */
public enum class DesktopFrameResult {
  /** The renderer drew into the frame's target. */
  RENDERED,
  /** The renderer had no update to draw; the last completed target is still current. */
  SKIPPED,
}

/**
 * Receives surface lifecycle and frames from a [DesktopMapHost].
 *
 * MapLibre Compose implements this to drive a map. Applications implement [DesktopMapHostFactory],
 * not this.
 *
 * Every method is called with renderer access held, on the host's renderer thread.
 */
public interface DesktopMapRenderer : AutoCloseable {
  /** The backend this renderer needs MapLibre Native to render with. */
  public val backend: MapRenderBackend

  /**
   * Called once when the host surface becomes usable, before any frame.
   *
   * [session] stays valid until [onSurfaceLost] or [close].
   */
  public fun onSurfaceAvailable(session: DesktopMapHostSession) {}

  /** Called when the surface size or scale factor changed, before the next frame. */
  public fun onSurfaceChanged(extent: DesktopMapExtent) {}

  /** Renders one frame into [frame]'s target. */
  public fun render(frame: DesktopMapFrame): DesktopFrameResult

  /**
   * Called when the surface went away and any target handles previously seen are now dangling.
   *
   * The renderer must drop them without freeing them; the host owns them. A new surface may follow
   * via [onSurfaceAvailable].
   */
  public fun onSurfaceLost() {}
}
