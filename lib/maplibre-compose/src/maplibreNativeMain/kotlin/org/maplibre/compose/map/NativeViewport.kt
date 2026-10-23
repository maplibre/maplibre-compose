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
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toEdgeInsets
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toScreenPoint
import org.maplibre.nativeffi.generated.CameraOptions
import org.maplibre.nativeffi.generated.CameraUpdate
import org.maplibre.nativeffi.generated.EdgeInsets
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.MapProjectionHandle
import org.maplibre.nativeffi.generated.MapSnapshot
import org.maplibre.nativeffi.generated.MapView
import org.maplibre.spatialk.geojson.Position

/** Main-thread snapshot cache, with a separate projection borrow for presented frames. */
internal class NativeViewport {
  private val insets = ViewportInsets()
  val appliedViewportInsets: EdgeInsets
    get() = insets.applied.toEdgeInsets()

  @Volatile private var renderedCameraPadding = EdgeInsets()

  var hasViewport = false
    private set

  private var snapshot: MapSnapshot? = null
  @Volatile private var geometry = MapViewportGeometry(CameraPosition(), EdgeInsets(), DpSize.Zero)
  private var cachedProjection: MapProjectionHandle? = null
  private val projection: MapProjectionHandle?
    get() =
      cachedProjection
        ?: snapshot?.view?.let(GeneratedApi::mapProjectionCreate)?.also { cachedProjection = it }

  private var extents: MapViewportExtents? = null
  private var cachedViewport: Viewport? = null

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
    view: MapView,
    extent: MapExtent,
  ): MlnFfiMapFrameProjection = FrameProjection(extent, GeneratedApi.mapProjectionCreate(view))

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
    cachedProjection?.close()
    cachedProjection = null
    snapshot = next
    geometry = next.viewportGeometry(appliedViewportInsets)
    extents = null
    cachedViewport = null
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
    snapshot = null
    cachedProjection?.close()
    cachedProjection = null
  }

  fun captureRenderPadding() {
    renderedCameraPadding = geometry.padding
  }

  fun setInsets(padding: DpPadding): Boolean = insets.set(padding)

  /** Main only. Returns the jump that moves a new map to [requested], if any. */
  fun prepareCamera(requested: CameraPosition?): CameraUpdate? {
    snapshot = null
    return insets.prepareCamera(requested)?.let {
      CameraUpdate(camera = it.toCameraOptions(appliedViewportInsets))
    }
  }

  fun cameraForAssignment(position: CameraPosition): CameraPosition =
    insets.cameraForAssignment(position, hasViewport)

  /** On main, with a usable viewport. Uses the camera cached by the event drain. */
  fun applyInsets(beforeChange: () -> Unit): CameraUpdate? =
    insets
      .apply(beforeChange) { camera }
      ?.let { CameraUpdate(camera = CameraOptions(padding = it.toEdgeInsets())) }

  private fun viewportExtents(): MapViewportExtents =
    extents
      ?: MapViewportExtents(projection?.let { unprojectedCorners(it, size) } ?: EmptyCorners).also {
        extents = it
      }

  fun getVisibleBounds(): VisibleBounds = viewportExtents().bounds

  fun getVisibleRegion(): VisibleRegion = viewportExtents().region

  fun getViewport(): Viewport? {
    if (!hasViewport || size == DpSize.Zero) return null
    cachedViewport?.let {
      return it
    }
    val extents = viewportExtents()
    return Viewport(
        cameraPosition = camera,
        size = size,
        visibleBounds = extents.bounds,
        visibleRegion = extents.region,
        metersPerDpAtCenter = metersPerDpAtLatitude(camera.center.latitude),
      )
      .also { cachedViewport = it }
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
    projection?.pixelForLatLng(position.toLatLng())?.toDpOffset()

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
