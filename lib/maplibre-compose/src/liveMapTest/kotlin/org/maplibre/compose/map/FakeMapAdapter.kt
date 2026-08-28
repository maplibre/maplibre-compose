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
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** Records every adapter call by name, in place of a render session. */
internal class FakeMapAdapter : MapAdapter {
  val calls: MutableList<String> = mutableListOf()
  var camera: CameraPosition = CameraPosition()
  var reportedViewport: Viewport? = null

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    calls += "animateCameraPosition"
    camera = finalPosition
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

  override fun setBaseStyle(style: BaseStyle, generation: Long) {
    calls += "setBaseStyle"
  }

  override fun getCameraPosition(): CameraPosition {
    calls += "getCameraPosition"
    return camera
  }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    calls += "setCameraPosition"
    camera = cameraPosition
  }

  override fun setCameraPadding(padding: PaddingValues) {
    calls += "setCameraPadding"
  }

  override suspend fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    calls += "fitCameraPosition"
    camera = CameraPosition(target = boundingBox.northeast, bearing = bearing, tilt = tilt)
  }

  override fun setCameraConstraints(value: CameraConstraints) {
    calls += "setCameraConstraints"
  }

  override fun getViewport(): Viewport? {
    calls += "getViewport"
    return reportedViewport
  }

  override fun setRenderSettings(value: RenderOptions) {
    calls += "setRenderSettings"
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
}
