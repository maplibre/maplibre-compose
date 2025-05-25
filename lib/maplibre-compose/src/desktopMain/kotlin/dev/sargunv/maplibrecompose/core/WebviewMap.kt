package dev.sargunv.maplibrecompose.core

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration

internal class WebviewMap(private val bridge: WebviewBridge) : MaplibreMap {
  suspend fun init() {
    bridge.callVoid("init")
  }

  override suspend fun asyncSetStyleUri(styleUri: String) {
    bridge.callVoid("setStyleUri", styleUri)
  }

  override suspend fun asyncSetDebugEnabled(enabled: Boolean) {
    bridge.callVoid("setDebugEnabled", enabled)
  }

  override suspend fun asyncGetCameraPosition(): CameraPosition {
    return CameraPosition()
  }

  override suspend fun asyncSetCameraPosition(cameraPosition: CameraPosition) {}

  override suspend fun asyncSetMaxZoom(maxZoom: Double) {
    bridge.callVoid("setMaxZoom", maxZoom)
  }

  override suspend fun asyncSetMinZoom(minZoom: Double) {
    bridge.callVoid("setMinZoom", minZoom)
  }

  override suspend fun asyncSetMinPitch(minPitch: Double) {
    bridge.callVoid("setMinPitch", minPitch)
  }

  override suspend fun asyncSetMaxPitch(maxPitch: Double) {
    bridge.callVoid("setMaxPitch", maxPitch)
  }

  override suspend fun asyncGetVisibleBoundingBox(): BoundingBox {
    return BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0))
  }

  override suspend fun asyncGetVisibleRegion(): VisibleRegion {
    return VisibleRegion(
      Position(0.0, 0.0),
      Position(0.0, 0.0),
      Position(0.0, 0.0),
      Position(0.0, 0.0),
    )
  }

  override suspend fun asyngSetMaximumFps(maximumFps: Int) {
    // Not supported on web
  }

  private var compassPosition: String? = null
  private var logoPosition: String? = null
  private var scalePosition: String? = null
  private var attributionPosition: String? = null

  override suspend fun asyncSetOrnamentSettings(value: OrnamentSettings) {
    val layoutDir = LayoutDirection.Ltr // TODO: Get from composition

    val desiredCompassPosition =
      if (value.isCompassEnabled) value.compassAlignment.toControlPosition(layoutDir) else null
    val desiredLogoPosition =
      if (value.isLogoEnabled) value.logoAlignment.toControlPosition(layoutDir) else null
    val desiredScalePosition =
      if (value.isScaleBarEnabled) value.scaleBarAlignment.toControlPosition(layoutDir) else null
    val desiredAttributionPosition =
      if (value.isAttributionEnabled) value.attributionAlignment.toControlPosition(layoutDir)
      else null

    if (compassPosition != desiredCompassPosition) {
      if (desiredCompassPosition == null) bridge.callVoid("removeCompassControl")
      else bridge.callVoid("addNavigationControl", desiredCompassPosition)
      compassPosition = desiredCompassPosition
    }

    if (logoPosition != desiredLogoPosition) {
      if (desiredLogoPosition == null) bridge.callVoid("removeLogoControl")
      else bridge.callVoid("addLogoControl", desiredLogoPosition)
      logoPosition = desiredLogoPosition
    }

    if (scalePosition != desiredScalePosition) {
      if (desiredScalePosition == null) bridge.callVoid("removeScaleControl")
      else bridge.callVoid("addScaleControl", desiredScalePosition)
      scalePosition = desiredScalePosition
    }

    if (attributionPosition != desiredAttributionPosition) {
      if (desiredAttributionPosition == null) bridge.callVoid("removeAttributionControl")
      else bridge.callVoid("addAttributionControl", desiredAttributionPosition)
      attributionPosition = desiredAttributionPosition
    }
  }

  override suspend fun asyncSetGestureSettings(value: GestureSettings) {
    bridge.callVoid("setTiltGesturesEnabled", value.isTiltGesturesEnabled)
    bridge.callVoid("setZoomGesturesEnabled", value.isZoomGesturesEnabled)
    bridge.callVoid("setRotateGesturesEnabled", value.isRotateGesturesEnabled)
    bridge.callVoid("setScrollGesturesEnabled", value.isScrollGesturesEnabled)
    bridge.callVoid("setKeyboardGesturesEnabled", value.isKeyboardGesturesEnabled)
  }

  override fun getStyleUri() = "" // todo

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {}

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {}

  override suspend fun asyncGetPosFromScreenLocation(offset: DpOffset): Position {
    return Position(0.0, 0.0)
  }

  override suspend fun asyncGetScreenLocationFromPos(position: Position): DpOffset {
    return DpOffset.Zero
  }

  override suspend fun asyncQueryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> {
    return emptyList()
  }

  override suspend fun asyncQueryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature> {
    return emptyList()
  }

  override suspend fun asyncMetersPerDpAtLatitude(latitude: Double): Double {
    return 0.0
  }
}
