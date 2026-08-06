package org.maplibre.compose.desktop

/** The outcome of one [DesktopMapRenderer.render] call. */
internal enum class DesktopFrameResult {
  /** The renderer drew into the frame's target. */
  RENDERED,
  /** The renderer had no update to draw; the last completed target is still current. */
  SKIPPED,
}

/**
 * Receives surface lifecycle and frames from a [DesktopMapHost].
 *
 * An interface rather than a class only so the surface can be driven by a stand-in under test;
 * `DesktopMapSession` is the one real implementation, and nothing outside this library writes
 * another.
 *
 * Every method is called with renderer access held, on the host's renderer thread.
 */
internal interface DesktopMapRenderer : AutoCloseable {
  /** The backend this renderer needs MapLibre Native to render with. */
  val backend: MapRenderBackend

  /**
   * Called once when the host surface becomes usable, before any frame.
   *
   * [session] stays valid until [onSurfaceLost] or [close].
   */
  fun onSurfaceAvailable(session: DesktopMapHostSession) {}

  /** Called when the surface size or scale factor changed, before the next frame. */
  fun onSurfaceChanged(extent: DesktopMapExtent) {}

  /** Renders one frame into [frame]'s target. */
  fun render(frame: DesktopMapFrame): DesktopFrameResult

  /**
   * Called when the surface went away and any target handles previously seen are now dangling.
   *
   * The renderer must drop them without freeing them; the host owns them. A new surface may follow
   * via [onSurfaceAvailable].
   */
  fun onSurfaceLost() {}
}

/**
 * Thrown from [DesktopMapRenderer.render] when no surface could make the map work again.
 *
 * A frame that throws is otherwise read as a lost device and retried against a rebuilt render
 * session; this says the failure is above the graphics layer, so the surface stops instead.
 */
internal class DesktopMapFatalFrameException(message: String, cause: Throwable?) :
  IllegalStateException(message, cause)
