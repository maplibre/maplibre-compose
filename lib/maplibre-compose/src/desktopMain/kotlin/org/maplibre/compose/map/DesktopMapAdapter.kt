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
import org.maplibre.compose.style.DesktopStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.kmp.native.map.MapLibreMap

internal class DesktopMapAdapter(
  internal val map: MapLibreMap,
  internal var callbacks: MapAdapter.Callbacks,
) : MapAdapter {

  private var lastBaseStyle: BaseStyle? = null

  override fun setBaseStyle(style: BaseStyle) {
    if (style == lastBaseStyle) return
    lastBaseStyle = style
    callbacks.onStyleChanged(this, null)

    when (style) {
      is BaseStyle.Uri -> map.loadStyleURL(style.uri)
      is BaseStyle.Json -> map.loadStyleJSON(style.json)
    }

    callbacks.onStyleChanged(this, DesktopStyle(map))
  }

  override fun getCameraPosition(): CameraPosition {
    TODO("get camera position")
  }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    // TODO: jumpTo
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
    TODO("get visible bounding box")
  }

  override fun getVisibleRegion(): VisibleRegion {
    TODO("get visible region")
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
    TODO("get position from screen location")
  }

  override fun screenLocationFromPosition(position: Position): DpOffset {
    TODO("get screen location from position")
  }

  override fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> {
    TODO("query rendered features at offset")
  }

  override fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> {
    TODO("query rendered features in rect")
  }

  override fun metersPerDpAtLatitude(latitude: Double): Double {
    TODO("get map scale")
  }

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    // TODO: flyTo position
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
