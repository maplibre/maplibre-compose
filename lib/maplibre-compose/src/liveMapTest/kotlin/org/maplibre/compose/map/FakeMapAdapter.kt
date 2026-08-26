package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** Records every adapter call by name, standing in for a render session. */
internal class FakeMapAdapter : MapAdapter {
  val calls: MutableList<String> = mutableListOf()

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    calls += "animateCameraPosition"
  }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    calls += "animateCameraPosition"
  }

  override fun setBaseStyle(style: BaseStyle) {
    calls += "setBaseStyle"
  }

  override fun getCameraPosition(): CameraPosition {
    calls += "getCameraPosition"
    return CameraPosition()
  }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    calls += "setCameraPosition"
  }

  override fun setCameraPadding(padding: PaddingValues) {
    calls += "setCameraPadding"
  }

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    calls += "setCameraPosition"
  }

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) {
    calls += "setCameraBoundingBox"
  }

  override fun setMaxZoom(maxZoom: Double) {
    calls += "setMaxZoom"
  }

  override fun setMinZoom(minZoom: Double) {
    calls += "setMinZoom"
  }

  override fun setMinPitch(minPitch: Double) {
    calls += "setMinPitch"
  }

  override fun setMaxPitch(maxPitch: Double) {
    calls += "setMaxPitch"
  }

  override fun getVisibleBoundingBox(): BoundingBox = error("unused in these tests")

  override fun getVisibleRegion(): VisibleRegion = error("unused in these tests")

  override fun getViewport(): Viewport? {
    calls += "getViewport"
    return null
  }

  override fun setRenderSettings(value: RenderOptions) {
    calls += "setRenderSettings"
  }

  override fun setGestureSettings(value: GestureOptions) {
    calls += "setGestureSettings"
  }

  override fun setTileLodSettings(value: TileLodOptions) {
    calls += "setTileLodSettings"
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position? = null

  override fun screenLocationFromPosition(position: Position): DpOffset? = null

  override suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = emptyList()

  override suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = emptyList()

  override fun metersPerDpAtLatitude(latitude: Double): Double = 1.0
}
