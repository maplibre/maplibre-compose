package org.maplibre.compose.mlnffi

import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.map.MapExtent
import org.maplibre.spatialk.geojson.Position

/** The outcome of one [MlnFfiMapRenderer.render] call. */
internal sealed interface MlnFfiFrameResult {
  /** The caller owns the optional projection until it retires this completed image. */
  data class Rendered(val projection: MlnFfiMapFrameProjection? = null) : MlnFfiFrameResult

  /** Retain the last image and wait for an engine update. */
  data object AwaitUpdate : MlnFfiFrameResult

  /** The target is temporarily unavailable; retry on a later frame. */
  data object RetryNextFrame : MlnFfiFrameResult
}

/** The physical pixel in a render target that contains the padded camera center. */
internal data class MlnFfiMapPresentationAnchor(val x: Int, val y: Int)

/** Returns the unpadded center of this extent in physical pixels. */
internal fun MapExtent.centerPresentationAnchor(): MlnFfiMapPresentationAnchor =
  MlnFfiMapPresentationAnchor(x = physicalWidth / 2, y = physicalHeight / 2)

/**
 * Receives lifecycle and frames from a surface controller. [render] runs with producer access on
 * the renderer thread. Lifecycle methods marshal native work through the supplied host session;
 * [presentFrame] borrows a completed projection on the Compose thread.
 */
internal interface MlnFfiMapRenderer : AutoCloseable {
  /** The backend this renderer needs MapLibre Native to render with. */
  val backend: MapRenderBackend

  val maximumFps: Int?
    get() = null

  /**
   * Called once when the host surface becomes usable, before any frame.
   *
   * [session] stays valid until [onSurfaceLost] or [close].
   */
  fun onSurfaceAvailable(session: MlnFfiMapHostSession) {}

  /** Called when the surface size or scale factor changed, before the next frame. */
  fun onSurfaceChanged(extent: MapExtent) {}

  /**
   * Renders into [frame]'s target. When [captureProjection] is true, acquires the completed image's
   * projection before returning, while renderer access is still held. Surface presenters that do
   * not composite overlays leave this false to avoid allocating an unused snapshot.
   */
  fun render(frame: MlnFfiMapFrame, captureProjection: Boolean = false): MlnFfiFrameResult

  /** Borrows the surface-owned projection until the surface replaces or clears the presentation. */
  fun presentFrame(
    projection: MlnFfiMapFrameProjection?,
    destination: MlnFfiMapDestination,
    scaleFactor: Double,
  ) {}

  /**
   * Returns the camera anchor for the most recent [render] attempt at [extent]. The surface stores
   * this value with a completed target so that a later resize can preserve its screen position.
   */
  fun presentationAnchor(extent: MapExtent): MlnFfiMapPresentationAnchor =
    extent.centerPresentationAnchor()

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

/** A projection owned by a completed frame, published before overlay placement. */
internal interface MlnFfiMapFrameProjection : AutoCloseable {
  val extent: MapExtent
  val anchor: MlnFfiMapPresentationAnchor

  fun screenLocation(position: Position): DpOffset
}
