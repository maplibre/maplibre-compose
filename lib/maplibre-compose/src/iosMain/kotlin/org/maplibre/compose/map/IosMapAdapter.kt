package org.maplibre.compose.map

import MapLibre.MLNAltitudeForZoomLevel
import MapLibre.MLNCameraChangeReason
import MapLibre.MLNCameraChangeReasonGestureOneFingerZoom
import MapLibre.MLNCameraChangeReasonGesturePan
import MapLibre.MLNCameraChangeReasonGesturePinch
import MapLibre.MLNCameraChangeReasonGestureRotate
import MapLibre.MLNCameraChangeReasonGestureTilt
import MapLibre.MLNCameraChangeReasonGestureZoomIn
import MapLibre.MLNCameraChangeReasonGestureZoomOut
import MapLibre.MLNCameraChangeReasonProgrammatic
import MapLibre.MLNCoordinateBoundsMake
import MapLibre.MLNFeatureProtocol
import MapLibre.MLNLoggingConfiguration
import MapLibre.MLNLoggingLevelDebug
import MapLibre.MLNLoggingLevelError
import MapLibre.MLNLoggingLevelFault
import MapLibre.MLNLoggingLevelInfo
import MapLibre.MLNLoggingLevelVerbose
import MapLibre.MLNLoggingLevelWarning
import MapLibre.MLNMapCamera
import MapLibre.MLNMapDebugCollisionBoxesMask
import MapLibre.MLNMapDebugOverdrawVisualizationMask
import MapLibre.MLNMapDebugTileBoundariesMask
import MapLibre.MLNMapDebugTileInfoMask
import MapLibre.MLNMapDebugTimestampsMask
import MapLibre.MLNMapView
import MapLibre.MLNMapViewDelegateProtocol
import MapLibre.MLNOrnamentPosition
import MapLibre.MLNOrnamentPositionBottomLeft
import MapLibre.MLNOrnamentPositionBottomRight
import MapLibre.MLNOrnamentPositionTopLeft
import MapLibre.MLNOrnamentPositionTopRight
import MapLibre.MLNStyle
import MapLibre.allowsTilting
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.IosStyle
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.getSystemRefreshRate
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toCGPoint
import org.maplibre.compose.util.toCGRect
import org.maplibre.compose.util.toCLLocationCoordinate2D
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toFeature
import org.maplibre.compose.util.toMLNCoordinateBounds
import org.maplibre.compose.util.toMLNOrnamentPosition
import org.maplibre.compose.util.toNSPredicate
import org.maplibre.compose.util.toPosition
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGSize
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIEdgeInsets
import platform.UIKit.UIEdgeInsetsMake
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UILongPressGestureRecognizer
import platform.UIKit.UITapGestureRecognizer
import platform.darwin.NSObject
import platform.darwin.sel_registerName

