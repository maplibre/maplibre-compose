package org.maplibre.kmp.native.map

import org.maplibre.kmp.native.camera.CameraOptions
import org.maplibre.kmp.native.util.AutoCleanPointer
import org.maplibre.kmp.native.util.LatLng
import org.maplibre.kmp.native.util.ScreenCoordinate
import org.maplibre.kmp.native.util.Size
import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public class MapLibreMap(
  private val frontend: RendererFrontend,
  private val observer: MapObserver,
  private val options: MapOptions,
  private val resourceOptions: ResourceOptions,
  private val clientOptions: ClientOptions,
) {

  internal val nativePeer =
    AutoCleanPointer(
      new = {
        nativeInit(
          frontendPointer = frontend.nativePointer,
          observer = observer,
          options = options,
          resourceOptions = resourceOptions,
          clientOptions = clientOptions,
        )
      },
      destroy = { nativeDestroy(it) },
    )

  @Suppress("unused")
  @get:CalledByNative
  @get:JvmName("getNativePointer")
  internal val nativePointer: Long
    get() = nativePeer.rawPtr

  // region Rendering
  public external fun triggerRepaint()

  // TODO: renderStill(callback)
  // TODO: renderStill(cameraOptions, debugOptions, callback)
  // endregion

  // region Style
  public external fun loadStyleURL(url: String)

  public external fun loadStyleJSON(json: String)

  // TODO: getStyle()
  // TODO: setStyle(style)
  // endregion

  // region Transitions / Gestures
  public external fun cancelTransitions()

  public var isGestureInProgress: Boolean
    get() = isGestureInProgressNative()
    set(value) = setGestureInProgressNative(value)

  public external fun setGestureInProgressNative(inProgress: Boolean)

  private external fun isGestureInProgressNative(): Boolean

  public val isRotating: Boolean
    get() = isRotatingNative()

  private external fun isRotatingNative(): Boolean

  public val isScaling: Boolean
    get() = isScalingNative()

  private external fun isScalingNative(): Boolean

  public val isPanning: Boolean
    get() = isPanningNative()

  private external fun isPanningNative(): Boolean

  // endregion

  // region Camera
  public external fun getCameraOptions(): CameraOptions

  public external fun jumpTo(cameraOptions: CameraOptions)

  public external fun easeTo(cameraOptions: CameraOptions, duration: Int)

  public external fun flyTo(cameraOptions: CameraOptions, duration: Int)

  public external fun moveBy(screenCoordinate: ScreenCoordinate)

  public external fun scaleBy(scale: Double, anchor: ScreenCoordinate?)

  public external fun pitchBy(pitch: Double)

  public external fun rotateBy(first: ScreenCoordinate, second: ScreenCoordinate)

  // TODO: cameraForLatLngBounds(bounds, edgeInsets, bearing?, pitch?)
  // TODO: cameraForLatLngs(points, edgeInsets, bearing?, pitch?)
  // TODO: cameraForGeometry(geometry, edgeInsets, bearing?, pitch?)
  // TODO: latLngBoundsForCamera(cameraOptions)
  // TODO: latLngBoundsForCameraUnwrapped(cameraOptions)
  // endregion

  // region Bounds
  // TODO: setBounds(options)
  // TODO: getBounds()
  // endregion

  // region Map Options
  private external fun getMapOptionsNative(): MapOptions

  public var northOrientation: NorthOrientation
    get() = getMapOptionsNative().northOrientation
    set(value) = setNorthOrientationNative(value)

  private external fun setNorthOrientationNative(value: NorthOrientation)

  public var constrainMode: ConstrainMode
    get() = getMapOptionsNative().constrainMode
    set(value) = setConstrainModeNative(value)

  private external fun setConstrainModeNative(value: ConstrainMode)

  public var viewportMode: ViewportMode
    get() = getMapOptionsNative().viewportMode
    set(value) = setViewportModeNative(value)

  private external fun setViewportModeNative(value: ViewportMode)

  public external fun setSize(size: Size)

  // endregion

  // region Projection Mode
  // TODO: setProjectionMode(mode)
  // TODO: getProjectionMode()
  // endregion

  // region Projection
  public external fun pixelForLatLng(latLng: LatLng): ScreenCoordinate

  public external fun latLngForPixel(pixel: ScreenCoordinate): LatLng

  // TODO: pixelsForLatLngs(latLngs)
  // TODO: latLngsForPixels(pixels)
  // endregion

  // region Transform
  // TODO: getTransfromState()
  // endregion

  // region Annotations
  // TODO: addAnnotationImage(image)
  // TODO: removeAnnotationImage(id)
  // TODO: getTopOffsetPixelsForAnnotationImage(id)
  // TODO: addAnnotation(annotation)
  // TODO: updateAnnotation(id, annotation)
  // TODO: removeAnnotation(id)
  // endregion

  // region Tile prefetching
  // TODO: setPrefetchZoomDelta(delta)
  // TODO: getPrefetchZoomDelta()
  // endregion

  // region Debug / Status
  public var debugOptions: MapDebugOptions
    get() = MapDebugOptions(getDebugNative())
    set(value) = setDebugNative(value.value)

  private external fun setDebugNative(debugOptions: Int)

  private external fun getDebugNative(): Int

  public var renderingStatsEnabled: Boolean
    get() = isRenderingStatsViewEnabledNative()
    set(value) = enableRenderingStatsViewNative(value)

  private external fun enableRenderingStatsViewNative(enabled: Boolean)

  private external fun isRenderingStatsViewEnabledNative(): Boolean

  public val isFullyLoaded: Boolean
    get() = isFullyLoadedNative()

  private external fun isFullyLoadedNative(): Boolean

  // TODO: dumpDebugLogs()
  // endregion

  // region Free Camera
  // TODO: setFreeCameraOptions(options)
  // TODO: getFreeCameraOptions()
  // endregion

  // region Tile LOD controls
  public var tileLodMinRadius: Double
    get() = getTileLodMinRadiusNative()
    set(value) = setTileLodMinRadiusNative(value)

  private external fun getTileLodMinRadiusNative(): Double

  private external fun setTileLodMinRadiusNative(value: Double)

  public var tileLodScale: Double
    get() = getTileLodScaleNative()
    set(value) = setTileLodScaleNative(value)

  private external fun getTileLodScaleNative(): Double

  private external fun setTileLodScaleNative(value: Double)

  public var tileLodPitchThreshold: Double
    get() = getTileLodPitchThresholdNative()
    set(value) = setTileLodPitchThresholdNative(value)

  private external fun getTileLodPitchThresholdNative(): Double

  private external fun setTileLodPitchThresholdNative(value: Double)

  public var tileLodZoomShift: Double
    get() = getTileLodZoomShiftNative()
    set(value) = setTileLodZoomShiftNative(value)

  private external fun getTileLodZoomShiftNative(): Double

  private external fun setTileLodZoomShiftNative(value: Double)

  // endregion

  // region Client / Journal
  // TODO: getClientOptions()
  // TODO: getActionJournal()
  // endregion

  private companion object {

    @JvmStatic
    external fun nativeInit(
      frontendPointer: Long,
      observer: MapObserver,
      options: MapOptions,
      resourceOptions: ResourceOptions,
      clientOptions: ClientOptions,
    ): Long

    @JvmStatic external fun nativeDestroy(ptr: Long)
  }
}
