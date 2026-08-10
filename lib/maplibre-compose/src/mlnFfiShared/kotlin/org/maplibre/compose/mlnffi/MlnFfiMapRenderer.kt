package org.maplibre.compose.mlnffi

import org.maplibre.compose.map.MapExtent

/** The outcome of one [MlnFfiMapRenderer.render] call. */
internal enum class MlnFfiFrameResult {
  /** The renderer drew into the frame's target. */
  RENDERED,
  /** The renderer had no update to draw; the last completed target is still current. */
  SKIPPED,
}

/**
 * Receives surface lifecycle and frames from a [MlnFfiMapHost].
 *
 * An interface rather than a class only so the surface can be driven by a stand-in under test;
 * `MlnFfiMapSession` is the one real implementation, and nothing outside this library writes
 * another.
 *
 * Every method is called with renderer access held, on the host's renderer thread.
 */
internal interface MlnFfiMapRenderer : AutoCloseable {
  /** The backend this renderer needs MapLibre Native to render with. */
  val backend: MapRenderBackend

  /**
   * Called once when the host surface becomes usable, before any frame.
   *
   * [session] stays valid until [onSurfaceLost] or [close].
   */
  fun onSurfaceAvailable(session: MlnFfiMapHostSession) {}

  /** Called when the surface size or scale factor changed, before the next frame. */
  fun onSurfaceChanged(extent: MapExtent) {}

  /** Renders one frame into [frame]'s target. */
  fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult

  /**
   * Called when the surface went away and any target handles previously seen are now dangling.
   *
   * The renderer must drop them without freeing them; the host owns them. A new surface may follow
   * via [onSurfaceAvailable].
   */
  fun onSurfaceLost() {}
}

/** A graphics failure for which rebuilding the render session can produce a usable later frame. */
internal open class MlnFfiRecoverableFrameException(message: String, cause: Throwable?) :
  IllegalStateException(message, cause)
