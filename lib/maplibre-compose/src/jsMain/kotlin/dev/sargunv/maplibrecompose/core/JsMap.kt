package dev.sargunv.maplibrecompose.core

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import dev.sargunv.maplibrecompose.core.util.toBoundingBox
import dev.sargunv.maplibrecompose.core.util.toControlPosition
import dev.sargunv.maplibrecompose.core.util.toDpOffset
import dev.sargunv.maplibrecompose.core.util.toLatLngBounds
import dev.sargunv.maplibrecompose.core.util.toLngLat
import dev.sargunv.maplibrecompose.core.util.toPaddingOptions
import dev.sargunv.maplibrecompose.core.util.toPaddingValuesAbsolute
import dev.sargunv.maplibrecompose.core.util.toPoint
import dev.sargunv.maplibrecompose.core.util.toPosition
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import dev.sargunv.maplibrejs.AttributionControl
import dev.sargunv.maplibrejs.EaseToOptions
import dev.sargunv.maplibrejs.FitBoundsOptions
import dev.sargunv.maplibrejs.JumpToOptions
import dev.sargunv.maplibrejs.LngLat
import dev.sargunv.maplibrejs.LogoControl
import dev.sargunv.maplibrejs.Map
import dev.sargunv.maplibrejs.MapLibreEvent
import dev.sargunv.maplibrejs.MapMouseEvent
import dev.sargunv.maplibrejs.MapOptions
import dev.sargunv.maplibrejs.NavigationControl
import dev.sargunv.maplibrejs.NavigationControlOptions
import dev.sargunv.maplibrejs.Point
import dev.sargunv.maplibrejs.QueryRenderedFeaturesOptions
import dev.sargunv.maplibrejs.ScaleControl
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import org.w3c.dom.HTMLElement

internal class JsMap(
  parent: HTMLElement,
  internal var layoutDir: LayoutDirection,
  internal var density: Density,
  internal var callbacks: MaplibreMap.Callbacks,
  internal var logger: Logger?,
) : StandardMaplibreMap {
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

  private var lastStyleUri: String = ""

  override fun setStyleUri(styleUri: String) {
    if (styleUri == lastStyleUri) return
    lastStyleUri = styleUri
    impl.setStyle(styleUri)
    callbacks.onStyleChanged(this, JsStyle(impl))
  }

  override fun setDebugEnabled(enabled: Boolean) {
    impl.showCollisionBoxes = enabled
    impl.showPadding = enabled
    impl.showTileBoundaries = enabled
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

  override fun setMaximumFps(maximumFps: Int) {
    // not supported on web
  }

  override fun setGestureSettings(value: GestureSettings) {
    if (value.isTiltGesturesEnabled) {
      impl.touchPitch.enable()
    } else {
      impl.touchPitch.disable()
    }

    if (value.isRotateGesturesEnabled) {
      impl.dragRotate.enable()
      impl.keyboard.enableRotation()
      impl.touchZoomRotate.enableRotation()
    } else {
      impl.dragRotate.disable()
      impl.keyboard.disableRotation()
      impl.touchZoomRotate.disableRotation()
    }

    if (value.isScrollGesturesEnabled) {
      impl.dragPan.enable()
    } else {
      impl.dragPan.disable()
    }

    if (value.isZoomGesturesEnabled) {
      impl.doubleClickZoom.enable()
      impl.scrollZoom.enable()
      impl.touchZoomRotate.enable()
    } else {
      impl.doubleClickZoom.disable()
      impl.scrollZoom.disable()
      impl.touchZoomRotate.disable()
    }

    if (value.isKeyboardGesturesEnabled) {
      impl.keyboard.enable()
    } else {
      impl.keyboard.disable()
    }
  }

  private var compassPosition: String? = null
  private var logoPosition: String? = null
  private var scalePosition: String? = null
  private var attributionPosition: String? = null

  private val navigationControl = NavigationControl(NavigationControlOptions(visualizePitch = true))
  private val logoControl = LogoControl()
  private val scaleControl = ScaleControl()
  private val attributionControl = AttributionControl()

  override fun setOrnamentSettings(value: OrnamentSettings) {
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
      if (desiredCompassPosition == null) impl.removeControl(navigationControl)
      else impl.addControl(navigationControl, desiredCompassPosition)
      compassPosition = desiredCompassPosition
    }

    if (logoPosition != desiredLogoPosition) {
      if (desiredLogoPosition == null) impl.removeControl(logoControl)
      else impl.addControl(logoControl, desiredLogoPosition)
      logoPosition = desiredLogoPosition
    }

    if (scalePosition != desiredScalePosition) {
      if (desiredScalePosition == null) impl.removeControl(scaleControl)
      else impl.addControl(scaleControl, desiredScalePosition)
      scalePosition = desiredScalePosition
    }

    if (attributionPosition != desiredAttributionPosition) {
      if (desiredAttributionPosition == null) impl.removeControl(attributionControl)
      else impl.addControl(attributionControl, desiredAttributionPosition)
      attributionPosition = desiredAttributionPosition
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

  override fun getStyleUri() = lastStyleUri

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
  ): List<Feature> {
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
  ): List<Feature> {
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

  override fun getMapSnapshotter() = JsMapSnapshotter()
}
