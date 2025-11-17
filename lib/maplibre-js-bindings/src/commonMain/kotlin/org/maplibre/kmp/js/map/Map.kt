@file:Suppress("unused")
@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.map

import org.maplibre.kmp.js.controls.IControl
import org.maplibre.kmp.js.event.Event
import org.maplibre.kmp.js.geometry.LngLat
import org.maplibre.kmp.js.geometry.LngLatBounds
import org.maplibre.kmp.js.geometry.Point
import org.maplibre.kmp.js.gestures.DoubleClickZoomHandler
import org.maplibre.kmp.js.gestures.DragPanHandler
import org.maplibre.kmp.js.gestures.DragRotateHandler
import org.maplibre.kmp.js.gestures.KeyboardHandler
import org.maplibre.kmp.js.gestures.ScrollZoomHandler
import org.maplibre.kmp.js.gestures.TwoFingersTouchPitchHandler
import org.maplibre.kmp.js.gestures.TwoFingersTouchZoomRotateHandler
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/** [Map](https://maplibre.org/maplibre-gl-js/docs/API/classes/Map/) */
public external class Map public constructor(options: MapOptions) {
  public var repaint: Boolean
  public var showCollisionBoxes: Boolean
  public var showOverdrawInspector: Boolean
  public var showPadding: Boolean
  public var showTileBoundaries: Boolean
  public val version: String

  public val doubleClickZoom: DoubleClickZoomHandler
  public val dragPan: DragPanHandler
  public val dragRotate: DragRotateHandler
  public val keyboard: KeyboardHandler
  public val scrollZoom: ScrollZoomHandler
  public val touchPitch: TwoFingersTouchPitchHandler
  public val touchZoomRotate: TwoFingersTouchZoomRotateHandler

  public fun setStyle(style: dynamic)

  public fun remove()

  public fun getBearing(): Double

  public fun getCenter(): LngLat

  public fun getPitch(): Double

  public fun getZoom(): Double

  public fun getPadding(): PaddingOptions

  public fun setBearing(bearing: Double)

  public fun setCenter(center: LngLat)

  public fun setPitch(pitch: Double)

  public fun setPadding(padding: PaddingOptions)

  public fun setZoom(zoom: Double)

  public fun setMaxZoom(max: Double)

  public fun setMinZoom(min: Double)

  public fun setMaxPitch(max: Double)

  public fun setMinPitch(min: Double)

  public fun setMaxBounds(bounds: Array<DoubleArray>?)

  public fun jumpTo(options: JumpToOptions)

  public fun easeTo(options: EaseToOptions)

  public fun fitBounds(bounds: LngLatBounds, options: FitBoundsOptions?)

  public fun flyTo(options: FlyToOptions)

  public fun addControl(control: IControl, position: String)

  public fun removeControl(control: IControl)

  public fun triggerRepaint()

  public fun getCanvasContainer(): HTMLElement

  public fun getCanvas(): HTMLCanvasElement

  public fun resize()

  public fun on(event: String, listener: (Event) -> Unit)

  public fun off(event: String, listener: (Event) -> Unit)

  public fun once(event: String, listener: (Event) -> Unit)

  public fun project(lngLat: LngLat): Point

  public fun unproject(point: Point): LngLat

  public fun queryRenderedFeatures(
    point: Point,
    options: QueryRenderedFeaturesOptions = definedExternally,
  ): Array<Any>

  public fun queryRenderedFeatures(
    box: Array<Point>,
    options: QueryRenderedFeaturesOptions = definedExternally,
  ): Array<Any>

  public fun getBounds(): LngLatBounds

  public fun cameraForBounds(
    bounds: LngLatBounds,
    options: CameraForBoundsOptions = definedExternally,
  ): CenterZoomBearing
}
