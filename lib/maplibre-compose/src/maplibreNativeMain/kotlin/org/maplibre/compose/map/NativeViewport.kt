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
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrameProjection
import org.maplibre.compose.mlnffi.MlnFfiMapPresentationAnchor
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
import org.maplibre.nativeffi.generated.MapSnapshot
import org.maplibre.nativeffi.generated.RenderSessionHandle
import org.maplibre.spatialk.geojson.Position

/** Main-thread snapshot cache, with a separate projection borrow for presented frames. */
internal class NativeViewport {
  private var viewportInsets = EdgeInsets()
  var appliedViewportInsets = EdgeInsets()
    private set

  @Volatile private var renderedCameraPadding = EdgeInsets()

  /** Defer camera framing until a render target has attached and queued its resize. */
  private var pendingCameraPadding: DpPadding? = null

  var hasViewport = false
    private set

  private var snapshot: MapSnapshot? = null
  @Volatile private var geometry = MapViewportGeometry(CameraPosition(), EdgeInsets(), DpSize.Zero)
  private var projection: MapProjectionHandle? = null
  private var wrappedProjection: MapProjectionHandle? = null
  private var extents: MapViewportExtents? = null

  val generation: ULong
    get() = snapshot?.generation ?: 0uL

  val camera: CameraPosition
    get() = geometry.camera

  val size: DpSize
    get() = geometry.size

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
    val next = projection?.let { PresentedProjection(it, destination, scaleFactor) }
    if (presentedProjection == next) return
    presentedProjection = next
    presentationRevision.longValue += 1
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

  /** Read once on attachment, then whenever a camera event reports a newer generation. */
  fun refresh(map: MapHandle) {
    val next = map.getSnapshot()
    if (next.generation == snapshot?.generation) return
    projection?.close()
    wrappedProjection?.close()
    snapshot = next
    geometry = next.viewportGeometry(appliedViewportInsets)
    projection = map.createProjection()
    wrappedProjection =
      if (camera.center.longitude !in -180.0..<180.0) map.createWrappedProjection() else null
    extents = null
  }

  fun attach(map: MapHandle) {
    hasViewport = true
    refresh(map)
  }

  fun detach() {
    hasViewport = false
  }

  fun retire() {
    viewportExtents()
    projection?.close()
    projection = null
    wrappedProjection?.close()
    wrappedProjection = null
  }

  fun captureRenderPadding() {
    renderedCameraPadding = geometry.padding
  }

  fun setInsets(insets: EdgeInsets): Boolean {
    if (viewportInsets == insets) return false
    viewportInsets = insets
    return true
  }

  /** Main only. Returns the jump that moves a new map to [requested], if any. */
  fun prepareCamera(requested: CameraPosition?): CameraUpdate? {
    snapshot = null
    appliedViewportInsets = EdgeInsets()
    pendingCameraPadding = requested?.padding?.takeUnless { it == DpPadding.Zero }
    return requested?.let {
      CameraUpdate(
        camera = it.copy(padding = DpPadding.Zero).toCameraOptions(appliedViewportInsets)
      )
    }
  }

  /** Main only: defer requested camera padding until a real target extent is applied. */
  fun cameraForAssignment(position: CameraPosition): CameraPosition {
    if (hasViewport) return position
    pendingCameraPadding = position.padding.takeUnless { it == DpPadding.Zero }
    return position.copy(padding = DpPadding.Zero)
  }

  /**
   * On main, with a usable viewport. Returns the jump that applies the insets, or null when nothing
   * changed. Reads the camera from the map's snapshot.
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

  private fun viewportExtents(): MapViewportExtents =
    extents
      ?: MapViewportExtents(projection?.let { unprojectedCorners(it, size) } ?: EmptyCorners).also {
        extents = it
      }

  fun getVisibleBounds(): VisibleBounds = viewportExtents().bounds

  fun getVisibleRegion(): VisibleRegion = viewportExtents().region

  fun getViewport(): Viewport? {
    if (!hasViewport || size == DpSize.Zero) return null
    val extents = viewportExtents()
    return Viewport(
      cameraPosition = camera,
      size = size,
      visibleBounds = extents.bounds,
      visibleRegion = extents.region,
      metersPerDpAtCenter = metersPerDpAtLatitude(camera.center.latitude),
    )
  }

  fun positionFromScreenLocation(offset: DpOffset): Position? =
    projection?.latLngForPixelUnwrapped(offset.toScreenPoint())?.toPosition()

  fun boxZoomFit(rect: DpRect): BoxZoomFit? {
    val projection = projection ?: return null
    return boxZoomFit(rect, camera) {
      projection.latLngForPixel(it.toScreenPoint()).toPosition()
    }
  }

  fun screenLocationFromPosition(position: Position): DpOffset? =
    (wrappedProjection ?: projection)?.pixelForLatLng(position.toLatLng())?.toDpOffset()

  fun overlayScreenLocationFromPosition(position: Position): DpOffset? {
    presentationRevision.longValue
    val presented = presentedProjection
    if (presented != null) return presented.toScreen(presented.frame.screenLocation(position))
    return screenLocationFromPosition(position)
  }

  fun metersPerDpAtLatitude(latitude: Double): Double =
    projection?.metersPerPixelAtLatitude(latitude.coerceIn(-90.0, 90.0))
      ?: metersPerDpAtLatitude(camera.zoom, latitude)
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
