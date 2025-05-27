package dev.sargunv.maplibrecompose.core

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration

internal interface MaplibreMap {
  suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration)

  suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  )

  suspend fun asyncSetStyleUri(styleUri: String)

  suspend fun asyncSetDebugEnabled(enabled: Boolean)

  suspend fun asyncGetCameraPosition(): CameraPosition

  suspend fun asyncSetCameraPosition(cameraPosition: CameraPosition)

  suspend fun asyncSetMaxZoom(maxZoom: Double)

  suspend fun asyncSetMinZoom(minZoom: Double)

  suspend fun asyncSetMinPitch(minPitch: Double)

  suspend fun asyncSetMaxPitch(maxPitch: Double)

  suspend fun asyncGetVisibleBoundingBox(): BoundingBox

  suspend fun asyncGetVisibleRegion(): VisibleRegion

  suspend fun asyngSetMaximumFps(maximumFps: Int)

  suspend fun asyncSetOrnamentSettings(value: OrnamentSettings)

  suspend fun asyncSetGestureSettings(value: GestureSettings)

  suspend fun asyncGetPosFromScreenLocation(offset: DpOffset): Position

  suspend fun asyncGetScreenLocationFromPos(position: Position): DpOffset

  suspend fun asyncQueryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: CompiledExpression<BooleanValue>? = null,
  ): List<Feature>

  suspend fun asyncQueryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: CompiledExpression<BooleanValue>? = null,
  ): List<Feature>

  suspend fun asyncMetersPerDpAtLatitude(latitude: Double): Double

  interface Callbacks {
    fun onStyleChanged(map: MaplibreMap, style: Style?)

    fun onCameraMoveStarted(map: MaplibreMap, reason: CameraMoveReason)

    fun onCameraMoved(map: MaplibreMap)

    fun onCameraMoveEnded(map: MaplibreMap)

    fun onClick(map: MaplibreMap, latLng: Position, offset: DpOffset)

    fun onLongClick(map: MaplibreMap, latLng: Position, offset: DpOffset)

    fun onFrame(fps: Double)
  }
}

internal interface StandardMaplibreMap : MaplibreMap {
  override suspend fun asyncSetStyleUri(styleUri: String) = setStyleUri(styleUri)

  override suspend fun asyncSetDebugEnabled(enabled: Boolean) = setDebugEnabled(enabled)

  override suspend fun asyncGetCameraPosition(): CameraPosition = getCameraPosition()

  override suspend fun asyncSetCameraPosition(cameraPosition: CameraPosition) =
    setCameraPosition(cameraPosition)

  override suspend fun asyncSetMaxZoom(maxZoom: Double) = setMaxZoom(maxZoom)

  override suspend fun asyncSetMinZoom(minZoom: Double) = setMinZoom(minZoom)

  override suspend fun asyncSetMinPitch(minPitch: Double) = setMinPitch(minPitch)

  override suspend fun asyncSetMaxPitch(maxPitch: Double) = setMaxPitch(maxPitch)

  override suspend fun asyncGetVisibleBoundingBox(): BoundingBox = getVisibleBoundingBox()

  override suspend fun asyncGetVisibleRegion(): VisibleRegion = getVisibleRegion()

  override suspend fun asyngSetMaximumFps(maximumFps: Int) = setMaximumFps(maximumFps)

  override suspend fun asyncSetOrnamentSettings(value: OrnamentSettings) =
    setOrnamentSettings(value)

  override suspend fun asyncSetGestureSettings(value: GestureSettings) = setGestureSettings(value)

  override suspend fun asyncGetPosFromScreenLocation(offset: DpOffset): Position =
    positionFromScreenLocation(offset)

  override suspend fun asyncGetScreenLocationFromPos(position: Position): DpOffset =
    screenLocationFromPosition(position)

  override suspend fun asyncQueryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> = queryRenderedFeatures(offset, layerIds, predicate)

  override suspend fun asyncQueryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> = queryRenderedFeatures(rect, layerIds, predicate)

  override suspend fun asyncMetersPerDpAtLatitude(latitude: Double): Double =
    metersPerDpAtLatitude(latitude)

  fun setStyleUri(styleUri: String)

  fun setDebugEnabled(enabled: Boolean)

  fun getCameraPosition(): CameraPosition

  fun setCameraPosition(cameraPosition: CameraPosition)

  fun setMaxZoom(maxZoom: Double)

  fun setMinZoom(minZoom: Double)

  fun setMinPitch(minPitch: Double)

  fun setMaxPitch(maxPitch: Double)

  fun getVisibleBoundingBox(): BoundingBox

  fun getVisibleRegion(): VisibleRegion

  fun setMaximumFps(maximumFps: Int)

  fun setOrnamentSettings(value: OrnamentSettings)

  fun setGestureSettings(value: GestureSettings)

  fun positionFromScreenLocation(offset: DpOffset): Position

  fun screenLocationFromPosition(position: Position): DpOffset

  fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: CompiledExpression<BooleanValue>? = null,
  ): List<Feature>

  fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: CompiledExpression<BooleanValue>? = null,
  ): List<Feature>

  fun metersPerDpAtLatitude(latitude: Double): Double
}
