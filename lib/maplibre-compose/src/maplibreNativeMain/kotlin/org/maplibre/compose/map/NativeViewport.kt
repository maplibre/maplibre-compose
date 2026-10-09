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
import org.maplibre.nativeffi.camera.CameraOptions
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapProjectionHandle
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.spatialk.geojson.Position

/**
 * Requested/applied extent and frozen map projections; presented frames remain a separate borrow.
 */
internal class NativeViewport(private val isClosing: () -> Boolean) {
  private val stateLock = MlnFfiLock()
  @Volatile private var viewportInsets: EdgeInsets = EdgeInsets.ZERO
  @Volatile
  var appliedViewportInsets: EdgeInsets = EdgeInsets.ZERO
    private set

  @Volatile private var renderedCameraPadding: EdgeInsets = EdgeInsets.ZERO

  /** Owner thread only: do not apply camera framing against the bootstrap 1x1 viewport. */
  private var pendingCameraPadding: DpPadding? = null

  private class ViewportRequest(val extent: MapExtent)

  /** Requested by the renderer and acknowledged by the owner, under [stateLock]. */
  @Volatile private var viewportRequest: ViewportRequest? = null
  @Volatile private var appliedViewportRequest: ViewportRequest? = null

  val hasViewport: Boolean
    get() = appliedViewportRequest != null

  /**
   * Applied camera, extents, and a projection frozen at that camera. One write publishes them
   * together so any-thread getters agree with the last native apply. The FFI map lives on the owner
   * thread, so getters read this snapshot instead of hopping. The default answers reads made before
   * the first snapshot.
   */
  private class MirroredViewport(
    val camera: CameraPosition = CameraPosition(),
    val effectivePadding: EdgeInsets = EdgeInsets.ZERO,
    val size: DpSize = DpSize.Zero,
    val projection: MapProjectionHandle? = null,
    val wrappedProjection: MapProjectionHandle? = null,
    extents: MapViewportExtents? = null,
    metersPerDpAtCenter: Double? = null,
  ) {
    /**
     * The corners this camera renders. Unprojecting them is a quarter of the owner thread's work
     * during camera motion, so they are derived from the frozen projection on the thread that asks
     * for them, and kept for later readers of the same publish.
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
     * Call under the projection lock, having read the mirror under that same lock: the owner thread
     * closes a replaced publish's handles as soon as the lock is free.
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
      MirroredViewport(camera, effectivePadding, size, null, null, extents(), metersPerDpAtCenter())
  }

  @Volatile private var mirroredViewport = MirroredViewport()

  /**
   * Publication of [mirroredViewport] versus a conversion that is using its projection. The owner
   * thread swaps the snapshot and closes the outgoing handle only after that conversion returns.
   */
  private val projectionLock = MlnFfiLock()

  /**
   * Owner thread only. True from a camera event until the next snapshot. The engine reports every
   * transform change as camera events, so the mirror is read again only after one of them, once per
   * drain, rather than after every drain.
   */
  var snapshotStale = false

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
        val padding = checkNotNull(projection.camera.padding)
        return MlnFfiMapPresentationAnchor(
          ((extent.physicalWidth + (padding.left - padding.right) * extent.scaleFactor) / 2)
            .toInt(),
          ((extent.physicalHeight + (padding.top - padding.bottom) * extent.scaleFactor) / 2)
            .toInt(),
        )
      }

    override fun screenLocation(position: Position): DpOffset? =
      projection.overlayScreenLocation(position)

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
   * The renderer sends dimensions; the owner acknowledges them after Native's asynchronous resize.
   */
  fun request(extent: MapExtent): Boolean = stateLock.withLock {
    if (isClosing()) return@withLock false
    viewportRequest = ViewportRequest(extent)
    true
  }

