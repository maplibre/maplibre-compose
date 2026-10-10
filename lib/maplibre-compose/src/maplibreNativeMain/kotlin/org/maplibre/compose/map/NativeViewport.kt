package org.maplibre.compose.map

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.camera.internal.BoxZoomFit
import org.maplibre.compose.camera.internal.boxZoomFit
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrameProjection
import org.maplibre.compose.mlnffi.MlnFfiMapPresentationAnchor
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.metersPerDpAtLatitude
import org.maplibre.compose.util.toCameraOptions
import org.maplibre.compose.util.toCameraPosition
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toScreenPoint
import org.maplibre.nativeffi.generated.CameraOptions
import org.maplibre.nativeffi.generated.CameraUpdate
import org.maplibre.nativeffi.generated.EdgeInsets
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.MapProjectionHandle
import org.maplibre.nativeffi.generated.RenderSessionHandle
import org.maplibre.spatialk.geojson.Position

/**
 * Requested/applied extent and frozen map projections; presented frames remain a separate borrow.
 *
 * The mirror is rebuilt from the map's published snapshot, which any thread can read, whenever the
 * session learns that the camera or viewport changed. Camera commands that read the applied insets
 * run under the session's camera lock.
 */
internal class NativeViewport(private val isClosing: () -> Boolean) {
  private val stateLock = MlnFfiLock()
  @Volatile private var viewportInsets: EdgeInsets = EdgeInsets()
  @Volatile
  var appliedViewportInsets: EdgeInsets = EdgeInsets()
    private set

  @Volatile private var renderedCameraPadding: EdgeInsets = EdgeInsets()

  /** Under the camera lock: do not apply camera framing against the bootstrap 1x1 viewport. */
  private var pendingCameraPadding: DpPadding? = null

  private class ViewportRequest(val extent: MapExtent)

  /** Requested by the renderer and acknowledged by the event pump, under [stateLock]. */
  @Volatile private var viewportRequest: ViewportRequest? = null
  @Volatile private var appliedViewportRequest: ViewportRequest? = null

  val hasViewport: Boolean
    get() = appliedViewportRequest != null

  /**
   * Applied camera, extents, and a projection frozen at that camera. One write publishes them
   * together so any-thread getters agree with one map snapshot, and read it without a native call.
   * The default answers reads made before the first snapshot.
   */
  private class MirroredViewport(
    val generation: ULong = 0uL,
    val camera: CameraPosition = CameraPosition(),
    val effectivePadding: EdgeInsets = EdgeInsets(),
    val size: DpSize = DpSize.Zero,
    val projection: MapProjectionHandle? = null,
    val wrappedProjection: MapProjectionHandle? = null,
    extents: MapViewportExtents? = null,
    metersPerDpAtCenter: Double? = null,
  ) {
    /**
     * The corners this camera renders. They are derived from the frozen projection on the thread
     * that asks for them, and kept for later readers of the same publish.
     */
    @Volatile private var derivedExtents: MapViewportExtents? = extents
    private var derivedMetersPerDpAtCenter: Double? = metersPerDpAtCenter

    /** Read under the projection lock, like [extents]. */
    fun metersPerDpAtCenter(): Double =
      derivedMetersPerDpAtCenter
        ?: metersPerDpAtLatitude(camera.center.latitude).also {
          derivedMetersPerDpAtCenter = it
        }

    fun metersPerDpAtLatitude(latitude: Double): Double =
      projection?.metersPerPixelAtLatitude(latitude.coerceIn(-90.0, 90.0))
        ?: metersPerDpAtLatitude(camera.zoom, latitude)

    /**
     * Call under the projection lock, having read the mirror under that same lock: a replaced
     * publish's handles close as soon as the lock is free.
     */
    fun extents(): MapViewportExtents {
      derivedExtents?.let {
        return it
      }
      val corners = projection?.let { unprojectedCorners(it, size) } ?: EmptyCorners
      return MapViewportExtents(corners).also { derivedExtents = it }
    }

    /** Freezes what the projection can still answer, before the handle is closed. */
    fun withoutProjection(): MirroredViewport =
      MirroredViewport(
        generation,
        camera,
        effectivePadding,
        size,
        null,
        null,
        extents(),
        metersPerDpAtCenter(),
      )
  }

