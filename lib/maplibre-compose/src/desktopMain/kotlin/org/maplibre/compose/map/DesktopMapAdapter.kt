package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
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
import org.maplibre.kmp.native.map.MapCanvas
import org.maplibre.kmp.native.map.MapControls
import org.maplibre.kmp.native.map.MapLibreMap
import org.maplibre.kmp.native.map.MapLoadError
import org.maplibre.kmp.native.map.MapObserver
import org.maplibre.kmp.native.map.RenderFrameStatus
import org.maplibre.kmp.native.util.LatLng
import org.maplibre.kmp.native.util.Projection
import org.maplibre.kmp.native.util.ScreenCoordinate
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

internal class DesktopMapAdapter(internal var callbacks: MapAdapter.Callbacks) :
  MapAdapter, MapObserver, MapControls.Observer {

  internal lateinit var map: MapLibreMap
  internal lateinit var mapControls: MapControls

  /** Reference to the AWT canvas; used to read canvas dimensions for visible-region computation. */
  internal var canvas: MapCanvas? = null

  private var lastBaseStyle: BaseStyle? = null

  override fun onDidFinishLoadingMap() {
    callbacks.onMapFinishedLoading(this)
  }

  override fun onDidFailLoadingMap(error: MapLoadError, message: String) {
    callbacks.onMapFailLoading(message)
  }

  override fun onDidFinishLoadingStyle() {
    callbacks.onStyleChanged(this, DesktopStyle(map))
  }

  override fun onCameraWillChange(mode: CameraChangeMode) {
    // camera moves once on map initialization, before we have a map reference
    if (!::map.isInitialized) return
    val reason =
      if (map.isGestureInProgress) CameraMoveReason.GESTURE else CameraMoveReason.PROGRAMMATIC
    callbacks.onCameraMoveStarted(this, reason)
  }

  override fun onCameraIsChanging() {
    // only called during animated camera movement
    callbacks.onCameraMoved(this)
  }

  override fun onCameraDidChange(mode: CameraChangeMode) {
    if (!::map.isInitialized) return
    callbacks.onCameraMoved(this)
    callbacks.onCameraMoveEnded(this)
  }

  val frameTimer = TimeSource.Monotonic
  var lastFrameTime = frameTimer.markNow()

  override fun onDidFinishRenderingFrame(status: RenderFrameStatus) {
    val time = frameTimer.markNow()
    val duration = time - lastFrameTime
    lastFrameTime = time
    callbacks.onFrame(1.0 / duration.toDouble(DurationUnit.SECONDS))
  }

  override fun setBaseStyle(style: BaseStyle) {
    if (style == lastBaseStyle) return
    lastBaseStyle = style

    when (style) {
      is BaseStyle.Uri ->
        if (style.uri.startsWith("jar:file:")) {
          map.loadStyleJSON(URI(style.uri).toURL().readText())
        } else {
          map.loadStyleURL(style.uri)
        }
      is BaseStyle.Json -> map.loadStyleJSON(style.json)
    }

    // Signal that a style change is in progress — the new style will be delivered via
    // onDidFinishLoadingStyle() once it has loaded successfully.
    callbacks.onStyleChanged(this, null)
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

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    val cameraOptions =
      map.cameraForLatLngBounds(
        bounds = boundingBox.toMlnLatLngBounds(),
        padding = padding.toMlnEdgeInsets(LayoutDirection.Ltr),
        bearing = bearing,
        pitch = tilt,
      )

    map.jumpTo(cameraOptions)
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
    val c = canvas
    val w = c?.width?.toDouble() ?: 0.0
    val h = c?.height?.toDouble() ?: 0.0
    val topLeft = map.latLngForPixel(ScreenCoordinate(0.0, 0.0)).toPosition()
    val topRight = map.latLngForPixel(ScreenCoordinate(w, 0.0)).toPosition()
    val bottomLeft = map.latLngForPixel(ScreenCoordinate(0.0, h)).toPosition()
    val bottomRight = map.latLngForPixel(ScreenCoordinate(w, h)).toPosition()
    return VisibleRegion(
      farLeft = topLeft,
      farRight = topRight,
      nearLeft = bottomLeft,
      nearRight = bottomRight,
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
    mapControls.config = value.toMapControlsConfig()
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
  ): List<Feature<Geometry, JsonObject?>> {
    val layerIdsJson = layerIds?.toJsonArrayString()
    val resultJson = map.queryRenderedFeaturesAtPoint(offset.x.value, offset.y.value, layerIdsJson)
    return parseFeatures(resultJson)
  }

  override fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> {
    val layerIdsJson = layerIds?.toJsonArrayString()
    val resultJson = map.queryRenderedFeaturesInBox(
      rect.left.value, rect.top.value,
      rect.right.value, rect.bottom.value,
      layerIdsJson,
    )
    return parseFeatures(resultJson)
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

  override fun onMapPrimaryClick(coordinate: ScreenCoordinate) {
    val latLng = map.latLngForPixel(coordinate)
    val position = latLng.toPosition()
    val dpOffset = coordinate.toDpOffset()
    callbacks.onClick(this, position, dpOffset)
  }

  override fun onMapSecondaryClick(coordinate: ScreenCoordinate) {
    val latLng = map.latLngForPixel(coordinate)
    val position = latLng.toPosition()
    val dpOffset = coordinate.toDpOffset()
    callbacks.onLongClick(this, position, dpOffset)
  }

  companion object {
    private fun Set<String>.toJsonArrayString(): String =
      joinToString(",", prefix = "[", postfix = "]") { "\"${it.replace("\"", "\\\"")}\"" }

    @Suppress("TooGenericExceptionCaught")
    private fun parseFeatures(json: String): List<Feature<Geometry, JsonObject?>> {
      if (json.isBlank()) return emptyList()
      return try {
        val collection: FeatureCollection<Geometry, JsonObject?> = FeatureCollection.fromJson(json)
        collection.features ?: emptyList()
      } catch (e: Exception) {
        emptyList()
      }
    }
  }
}
