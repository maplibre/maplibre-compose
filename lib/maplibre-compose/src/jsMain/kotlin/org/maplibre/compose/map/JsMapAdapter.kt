package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.JsStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toControlPosition
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.compose.util.toLngLat
import org.maplibre.compose.util.toPaddingOptions
import org.maplibre.compose.util.toPaddingValuesAbsolute
import org.maplibre.compose.util.toPoint
import org.maplibre.compose.util.toPosition
import org.maplibre.kmp.js.controls.AttributionControl
import org.maplibre.kmp.js.controls.FullscreenControl
import org.maplibre.kmp.js.controls.GeolocateControl
import org.maplibre.kmp.js.controls.GlobeControl
import org.maplibre.kmp.js.controls.LogoControl
import org.maplibre.kmp.js.controls.NavigationControl
import org.maplibre.kmp.js.controls.NavigationControlOptions
import org.maplibre.kmp.js.controls.ScaleControl
import org.maplibre.kmp.js.event.MapLibreEvent
import org.maplibre.kmp.js.event.MapMouseEvent
import org.maplibre.kmp.js.geometry.LngLat
import org.maplibre.kmp.js.geometry.Point
import org.maplibre.kmp.js.map.EaseToOptions
import org.maplibre.kmp.js.map.FitBoundsOptions
import org.maplibre.kmp.js.map.JumpToOptions
import org.maplibre.kmp.js.map.Map
import org.maplibre.kmp.js.map.MapOptions
import org.maplibre.kmp.js.map.QueryRenderedFeaturesOptions
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import org.w3c.dom.HTMLElement

