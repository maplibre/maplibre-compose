package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesktopStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.kmp.native.camera.CameraChangeMode
import org.maplibre.kmp.native.camera.CameraOptions
import org.maplibre.kmp.native.map.MapLibreMap
import org.maplibre.kmp.native.map.MapLoadError
import org.maplibre.kmp.native.map.MapObserver
import org.maplibre.kmp.native.util.LatLng

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
    // TODO: get camera position
    return CameraPosition()
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
    // TODO: bounds
  }

  override fun setMaxZoom(maxZoom: Double) {
    // TODO: bounds
  }

  override fun setMinZoom(minZoom: Double) {
    // TODO: bounds
  }

  override fun setMinPitch(minPitch: Double) {
    // TODO: bounds
  }

  override fun setMaxPitch(maxPitch: Double) {
    // TODO: bounds
  }

  override fun getVisibleBoundingBox(): BoundingBox {
    // TODO: get visible bounding box
    return BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0))
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
    // TODO: get position from screen location
    return Position(0.0, 0.0)
  }

  override fun screenLocationFromPosition(position: Position): DpOffset {
    // TODO: get screen location from position
    return DpOffset(0.dp, 0.dp)
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
    // TODO: calculate meters per dp at latitude
    return 1.0
  }

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    map.flyTo(
      CameraOptions.centered(
        center = LatLng(finalPosition.target.latitude, finalPosition.target.longitude),
        zoom = finalPosition.zoom,
        bearing = finalPosition.bearing,
        pitch = finalPosition.tilt,
      ),
      duration.inWholeMilliseconds.toInt(),
    )
    // TODO: handle cancellation somehow?
    delay(duration)
  }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    // TODO: flyTo bounding box
  }
}