  @Volatile private var mirroredViewport = MirroredViewport()

  /**
   * Publication of [mirroredViewport] versus a conversion that is using its projection. A publish
   * swaps the snapshot and closes the outgoing handle only after that conversion returns.
   */
  private val projectionLock = MlnFfiLock()

  /**
   * True from a camera event until the next snapshot. The engine reports every transform change as
   * camera events, so the mirror is read again only after one of them, once per drain, rather than
   * after every drain.
   */
  @Volatile var snapshotStale = false

  // Keep native handles outside Compose snapshots: an older snapshot must not read a closed handle.
  private var presentedProjection: PresentedProjection? = null
  private val presentationRevision = mutableLongStateOf(0L)

  private data class PresentedProjection(
    val frame: MlnFfiMapFrameProjection,
    val destination: MlnFfiMapDestination,
    val scaleFactor: Double,
  ) {
    fun toScreen(point: DpOffset): DpOffset =
      DpOffset(
        ((point.x.value * frame.extent.scaleFactor + destination.left) / scaleFactor).dp,
        ((point.y.value * frame.extent.scaleFactor + destination.top) / scaleFactor).dp,
      )
  }

  private class FrameProjection(
    override val extent: MapExtent,
    val projection: MapProjectionHandle,
  ) : MlnFfiMapFrameProjection {
    override val anchor: MlnFfiMapPresentationAnchor
      get() {
        val padding = checkNotNull(projection.getCamera().padding)
        return MlnFfiMapPresentationAnchor(
          ((extent.physicalWidth + (padding.left - padding.right) * extent.scaleFactor) / 2)
            .toInt(),
          ((extent.physicalHeight + (padding.top - padding.bottom) * extent.scaleFactor) / 2)
            .toInt(),
        )
      }

    override fun screenLocation(position: Position): DpOffset =
      projection.pixelForLatLng(position.toLatLng()).toDpOffset()

    override fun close() = projection.close()
  }

  fun presentFrame(
    projection: MlnFfiMapFrameProjection?,
    destination: MlnFfiMapDestination,
    scaleFactor: Double,
  ) {
    projectionLock.withLock {
      val next = projection?.let { PresentedProjection(it, destination, scaleFactor) }
      if (presentedProjection == next) return
      presentedProjection = next
      presentationRevision.longValue += 1
    }
  }

  fun captureFrameProjection(
    session: RenderSessionHandle,
    extent: MapExtent,
  ): MlnFfiMapFrameProjection =
    FrameProjection(extent, session.createProjection().normalizeWrappedCenter())

  fun presentationAnchor(extent: MapExtent): MlnFfiMapPresentationAnchor {
    val padding = renderedCameraPadding
    return MlnFfiMapPresentationAnchor(
      x =
        ((extent.physicalWidth + (padding.left - padding.right) * extent.scaleFactor) / 2.0)
          .toInt(),
      y =
        ((extent.physicalHeight + (padding.top - padding.bottom) * extent.scaleFactor) / 2.0)
          .toInt(),
    )
  }

  /**
   * The renderer sends dimensions; the event pump acknowledges them once the map's snapshot reports
   * the size that the render session applied.
   */
  fun request(extent: MapExtent): Boolean = stateLock.withLock {
    if (isClosing()) return@withLock false
    viewportRequest = ViewportRequest(extent)
    true
  }