internal class IosMapAdapter(
  private var mapView: MLNMapView,
  internal var size: CValue<CGSize>,
  internal var layoutDir: LayoutDirection,
  internal var density: Density,
  internal var insetPadding: PaddingValues,
  internal var callbacks: MapAdapter.Callbacks,
  internal var logger: Logger?,
) : MapAdapter {

  // hold strong references to things that the sdk keeps weak references to
  private val gestures = mutableListOf<Gesture<*>>()
  private val delegate: Delegate

  init {
    mapView.automaticallyAdjustsContentInset = false

    addGestures(
      Gesture(UITapGestureRecognizer()) {
        if (state != UIGestureRecognizerStateEnded) return@Gesture
        val point = locationInView(this@IosMapAdapter.mapView).toDpOffset()
        callbacks.onClick(this@IosMapAdapter, positionFromScreenLocation(point), point)
      },
      Gesture(UILongPressGestureRecognizer(), isCooperative = false) {
        if (state != UIGestureRecognizerStateBegan) return@Gesture
        val point = locationInView(this@IosMapAdapter.mapView).toDpOffset()
        callbacks.onLongClick(this@IosMapAdapter, positionFromScreenLocation(point), point)
      },
    )

    // delegate log level configuration to Kermit logger
    MLNLoggingConfiguration.sharedConfiguration.loggingLevel = MLNLoggingLevelVerbose
    MLNLoggingConfiguration.sharedConfiguration.setHandler { level, _, _, message ->
      when (level) {
        MLNLoggingLevelFault -> logger?.a { "$message" }
        MLNLoggingLevelError -> logger?.e { "$message" }
        MLNLoggingLevelWarning -> logger?.w { "$message" }
        MLNLoggingLevelInfo -> logger?.i { "$message" }
        MLNLoggingLevelDebug -> logger?.d { "$message" }
        MLNLoggingLevelVerbose -> logger?.v { "$message" }
        else -> error("Unexpected logging level: $level")
      }
    }

    delegate = Delegate(this)
    mapView.delegate = delegate
  }

  private class Delegate(private val map: IosMapAdapter) : NSObject(), MLNMapViewDelegateProtocol {

    val timeSource = TimeSource.Monotonic
    var lastFrameTime = timeSource.markNow()

    override fun mapViewWillStartLoadingMap(mapView: MLNMapView) {
      map.logger?.i { "Map will start loading" }
    }

    override fun mapViewDidFailLoadingMap(mapView: MLNMapView, withError: NSError) {
      map.logger?.e { "Map failed to load: $withError" }
      map.callbacks.onMapFailLoading(withError.localizedFailureReason)
    }

    override fun mapViewDidFinishLoadingMap(mapView: MLNMapView) {
      map.logger?.i { "Map finished loading" }
      map.callbacks.onMapFinishedLoading(map)
    }

    override fun mapView(mapView: MLNMapView, didFinishLoadingStyle: MLNStyle) {
      map.logger?.i { "Style finished loading" }
      map.callbacks.onStyleChanged(
        map = map,
        style = IosStyle(style = didFinishLoadingStyle, getScale = { map.density.density }),
      )
    }

    private val anyGesture =
      (MLNCameraChangeReasonGestureOneFingerZoom or
        MLNCameraChangeReasonGesturePan or
        MLNCameraChangeReasonGesturePinch or
        MLNCameraChangeReasonGestureRotate or
        MLNCameraChangeReasonGestureTilt or
        MLNCameraChangeReasonGestureZoomIn or
        MLNCameraChangeReasonGestureZoomOut)

    @ObjCSignatureOverride
    override fun mapView(
      mapView: MLNMapView,
      @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
      regionWillChangeWithReason: MLNCameraChangeReason,
      animated: Boolean,
    ) {
      map.callbacks.onCameraMoveStarted(
        map,
        if (regionWillChangeWithReason and anyGesture != 0uL) {
          CameraMoveReason.GESTURE
        } else if (regionWillChangeWithReason and MLNCameraChangeReasonProgrammatic != 0uL) {
          CameraMoveReason.PROGRAMMATIC
        } else {
          map.logger?.w { "Unknown camera move reason: $regionWillChangeWithReason" }
          CameraMoveReason.UNKNOWN
        },
      )
    }

    override fun mapViewRegionIsChanging(mapView: MLNMapView) {
      map.callbacks.onCameraMoved(map)
    }

    @ObjCSignatureOverride
    override fun mapView(
      mapView: MLNMapView,
      @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
      regionDidChangeWithReason: MLNCameraChangeReason,
      animated: Boolean,
    ) {
      map.callbacks.onCameraMoveEnded(map)
    }

    override fun mapViewDidFinishRenderingFrame(mapView: MLNMapView, fullyRendered: Boolean) {
      val time = timeSource.markNow()
      val duration = time - lastFrameTime
      lastFrameTime = time
      map.callbacks.onFrame(1.0 / duration.toDouble(DurationUnit.SECONDS))
    }
  }

  private var lastBaseStyle: BaseStyle? = null

  override fun setBaseStyle(style: BaseStyle) {
    if (style == lastBaseStyle) return
    lastBaseStyle = style
    logger?.i { "Setting style URI" }
    callbacks.onStyleChanged(this, null)
    when (style) {
      is BaseStyle.Uri -> mapView.setStyleURL(NSURL(string = style.uri))
      is BaseStyle.Json -> mapView.setStyleJSON(style.json)
    }
  }

  internal class Gesture<T : UIGestureRecognizer>(
    val recognizer: T,
    val isCooperative: Boolean = true,
    private val action: T.() -> Unit,
  ) : NSObject() {
    init {
      recognizer.addTarget(target = this, action = sel_registerName(::handleGesture.name + ":"))
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun handleGesture(sender: UIGestureRecognizer) {
      @Suppress("UNCHECKED_CAST") action(sender as T)
    }
  }

  private inline fun <reified T : UIGestureRecognizer> addGestures(vararg gestures: Gesture<T>) {
    gestures.forEach { gesture ->
      if (gesture.isCooperative) {
        mapView.gestureRecognizers!!.filterIsInstance<T>().forEach {
          gesture.recognizer.requireGestureRecognizerToFail(it)
        }
      }
      this.gestures.add(gesture)
      mapView.addGestureRecognizer(gesture.recognizer)
    }
  }

  override fun setMinPitch(minPitch: Double) {
    mapView.minimumPitch = minPitch
  }

  override fun setMaxPitch(maxPitch: Double) {
    mapView.maximumPitch = maxPitch
  }

  override fun setMinZoom(minZoom: Double) {
    mapView.minimumZoomLevel = minZoom
  }

  override fun setMaxZoom(maxZoom: Double) {
    mapView.maximumZoomLevel = maxZoom
  }

  private var lastBoundingBox: BoundingBox? = null

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) {
    if (boundingBox == lastBoundingBox) return
    lastBoundingBox = boundingBox
    mapView.setMaximumScreenBounds(
      boundingBox?.toMLNCoordinateBounds()
        ?: MLNCoordinateBoundsMake(
          ne = CLLocationCoordinate2DMake(latitude = 90.0, longitude = 180.0),
          sw = CLLocationCoordinate2DMake(latitude = -90.0, longitude = -180.0),
        )
    )
  }

  override fun getVisibleBoundingBox(): BoundingBox {
    return mapView.visibleCoordinateBounds.toBoundingBox()
  }

  override fun getVisibleRegion(): VisibleRegion {
    return size.useContents {
      VisibleRegion(
        farLeft = positionFromScreenLocation(DpOffset(x = 0.dp, y = 0.dp)),
        farRight = positionFromScreenLocation(DpOffset(x = width.dp, y = 0.dp)),
        nearLeft = positionFromScreenLocation(DpOffset(x = 0.dp, y = height.dp)),
        nearRight = positionFromScreenLocation(DpOffset(x = width.dp, y = height.dp)),
      )
    }
  }

  override fun setRenderSettings(value: RenderOptions) {
    mapView.preferredFramesPerSecond = value.maximumFps?.toLong() ?: getSystemRefreshRate(mapView)

    var debugMask = 0uL
    with(value.debugSettings) {
      if (isTileBoundariesEnabled) debugMask = debugMask or MLNMapDebugTileBoundariesMask
      if (isTileInfoEnabled) debugMask = debugMask or MLNMapDebugTileInfoMask
      if (isTimestampsEnabled) debugMask = debugMask or MLNMapDebugTimestampsMask
      if (isCollisionBoxesEnabled) debugMask = debugMask or MLNMapDebugCollisionBoxesMask
      if (isOverdrawVisualizationEnabled)
        debugMask = debugMask or MLNMapDebugOverdrawVisualizationMask
    }
    mapView.debugMask = debugMask
  }

  override fun setGestureSettings(value: GestureOptions) {
    mapView.rotateEnabled = value.isRotateEnabled
    mapView.scrollEnabled = value.isScrollEnabled
    mapView.allowsTilting = value.isTiltEnabled
    mapView.zoomEnabled = value.isZoomEnabled
    mapView.hapticFeedbackEnabled = value.isHapticFeedbackEnabled
  }

  private fun calculateMargins(
    ornamentPosition: MLNOrnamentPosition,
    uiPadding: PaddingValues,
  ): CValue<CGPoint> {
    return when (ornamentPosition) {
      MLNOrnamentPositionTopLeft ->
        CGPointMake(
          (uiPadding.calculateLeftPadding(layoutDir).value -
              insetPadding.calculateLeftPadding(layoutDir).value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
          (uiPadding.calculateTopPadding().value - insetPadding.calculateTopPadding().value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
        )

      MLNOrnamentPositionTopRight ->
        CGPointMake(
          (uiPadding.calculateRightPadding(layoutDir).value -
              insetPadding.calculateRightPadding(layoutDir).value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
          (uiPadding.calculateTopPadding().value - insetPadding.calculateTopPadding().value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
        )

      MLNOrnamentPositionBottomLeft -> {
        CGPointMake(
          (uiPadding.calculateLeftPadding(layoutDir).value -
              insetPadding.calculateLeftPadding(layoutDir).value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
          (uiPadding.calculateBottomPadding().value - insetPadding.calculateBottomPadding().value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
        )
      }

      MLNOrnamentPositionBottomRight ->
        CGPointMake(
          (uiPadding.calculateRightPadding(layoutDir).value -
              insetPadding.calculateRightPadding(layoutDir).value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
          (uiPadding.calculateBottomPadding().value - insetPadding.calculateBottomPadding().value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
        )

      else -> error("Invalid ornament position")
    }
  }

  override fun setOrnamentSettings(value: OrnamentOptions) {
    mapView.logoView.hidden = !value.isLogoEnabled
    mapView.logoViewPosition = value.logoAlignment.toMLNOrnamentPosition(layoutDir)
    mapView.logoViewMargins = calculateMargins(mapView.logoViewPosition, value.padding)

    mapView.attributionButton.hidden = !value.isAttributionEnabled
    mapView.attributionButtonPosition = value.attributionAlignment.toMLNOrnamentPosition(layoutDir)
    mapView.attributionButtonMargins =
      calculateMargins(mapView.attributionButtonPosition, value.padding)

    mapView.compassView.hidden = !value.isCompassEnabled
    mapView.compassViewPosition = value.compassAlignment.toMLNOrnamentPosition(layoutDir)
    mapView.compassViewMargins = calculateMargins(mapView.compassViewPosition, value.padding)

    mapView.scaleBar.hidden = !value.isScaleBarEnabled
    mapView.scaleBarPosition = value.scaleBarAlignment.toMLNOrnamentPosition(layoutDir)
    mapView.scaleBarMargins = calculateMargins(mapView.scaleBarPosition, value.padding)
  }

  private fun CameraPosition.toMLNMapCamera(): MLNMapCamera {
    return MLNMapCamera().let {
      it.centerCoordinate = target.toCLLocationCoordinate2D()
      it.pitch = tilt
      it.heading = bearing
      it.altitude =
        MLNAltitudeForZoomLevel(
          zoomLevel = zoom,
          pitch = tilt,
          latitude = target.latitude,
          size = size,
        )
      it
    }
  }

  override fun getCameraPosition(): CameraPosition {
    return CameraPosition(
      target = mapView.camera.centerCoordinate.toPosition(),
      bearing = mapView.camera.heading,
      tilt = mapView.camera.pitch,
      zoom = mapView.zoomLevel,
      padding =
        mapView.cameraEdgeInsets.useContents {
          PaddingValues.Absolute(left = left.dp, top = top.dp, right = right.dp, bottom = bottom.dp)
        },
    )
  }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    mapView.setCamera(
      cameraPosition.toMLNMapCamera(),
      withDuration = 0.0,
      animationTimingFunction = null,
      edgePadding = cameraPosition.padding.toEdgeInsets(),
      completionHandler = null,
    )
  }

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    mapView.setCamera(
      camera =
        mapView
          .cameraThatFitsCoordinateBounds(
            bounds = boundingBox.toMLNCoordinateBounds(),
            edgePadding = padding.toEdgeInsets(),
          )
          .apply {
            heading = bearing
            pitch = tilt
          },
      animated = false,
    )
  }

  private fun PaddingValues.toEdgeInsets(): CValue<UIEdgeInsets> =
    UIEdgeInsetsMake(
      top = calculateTopPadding().value.toDouble(),
      left = calculateLeftPadding(layoutDir).value.toDouble(),
      bottom = calculateBottomPadding().value.toDouble(),
      right = calculateRightPadding(layoutDir).value.toDouble(),
    )

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) =
    suspendCoroutine { cont ->
      mapView.flyToCamera(
        camera = finalPosition.toMLNMapCamera(),
        withDuration = duration.toDouble(DurationUnit.SECONDS),
        edgePadding = finalPosition.padding.toEdgeInsets(),
        completionHandler = { cont.resume(Unit) },
      )
    }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    suspendCoroutine { cont ->
      mapView.flyToCamera(
        camera =
          mapView
            .cameraThatFitsCoordinateBounds(
              bounds = boundingBox.toMLNCoordinateBounds(),
              edgePadding = padding.toEdgeInsets(),
            )
            .apply {
              heading = bearing
              pitch = tilt
            },
        withDuration = duration.toDouble(DurationUnit.SECONDS),
        edgePadding = padding.toEdgeInsets(),
        completionHandler = { cont.resume(Unit) },
      )
    }
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position =
    mapView.convertPoint(point = offset.toCGPoint(), toCoordinateFromView = mapView).toPosition()

  override fun screenLocationFromPosition(position: Position): DpOffset =
    mapView
      .convertCoordinate(position.toCLLocationCoordinate2D(), toPointToView = mapView)
      .toDpOffset()

  override fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    mapView
      .visibleFeaturesAtPoint(
        point = offset.toCGPoint(),
        inStyleLayersWithIdentifiers = layerIds,
        predicate = predicate?.toNSPredicate(),
      )
      .map { (it as MLNFeatureProtocol).toFeature() }

  override fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    mapView
      .visibleFeaturesInRect(
        rect = rect.toCGRect(),
        inStyleLayersWithIdentifiers = layerIds,
        predicate = predicate?.toNSPredicate(),
      )
      .map { (it as MLNFeatureProtocol).toFeature() }

  override fun metersPerDpAtLatitude(latitude: Double) = mapView.metersPerPointAtLatitude(latitude)
}
