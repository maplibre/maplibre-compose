package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Style
import org.maplibre.compose.util.VisibleRegion

internal interface MapAdapter {
  suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration)

  suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  )

  fun setBaseStyle(style: BaseStyle)

  fun getCameraPosition(): CameraPosition

  fun setCameraPosition(cameraPosition: CameraPosition)

  fun setCameraBoundingBox(boundingBox: BoundingBox?)

  fun setMaxZoom(maxZoom: Double)

  fun setMinZoom(minZoom: Double)

  fun setMinPitch(minPitch: Double)

  fun setMaxPitch(maxPitch: Double)

  fun getVisibleBoundingBox(): BoundingBox

  fun getVisibleRegion(): VisibleRegion

  fun setRenderSettings(value: RenderOptions)

  fun setOrnamentSettings(value: OrnamentOptions)

  fun setGestureSettings(value: GestureOptions)

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

  interface Callbacks {
    fun onStyleChanged(map: MapAdapter, style: Style?)

    fun onMapFinishedLoading(map: MapAdapter)

    fun onMapFailLoading(reason: String?)

    fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason)

    fun onCameraMoved(map: MapAdapter)

    fun onCameraMoveEnded(map: MapAdapter)

    fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset)

    fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset)

    fun onFrame(fps: Double)
  }
}