internal class JsMapAdapter(
  parent: HTMLElement,
  internal var layoutDir: LayoutDirection,
  internal var density: Density,
  internal var callbacks: MapAdapter.Callbacks,
  internal var logger: Logger?,
) : MapAdapter {
  private val impl = Map(MapOptions(parent, disableAttributionControl = true))

  val timeSource = TimeSource.Monotonic
  var lastFrameTime = timeSource.markNow()

  init {
    impl.on("render") {
      val time = timeSource.markNow()
      val duration = time - lastFrameTime
      lastFrameTime = time
      callbacks.onFrame(1.0 / duration.toDouble(DurationUnit.SECONDS))
    }

    impl.on("move") { callbacks.onCameraMoved(this) }

    impl.on("moveend") { callbacks.onCameraMoveEnded(this) }

    impl.on("movestart") {
      val event = it.unsafeCast<MapLibreEvent<Any?>>()
      if (event.originalEvent != null) callbacks.onCameraMoveStarted(this, CameraMoveReason.GESTURE)
      else callbacks.onCameraMoveStarted(this, CameraMoveReason.PROGRAMMATIC)
    }

    impl.on("click") {
      val event = it.unsafeCast<MapMouseEvent>()
      callbacks.onClick(this, event.lngLat.toPosition(), event.point.toDpOffset())
    }

    impl.on("contextmenu") {
      val event = it.unsafeCast<MapMouseEvent>()
      callbacks.onLongClick(this, event.lngLat.toPosition(), event.point.toDpOffset())
    }
  }

  fun resize() {
    impl.resize()
  }

  private var lastBaseStyle: BaseStyle? = null

  override fun setBaseStyle(style: BaseStyle) {
    if (style == lastBaseStyle) return
    lastBaseStyle = style
    when (style) {
      is BaseStyle.Json -> impl.setStyle(JSON.parse(style.json))
      is BaseStyle.Uri -> impl.setStyle(style.uri)
    }
    callbacks.onStyleChanged(this, JsStyle(impl))
  }

  override fun setMinPitch(minPitch: Double) {
    impl.setMinPitch(minPitch)
  }

  override fun setMaxPitch(maxPitch: Double) {
    impl.setMaxPitch(maxPitch)
  }

  override fun setMinZoom(minZoom: Double) {
    impl.setMinZoom(minZoom)
  }

  override fun setMaxZoom(maxZoom: Double) {
    impl.setMaxZoom(maxZoom)
  }

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) {
    impl.setMaxBounds(
      boundingBox?.let {
        arrayOf(doubleArrayOf(it.west, it.south), doubleArrayOf(it.east, it.north))
      }
    )
  }

  override fun getVisibleBoundingBox(): BoundingBox {
    return impl.getBounds().toBoundingBox()
  }

  override fun getVisibleRegion(): VisibleRegion {
    val rect = impl.getCanvas().getBoundingClientRect()
    return VisibleRegion(
      farLeft = impl.unproject(Point(rect.left, rect.top)).toPosition(),
      farRight = impl.unproject(Point(rect.right, rect.top)).toPosition(),
      nearLeft = impl.unproject(Point(rect.left, rect.bottom)).toPosition(),
      nearRight = impl.unproject(Point(rect.right, rect.bottom)).toPosition(),
    )
  }

  override fun setRenderSettings(value: RenderOptions) {
    with(value.debugSettings) {
      impl.showCollisionBoxes = showCollisionBoxes
      impl.showPadding = showPadding
      impl.showTileBoundaries = showTileBoundaries
      impl.showOverdrawInspector = showOverdrawInspector
    }
  }

  override fun setGestureSettings(value: GestureOptions) {
    if (value.isTouchPitchEnabled) {
      impl.touchPitch.enable()
    } else {
      impl.touchPitch.disable()
    }

    if (value.isDragRotateEnabled) {
      impl.dragRotate.enable()
    } else {
      impl.dragRotate.disable()
    }

    if (value.isDragPanEnabled) {
      impl.dragPan.enable()
    } else {
      impl.dragPan.disable()
    }

    if (value.isDoubleClickZoomEnabled) {
      impl.doubleClickZoom.enable()
    } else {
      impl.doubleClickZoom.disable()
    }

    if (value.isScrollZoomEnabled) {
      impl.scrollZoom.enable()
    } else {
      impl.scrollZoom.disable()
    }

    when (value.touchZoomRotateMode) {
      GestureOptions.ZoomRotateMode.Disabled -> {
        impl.touchZoomRotate.disableRotation()
        impl.touchZoomRotate.disable()
      }
      GestureOptions.ZoomRotateMode.ZoomOnly -> {
        impl.touchZoomRotate.enable()
        impl.touchZoomRotate.disableRotation()
      }
      GestureOptions.ZoomRotateMode.RotateAndZoom -> {
        impl.touchZoomRotate.enableRotation()
        impl.touchZoomRotate.enable()
      }
    }

    when (value.keyboardZoomRotateMode) {
      GestureOptions.ZoomRotateMode.Disabled -> {
        impl.keyboard.disableRotation()
        impl.keyboard.disable()
      }
      GestureOptions.ZoomRotateMode.ZoomOnly -> {
        impl.keyboard.enable()
        impl.keyboard.disableRotation()
      }
      GestureOptions.ZoomRotateMode.RotateAndZoom -> {
        impl.keyboard.enableRotation()
        impl.keyboard.enable()
      }
    }
  }

  private var compassPosition: String? = null
  private var logoPosition: String? = null
  private var scalePosition: String? = null
  private var attributionPosition: String? = null
  private var globeButtonPosition: String? = null
  private var fullscreenButtonPosition: String? = null
  private var geolocateButtonPosition: String? = null
  private val navigationControl = NavigationControl(NavigationControlOptions(visualizePitch = true))
  private val logoControl = LogoControl()
  private val scaleControl = ScaleControl()
  private val attributionControl = AttributionControl()
  private val globeControl = GlobeControl()
  private val fullscreenControl = FullscreenControl()
  private val geolocateControl = GeolocateControl()

  override fun setOrnamentSettings(value: OrnamentOptions) {
    val desiredCompassPosition =
      if (value.isNavigationEnabled) value.navigationAlignment.toControlPosition(layoutDir)
      else null

    if (compassPosition != desiredCompassPosition) {
      if (desiredCompassPosition == null) impl.removeControl(navigationControl)
      else impl.addControl(navigationControl, desiredCompassPosition)
      compassPosition = desiredCompassPosition
    }

    val desiredLogoPosition =
      if (value.isLogoEnabled) value.logoAlignment.toControlPosition(layoutDir) else null

    if (logoPosition != desiredLogoPosition) {
      if (desiredLogoPosition == null) impl.removeControl(logoControl)
      else impl.addControl(logoControl, desiredLogoPosition)
      logoPosition = desiredLogoPosition
    }

    val desiredScalePosition =
      if (value.isScaleBarEnabled) value.scaleBarAlignment.toControlPosition(layoutDir) else null

    if (scalePosition != desiredScalePosition) {
      if (desiredScalePosition == null) impl.removeControl(scaleControl)
      else impl.addControl(scaleControl, desiredScalePosition)
      scalePosition = desiredScalePosition
    }

    val desiredAttributionPosition =
      if (value.isAttributionEnabled) value.attributionAlignment.toControlPosition(layoutDir)
      else null

    if (attributionPosition != desiredAttributionPosition) {
      if (desiredAttributionPosition == null) impl.removeControl(attributionControl)
      else impl.addControl(attributionControl, desiredAttributionPosition)
      attributionPosition = desiredAttributionPosition
    }

    val desiredGlobeButtonPosition =
      if (value.isGlobeButtonEnabled) value.globeButtonAlignment.toControlPosition(layoutDir)
      else null

    if (globeButtonPosition != desiredGlobeButtonPosition) {
      if (desiredGlobeButtonPosition == null) impl.removeControl(globeControl)
      else impl.addControl(globeControl, desiredGlobeButtonPosition)
      globeButtonPosition = desiredGlobeButtonPosition
    }

    val desiredFullscreenButtonPosition =
      if (value.isFullscreenButtonEnabled)
        value.fullscreenButtonAlignment.toControlPosition(layoutDir)
      else null

    if (fullscreenButtonPosition != desiredFullscreenButtonPosition) {
      if (desiredFullscreenButtonPosition == null) impl.removeControl(fullscreenControl)
      else impl.addControl(fullscreenControl, desiredFullscreenButtonPosition)
      fullscreenButtonPosition = desiredFullscreenButtonPosition
    }

    val desiredGeolocateButtonPosition =
      if (value.isGeolocateButtonEnabled)
        value.geolocateButtonAlignment.toControlPosition(layoutDir)
      else null

    if (geolocateButtonPosition != desiredGeolocateButtonPosition) {
      if (desiredGeolocateButtonPosition == null) impl.removeControl(geolocateControl)
      else impl.addControl(geolocateControl, desiredGeolocateButtonPosition)
      geolocateButtonPosition = desiredGeolocateButtonPosition
    }
  }

  override fun getCameraPosition(): CameraPosition {
    return CameraPosition(
      bearing = impl.getBearing(),
      target = impl.getCenter().toPosition(),
      tilt = impl.getPitch(),
      zoom = impl.getZoom(),
      padding = impl.getPadding().toPaddingValuesAbsolute(),
    )
  }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    impl.jumpTo(
      JumpToOptions(
        center = cameraPosition.target.toLngLat(),
        zoom = cameraPosition.zoom,
        bearing = cameraPosition.bearing,
        pitch = cameraPosition.tilt,
        padding = cameraPosition.padding.toPaddingOptions(layoutDir),
      )
    )
  }

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    impl.easeTo(
      EaseToOptions(
        center = finalPosition.target.toLngLat(),
        zoom = finalPosition.zoom,
        bearing = finalPosition.bearing,
        pitch = finalPosition.tilt,
        padding = finalPosition.padding.toPaddingOptions(layoutDir),
        duration = duration.toDouble(DurationUnit.MILLISECONDS),
        easing = { t -> t },
      )
    )
  }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    impl.fitBounds(
      bounds = boundingBox.toLatLngBounds(),
      options =
        FitBoundsOptions(
          linear = true,
          bearing = bearing,
          pitch = tilt,
          padding = padding.toPaddingOptions(layoutDir),
        ),
    )
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position {
    return impl.unproject(offset.toPoint()).toPosition()
  }

  override fun screenLocationFromPosition(position: Position): DpOffset {
    return impl.project(position.toLngLat()).toDpOffset()
  }

  override fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> {
    return impl
      .queryRenderedFeatures(
        offset.toPoint(),
        QueryRenderedFeaturesOptions(
          layers = layerIds?.toTypedArray(),
          filter = null, // TODO
        ),
      )
      .map { Feature.fromJson(JSON.stringify(it)) }
  }

  override fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> {
    return impl
      .queryRenderedFeatures(
        arrayOf(
          Point(rect.left.value.toDouble(), rect.bottom.value.toDouble()),
          Point(rect.right.value.toDouble(), rect.top.value.toDouble()),
        ),
        QueryRenderedFeaturesOptions(
          layers = layerIds?.toTypedArray(),
          filter = null, // TODO
        ),
      )
      .map { Feature.fromJson(JSON.stringify(it)) }
  }

  override fun metersPerDpAtLatitude(latitude: Double): Double {
    val point = impl.project(LngLat(impl.getCenter().lng, latitude))
    return impl.unproject(point).distanceTo(impl.unproject(Point(point.x + 1, point.y)))
  }
}
