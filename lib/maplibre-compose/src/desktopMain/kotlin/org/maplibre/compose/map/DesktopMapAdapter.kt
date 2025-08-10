package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.VisibleRegion

internal class DesktopMapAdapter : MapAdapter {
  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) =
    Unit

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) = Unit

  override fun setBaseStyle(style: BaseStyle) {}

  override fun getCameraPosition(): CameraPosition {
    TODO()
  }

  override fun setCameraPosition(cameraPosition: CameraPosition) {}

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) {}

  override fun setMaxZoom(maxZoom: Double) {}

  override fun setMinZoom(minZoom: Double) {}

  override fun setMinPitch(minPitch: Double) {}

  override fun setMaxPitch(maxPitch: Double) {}

  override fun getVisibleBoundingBox(): BoundingBox {
    TODO()
  }

  override fun getVisibleRegion(): VisibleRegion {
    TODO()
  }

  override fun setRenderSettings(value: RenderOptions) {}

  override fun setOrnamentSettings(value: OrnamentOptions) {}

  override fun setGestureSettings(value: GestureOptions) {}

  override fun positionFromScreenLocation(offset: DpOffset): Position {
    TODO()
  }

  override fun screenLocationFromPosition(position: Position): DpOffset {
    TODO()
  }

  override fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> {
    TODO()
  }

  override fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> {
    TODO()
  }

  override fun metersPerDpAtLatitude(latitude: Double): Double {
    TODO()
  }
}
