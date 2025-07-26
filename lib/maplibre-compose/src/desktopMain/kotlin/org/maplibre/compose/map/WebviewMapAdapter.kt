package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toControlPosition

internal class WebviewMapAdapter(private val bridge: WebviewBridge) : MapAdapter {
  suspend fun init() {
    bridge.callVoid("init")
  }

  override suspend fun asyncSetBaseStyle(style: BaseStyle) {
    when (style) {
      is BaseStyle.Uri -> bridge.callVoid("setStyleUri", style.uri)
      is BaseStyle.Json -> bridge.callVoid("setStyleJson", style.json)
    }
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

  override suspend fun asyncSetCameraBoundingBox(boundingBox: BoundingBox?) {
    bridge.callVoid(
      "setMaxBounds",
      boundingBox?.let { arrayOf(it.southwest.coordinates, it.northeast.coordinates) },
    )
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

  override suspend fun asyncSetRenderSettings(value: RenderOptions) {
    with(value.debugSettings) {
      bridge.callVoid("setShowCollisionBoxes", showCollisionBoxes)
      bridge.callVoid("setShowPadding", showPadding)
      bridge.callVoid("setShowTileBoundaries", showTileBoundaries)
      bridge.callVoid("setShowOverdrawInspector", showOverdrawInspector)
    }
  }

  private var compassPosition: String? = null
  private var logoPosition: String? = null
  private var scalePosition: String? = null
  private var attributionPosition: String? = null

  override suspend fun asyncSetOrnamentSettings(value: OrnamentOptions) {
    val layoutDir = LayoutDirection.Ltr // TODO: Get from composition

    val desiredCompassPosition =
      if (value.isNavigationEnabled) value.navigationAlignment.toControlPosition(layoutDir)
      else null
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

  override suspend fun asyncSetGestureSettings(value: GestureOptions) {
    bridge.callVoid("setTouchPitchEnabled", value.isTouchPitchEnabled)
    bridge.callVoid("setDragRotateEnabled", value.isDragRotateEnabled)
    bridge.callVoid("setDragPanEnabled", value.isDragPanEnabled)
    bridge.callVoid("setDoubleClickZoomEnabled", value.isDoubleClickZoomEnabled)
    bridge.callVoid("setScrollZoomEnabled", value.isScrollZoomEnabled)
    bridge.callVoid("setTouchZoomRotateMode", value.touchZoomRotateMode.name)
    bridge.callVoid("setKeyboardZoomRotateMode", value.keyboardZoomRotateMode.name)
  }

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
