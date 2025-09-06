package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesktopStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toCameraPosition
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toMlnCameraOptions
import org.maplibre.compose.util.toMlnEdgeInsets
import org.maplibre.compose.util.toMlnLatLng
import org.maplibre.compose.util.toMlnLatLngBounds
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toScreenCoordinate
import org.maplibre.kmp.native.camera.CameraChangeMode
import org.maplibre.kmp.native.camera.CameraOptions
import org.maplibre.kmp.native.map.MapLibreMap
import org.maplibre.kmp.native.map.MapLoadError
import org.maplibre.kmp.native.map.MapObserver
import org.maplibre.kmp.native.util.LatLng
import org.maplibre.kmp.native.util.Projection

internal class DesktopMapAdapter(internal var callbacks: MapAdapter.Callbacks) :
  MapAdapter, MapObserver {

  internal lateinit var map: MapLibreMap

  private var lastBaseStyle: BaseStyle? = null

  override fun onDidFinishLoadingMap() {
    callbacks.onMapFinishedLoading(this)
  }

  override fun onDidFailLoadingMap(error: MapLoadError, message: String) {
    callbacks.onMapFailLoading(message)
  }

  override fun onCameraWillChange(mode: CameraChangeMode) {
    // TODO: Determine the reason for camera movement
    callbacks.onCameraMoveStarted(this, CameraMoveReason.UNKNOWN)
  }

  override fun onCameraIsChanging() {
    callbacks.onCameraMoved(this)
  }

  override fun onCameraDidChange(mode: CameraChangeMode) {
    callbacks.onCameraMoveEnded(this)
  }

  override fun setBaseStyle(style: BaseStyle) {
    if (style == lastBaseStyle) return
    lastBaseStyle = style

    when (style) {
      is BaseStyle.Uri -> map.loadStyleURL(style.uri)
      is BaseStyle.Json -> map.loadStyleJSON(style.json)
    }

    callbacks.onStyleChanged(this, DesktopStyle(map))
  }

  override fun getCameraPosition(): CameraPosition {
    return map.getCameraOptions().toCameraPosition()
  }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    map.jumpTo(
      CameraOptions.centered(
        center = LatLng(cameraPosition.target.latitude, cameraPosition.target.longitude),
        zoom = cameraPosition.zoom,
        bearing = cameraPosition.bearing,
        pitch = cameraPosition.tilt,
      )
    )
  }

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) {
    map.bounds = map.bounds.copy(bounds = boundingBox?.toMlnLatLngBounds())
  }

  override fun setMaxZoom(maxZoom: Double) {
    map.bounds = map.bounds.copy(maxZoom = maxZoom)
  }

  override fun setMinZoom(minZoom: Double) {
    map.bounds = map.bounds.copy(minZoom = minZoom)
  }

  override fun setMinPitch(minPitch: Double) {
    map.bounds = map.bounds.copy(minPitch = minPitch)
  }

  override fun setMaxPitch(maxPitch: Double) {
    map.bounds = map.bounds.copy(maxPitch = maxPitch)
  }

  override fun getVisibleBoundingBox(): BoundingBox {
    return map.latLngBoundsForCamera(map.getCameraOptions()).toBoundingBox()
  }

  override fun getVisibleRegion(): VisibleRegion {
    // TODO: get visible region
    return VisibleRegion(
      Position(0.0, 0.0),
      Position(0.0, 0.0),
      Position(0.0, 0.0),
      Position(0.0, 0.0),
    )
  }

  override fun setRenderSettings(value: RenderOptions) {
    map.debugOptions = value.debugOptions
    // TODO: FPS limit
  }

  override fun setOrnamentSettings(value: OrnamentOptions) {
    // No-op for desktop, as ornaments are not supported
  }

  override fun setGestureSettings(value: GestureOptions) {
    // TODO: gesture settings
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position {
    return map.latLngForPixel(offset.toScreenCoordinate()).toPosition()
  }

  override fun screenLocationFromPosition(position: Position): DpOffset {
    return map.pixelForLatLng(position.toMlnLatLng()).toDpOffset()
  }

  override fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> {
    // TODO: query rendered features at offset
    return emptyList()
  }

  override fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> {
    // TODO: query rendered features in rectangle
    return emptyList()
  }

  override fun metersPerDpAtLatitude(latitude: Double): Double {
    // TODO: does this need to be density scaled?
    return Projection.getMetersPerPixelAtLatitude(latitude, getCameraPosition().zoom)
  }

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    map.flyTo(
      finalPosition.toMlnCameraOptions(LayoutDirection.Ltr),
      duration.inWholeMilliseconds.toInt(),
    )
    try {
      delay(duration)
    } catch (e: CancellationException) {
      map.cancelTransitions()
      throw e
    }
  }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    val cameraOptions =
      map.cameraForLatLngBounds(
        bounds = boundingBox.toMlnLatLngBounds(),
        padding = padding.toMlnEdgeInsets(LayoutDirection.Ltr),
        bearing = bearing,
        pitch = tilt,
      )

    map.flyTo(cameraOptions, duration.inWholeMilliseconds.toInt())

    try {
      delay(duration)
    } catch (e: CancellationException) {
      map.cancelTransitions()
      throw e
    }
  }
}
