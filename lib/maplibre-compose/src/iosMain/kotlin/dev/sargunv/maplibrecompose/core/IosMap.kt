package dev.sargunv.maplibrecompose.core

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import cocoapods.MapLibre.MLNAltitudeForZoomLevel
import cocoapods.MapLibre.MLNCameraChangeReason
import cocoapods.MapLibre.MLNCameraChangeReasonGestureOneFingerZoom
import cocoapods.MapLibre.MLNCameraChangeReasonGesturePan
import cocoapods.MapLibre.MLNCameraChangeReasonGesturePinch
import cocoapods.MapLibre.MLNCameraChangeReasonGestureRotate
import cocoapods.MapLibre.MLNCameraChangeReasonGestureTilt
import cocoapods.MapLibre.MLNCameraChangeReasonGestureZoomIn
import cocoapods.MapLibre.MLNCameraChangeReasonGestureZoomOut
import cocoapods.MapLibre.MLNCameraChangeReasonProgrammatic
import cocoapods.MapLibre.MLNFeatureProtocol
import cocoapods.MapLibre.MLNLoggingBlockHandler
import cocoapods.MapLibre.MLNLoggingConfiguration
import cocoapods.MapLibre.MLNLoggingLevel
import cocoapods.MapLibre.MLNLoggingLevelDebug
import cocoapods.MapLibre.MLNLoggingLevelError
import cocoapods.MapLibre.MLNLoggingLevelFault
import cocoapods.MapLibre.MLNLoggingLevelInfo
import cocoapods.MapLibre.MLNLoggingLevelVerbose
import cocoapods.MapLibre.MLNLoggingLevelWarning
import cocoapods.MapLibre.MLNMapCamera
import cocoapods.MapLibre.MLNMapDebugCollisionBoxesMask
import cocoapods.MapLibre.MLNMapDebugTileBoundariesMask
import cocoapods.MapLibre.MLNMapDebugTileInfoMask
import cocoapods.MapLibre.MLNMapDebugTimestampsMask
import cocoapods.MapLibre.MLNMapView
import cocoapods.MapLibre.MLNMapViewDelegateProtocol
import cocoapods.MapLibre.MLNOrnamentPosition
import cocoapods.MapLibre.MLNOrnamentPositionBottomLeft
import cocoapods.MapLibre.MLNOrnamentPositionBottomRight
import cocoapods.MapLibre.MLNOrnamentPositionTopLeft
import cocoapods.MapLibre.MLNOrnamentPositionTopRight
import cocoapods.MapLibre.MLNStyle
import cocoapods.MapLibre.allowsTilting
import dev.sargunv.maplibrecompose.core.util.toBoundingBox
import dev.sargunv.maplibrecompose.core.util.toCGPoint
import dev.sargunv.maplibrecompose.core.util.toCGRect
import dev.sargunv.maplibrecompose.core.util.toCLLocationCoordinate2D
import dev.sargunv.maplibrecompose.core.util.toDpOffset
import dev.sargunv.maplibrecompose.core.util.toFeature
import dev.sargunv.maplibrecompose.core.util.toMLNCoordinateBounds
import dev.sargunv.maplibrecompose.core.util.toMLNOrnamentPosition
import dev.sargunv.maplibrecompose.core.util.toNSPredicate
import dev.sargunv.maplibrecompose.core.util.toPosition
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
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
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGSize
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
import platform.darwin.NSUInteger
import platform.darwin.sel_registerName