  /** Owner thread only. Publish readiness after size and padding describe the same viewport. */
  fun applyPending(
    map: MapHandle,
    beforeResize: (DpSize) -> Unit,
    beforePadding: () -> Unit,
  ): Boolean {
    val request = viewportRequest?.takeUnless { it === appliedViewportRequest } ?: return false
    val size = map.size
    if (size.width != request.extent.width || size.height != request.extent.height) return false
    beforeResize(DpSize(size.width.dp, size.height.dp))
    applyInsets(map, beforePadding)
    snapshot(map)
    // Keep the last usable viewport during resize and surface loss. A detached presentation
    // cannot be made ready by an acknowledgment that was already in flight.
    return stateLock.withLock {
      if (viewportRequest !== request) return@withLock false
      appliedViewportRequest = request
      true
    }
  }

  /** Owner thread only. Publishes the applied camera and viewport for any-thread getters. */
  fun snapshot(map: MapHandle) {
    val geometry = map.readViewportGeometry(appliedViewportInsets)
    publishViewport(
      MirroredViewport(
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
    // A failed read leaves this stale so the next event drain retries it.
    snapshotStale = false
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

  fun prepareCamera(map: MapHandle, requested: CameraPosition?) {
    appliedViewportInsets = EdgeInsets.ZERO
    pendingCameraPadding = requested?.padding?.takeUnless { it == DpPadding.Zero }
    requested?.let {
      map.jumpTo(it.copy(padding = DpPadding.Zero).toCameraOptions(appliedViewportInsets))
    }
  }

  /** Owner thread only: defer requested camera padding until a real target extent is applied. */
  fun cameraForAssignment(position: CameraPosition): CameraPosition {
    if (hasViewport) return position
    pendingCameraPadding = position.padding.takeUnless { it == DpPadding.Zero }
    return position.copy(padding = DpPadding.Zero)
  }

  /** Owner thread only, with a usable viewport. */
  fun applyInsets(map: MapHandle, beforeChange: () -> Unit) {
    val padding = viewportInsets
    val pending = pendingCameraPadding
    if (padding == appliedViewportInsets && pending == null) return
    beforeChange()
    val current = map.camera.toCameraPosition(appliedViewportInsets)
    val effective =
      current.copy(padding = pending ?: current.padding).toCameraOptions(padding).padding
    map.jumpTo(CameraOptions().also { it.padding = effective })
    appliedViewportInsets = padding
    pendingCameraPadding = null
  }

  fun getVisibleBounds(): VisibleBounds = projectionLock.withLock {
    mirroredViewport.extents().bounds
  }

  fun getVisibleRegion(): VisibleRegion = projectionLock.withLock {
    mirroredViewport.extents().region
  }

  fun getViewport(): Viewport? {
    // The map bootstraps at a 1x1 extent, so the mirror describes a real viewport only once the
    // map owner has acknowledged the render target's dimensions and applied padding.
    if (!hasViewport) return null
    // One read under the lock that keeps its projection open: every property comes from the same
    // publish, and the owner thread cannot close that publish's handles while they are read.
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
      return@withLock presented.frame.screenLocation(position)?.let(presented::toScreen)
    val snapshot = mirroredViewport
    val projection = snapshot.wrappedProjection ?: snapshot.projection ?: return@withLock null
    projection.overlayScreenLocation(position)
  }

  fun metersPerDpAtLatitude(latitude: Double): Double = projectionLock.withLock {
    mirroredViewport.metersPerDpAtLatitude(latitude)
  }
}

private fun MapProjectionHandle.overlayScreenLocation(position: Position): DpOffset? {
  val coordinate = position.toLatLng()
  if (isLocationOccluded(coordinate)) return null
  return pixelForLatLng(coordinate).toDpOffset()
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
    val center = camera.center
    if (center != null && center.longitude !in -180.0..<180.0) {
      val longitude = ((center.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
      setCamera(
        CameraOptions().also {
          it.center = Position(longitude, center.latitude).toLatLng()
        }
      )
    }
    return this
  } catch (error: Throwable) {
    close()
    throw error
  }
}