  /**
   * Event pump only, under the camera lock. Returns the request whose size the map now has, and the
   * jump that applies the insets with it, or null while no request waits or its size has not
   * reached the map. Acknowledge it with [acknowledge] once the jump has run.
   */
  fun pendingRequest(
    map: MapHandle,
    beforeResize: (DpSize) -> Unit,
    beforePadding: () -> Unit,
  ): Pair<Any, CameraUpdate?>? {
    val request = viewportRequest?.takeUnless { it === appliedViewportRequest } ?: return null
    val size = map.getSnapshot().logicalExtent
    if (
      size.width.toInt() != request.extent.width || size.height.toInt() != request.extent.height
    ) {
      return null
    }
    beforeResize(DpSize(size.width.toInt().dp, size.height.toInt().dp))
    return request to applyInsets(map, beforePadding)
  }

  /**
   * Publishes readiness after size and padding describe the same viewport. Keeps the last usable
   * viewport during resize and surface loss: a detached presentation cannot be made ready by an
   * acknowledgment that was already in flight.
   */
  fun acknowledge(request: Any): Boolean = stateLock.withLock {
    if (viewportRequest !== request) return@withLock false
    appliedViewportRequest = viewportRequest
    true
  }

  /** Publishes the map's latest camera and viewport for any-thread getters. Any thread. */
  fun snapshot(map: MapHandle) {
    // Cleared first, so a camera event that arrives during the read marks the mirror again.
    snapshotStale = false
    val snapshot = map.getSnapshot()
    if (snapshot.generation < mirroredViewport.generation) return
    val geometry = snapshot.viewportGeometry(appliedViewportInsets)
    publishViewport(
      MirroredViewport(
        generation = snapshot.generation,
        camera = geometry.camera,
        effectivePadding = geometry.padding,
        size = geometry.size,
        // A fresh handle per snapshot: createProjection freezes the transform at creation.
        projection = map.createProjection(),
        wrappedProjection =
          if (geometry.camera.center.longitude !in -180.0..<180.0) {
            map.createWrappedProjection()
          } else null,
      )
    )
  }

  fun retire() {
    val previous = projectionLock.withLock {
      val current = mirroredViewport
      mirroredViewport = current.withoutProjection()
      current
    }
    runCatching { previous.projection?.close() }
    runCatching { previous.wrappedProjection?.close() }
  }

  private fun publishViewport(next: MirroredViewport) {
    val previous = projectionLock.withLock {
      val current = mirroredViewport
      // Two threads can read snapshots concurrently; the older one must not win.
      if (next.generation < current.generation) {
        current.also { mirroredViewport = it }
        return@withLock next
      }
      mirroredViewport = next
      current
    }
    runCatching { previous.projection?.close() }
    runCatching { previous.wrappedProjection?.close() }
  }

  val camera
    get() = mirroredViewport.camera

  fun captureRenderPadding() {
    renderedCameraPadding = mirroredViewport.effectivePadding
  }

  fun clearRequest(clearApplied: Boolean = false) {
    stateLock.withLock {
      viewportRequest = null
      if (clearApplied) appliedViewportRequest = null
    }
  }

  fun setInsets(insets: EdgeInsets): Boolean {
    if (viewportInsets == insets) return false
    viewportInsets = insets
    return true
  }

  /** Under the camera lock. Returns the jump that moves a new map to [requested], if any. */
  fun prepareCamera(requested: CameraPosition?): CameraUpdate? {
    appliedViewportInsets = EdgeInsets()
    pendingCameraPadding = requested?.padding?.takeUnless { it == DpPadding.Zero }
    return requested?.let {
      CameraUpdate(
        camera = it.copy(padding = DpPadding.Zero).toCameraOptions(appliedViewportInsets)
      )
    }
  }

  /**
   * Under the camera lock: defer requested camera padding until a real target extent is applied.
   */
  fun cameraForAssignment(position: CameraPosition): CameraPosition {
    if (hasViewport) return position
    pendingCameraPadding = position.padding.takeUnless { it == DpPadding.Zero }
    return position.copy(padding = DpPadding.Zero)
  }