internal class IosMap(
  private var mapView: MLNMapView,
  internal var size: CValue<CGSize>,
  internal var layoutDir: LayoutDirection,
  internal var density: Density,
  internal var insetPadding: PaddingValues,
  internal var callbacks: MaplibreMap.Callbacks,
  internal var logger: Logger?,
) : StandardMaplibreMap {

  // hold strong references to things that the sdk keeps weak references to
  private val gestures = mutableListOf<Gesture<*>>()
  private val delegate: Delegate

  init {
    mapView.automaticallyAdjustsContentInset = false

    addGestures(
      Gesture(UITapGestureRecognizer()) {
        if (state != UIGestureRecognizerStateEnded) return@Gesture
        val point = locationInView(this@IosMap.mapView).toDpOffset()
        callbacks.onClick(this@IosMap, positionFromScreenLocation(point), point)
      },
      Gesture(UILongPressGestureRecognizer(), isCooperative = false) {
        if (state != UIGestureRecognizerStateBegan) return@Gesture
        val point = locationInView(this@IosMap.mapView).toDpOffset()
        callbacks.onLongClick(this@IosMap, positionFromScreenLocation(point), point)
      },
    )

    // delegate log level configuration to Kermit logger
    MLNLoggingConfiguration.sharedConfiguration.setHandler(LoggingBlockHandler(this))
    MLNLoggingConfiguration.sharedConfiguration.loggingLevel = MLNLoggingLevelVerbose

    delegate = Delegate(this)
    mapView.delegate = delegate
  }

  private class LoggingBlockHandler(private val map: IosMap) : MLNLoggingBlockHandler {
    override fun invoke(level: MLNLoggingLevel, path: String?, line: NSUInteger, message: String?) {
      when (level) {
        MLNLoggingLevelFault -> map.logger?.a { "$message" }
        MLNLoggingLevelError -> map.logger?.e { "$message" }
        MLNLoggingLevelWarning -> map.logger?.w { "$message" }
        MLNLoggingLevelInfo -> map.logger?.i { "$message" }
        MLNLoggingLevelDebug -> map.logger?.d { "$message" }
        MLNLoggingLevelVerbose -> map.logger?.v { "$message" }
        else -> error("Unexpected logging level: $level")
      }
    }
  }

  private class Delegate(private val map: IosMap) : NSObject(), MLNMapViewDelegateProtocol {

    val timeSource = TimeSource.Monotonic
    var lastFrameTime = timeSource.markNow()

    override fun mapViewWillStartLoadingMap(mapView: MLNMapView) {
      map.logger?.i { "Map will start loading" }
    }

    override fun mapViewDidFailLoadingMap(mapView: MLNMapView, withError: NSError) {
      map.logger?.e { "Map failed to load: $withError" }
    }

    override fun mapViewDidFinishLoadingMap(mapView: MLNMapView) {
      map.logger?.i { "Map finished loading" }
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

  private var lastStyleUri: String = ""

  override fun setStyleUri(styleUri: String) {
    if (styleUri == lastStyleUri) return
    lastStyleUri = styleUri
    logger?.i { "Setting style URI" }
    callbacks.onStyleChanged(this, null)
    mapView.setStyleURL(NSURL(string = styleUri))
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

  override fun setDebugEnabled(enabled: Boolean) {
    mapView.debugMask =
      if (enabled)
        MLNMapDebugTileBoundariesMask or
          MLNMapDebugTileInfoMask or
          MLNMapDebugTimestampsMask or
          MLNMapDebugCollisionBoxesMask
      else 0uL
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

  override fun setMaximumFps(maximumFps: Int) {
    mapView.preferredFramesPerSecond = maximumFps.toLong()
  }

  override fun setGestureSettings(value: GestureSettings) {
    mapView.rotateEnabled = value.isRotateGesturesEnabled
    mapView.scrollEnabled = value.isScrollGesturesEnabled
    mapView.allowsTilting = value.isTiltGesturesEnabled
    mapView.zoomEnabled = value.isZoomGesturesEnabled
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

      MLNOrnamentPositionBottomLeft ->
        CGPointMake(
          (uiPadding.calculateLeftPadding(layoutDir).value -
              insetPadding.calculateLeftPadding(layoutDir).value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
          (uiPadding.calculateBottomPadding().value - insetPadding.calculateBottomPadding().value)
            .toDouble()
            .coerceAtLeast(0.0) + 8.0,
        )

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

  override fun setOrnamentSettings(value: OrnamentSettings) {
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
  ): List<Feature> =
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
  ): List<Feature> =
    mapView
      .visibleFeaturesInRect(
        rect = rect.toCGRect(),
        inStyleLayersWithIdentifiers = layerIds,
        predicate = predicate?.toNSPredicate(),
      )
      .map { (it as MLNFeatureProtocol).toFeature() }

  override fun metersPerDpAtLatitude(latitude: Double) = mapView.metersPerPointAtLatitude(latitude)
}