  /**
   * Under the camera lock, with a usable viewport. Returns the jump that applies the insets, or
   * null when nothing changed. Reads the camera from the map's snapshot.
   */
  fun applyInsets(map: MapHandle, beforeChange: () -> Unit): CameraUpdate? {
    val padding = viewportInsets
    val pending = pendingCameraPadding
    if (padding == appliedViewportInsets && pending == null) return null
    beforeChange()
    val current = map.getSnapshot().camera.toCameraPosition(appliedViewportInsets)
    val effective =
      current.copy(padding = pending ?: current.padding).toCameraOptions(padding).padding
    appliedViewportInsets = padding
    pendingCameraPadding = null
    return CameraUpdate(camera = CameraOptions(padding = effective))
  }

  fun getVisibleBounds(): VisibleBounds = projectionLock.withLock {
    mirroredViewport.extents().bounds
  }

  fun getVisibleRegion(): VisibleRegion = projectionLock.withLock {
    mirroredViewport.extents().region
  }

  fun getViewport(): Viewport? {
    // The map bootstraps at a 1x1 extent, so the mirror describes a real viewport only once the
    // session has acknowledged the render target's dimensions and applied padding.
    if (!hasViewport) return null
    // One read under the lock that keeps its projection open: every property comes from the same
    // publish, and nothing can close that publish's handles while they are read.
    return projectionLock.withLock {
      val mirror = mirroredViewport
      if (mirror.size == DpSize.Zero) return@withLock null
      val extents = mirror.extents()
      Viewport(
        cameraPosition = mirror.camera,
        size = mirror.size,
        visibleBounds = extents.bounds,
        visibleRegion = extents.region,
        metersPerDpAtCenter = mirror.metersPerDpAtCenter(),
      )
    }
  }

  fun positionFromScreenLocation(offset: DpOffset): Position? = projectionLock.withLock {
    mirroredViewport.projection?.latLngForPixelUnwrapped(offset.toScreenPoint())?.toPosition()
  }

  fun boxZoomFit(rect: DpRect): BoxZoomFit? = projectionLock.withLock {
    val projection = mirroredViewport.projection ?: return@withLock null
    boxZoomFit(rect, mirroredViewport.camera) {
      projection.latLngForPixel(it.toScreenPoint()).toPosition()
    }
  }

  fun screenLocationFromPosition(position: Position): DpOffset? = projectionLock.withLock {
    val snapshot = mirroredViewport
    val projection = snapshot.wrappedProjection ?: snapshot.projection ?: return@withLock null
    projection.pixelForLatLng(position.toLatLng()).toDpOffset()
  }

  fun overlayScreenLocationFromPosition(position: Position): DpOffset? = projectionLock.withLock {
    presentationRevision.longValue
    val presented = presentedProjection
    if (presented != null)
      return@withLock presented.toScreen(presented.frame.screenLocation(position))
    val snapshot = mirroredViewport
    val projection = snapshot.wrappedProjection ?: snapshot.projection ?: return@withLock null
    projection.pixelForLatLng(position.toLatLng()).toDpOffset()
  }

  fun metersPerDpAtLatitude(latitude: Double): Double = projectionLock.withLock {
    mirroredViewport.metersPerDpAtLatitude(latitude)
  }
}

/**
 * Native projects against a wrapped center, but anchored moves can leave the transform in another
 * world. Normalize a standalone projection for geographic-to-screen conversion. Keep the raw
 * snapshot separately so screen-to-geographic conversion still preserves the actual world copy.
 */
internal fun MapHandle.createWrappedProjection(): MapProjectionHandle =
  createProjection().normalizeWrappedCenter()

private fun MapProjectionHandle.normalizeWrappedCenter(): MapProjectionHandle {
  try {
    val center = getCamera().center
    if (center != null && center.longitude !in -180.0..<180.0) {
      val longitude = ((center.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
      setCamera(CameraOptions(center = Position(longitude, center.latitude).toLatLng()))
    }
    return this
  } catch (error: Throwable) {
    close()
    throw error
  }
}
