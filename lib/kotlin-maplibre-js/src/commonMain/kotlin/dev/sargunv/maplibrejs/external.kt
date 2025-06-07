@file:JsModule("maplibre-gl")

package dev.sargunv.maplibrejs

import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.TouchEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent

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

  public fun setStyle(style: String)

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

  public fun on(event: String, listener: (AnyEvent) -> Unit)

  public fun off(event: String, listener: (AnyEvent) -> Unit)

  public fun once(event: String, listener: (AnyEvent) -> Unit)

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

  public fun addLayer(layer: LayerSpecification, beforeId: String? = definedExternally)

  public fun removeLayer(id: String)

  public fun getLayer(id: String): StyleLayer?

  public fun getLayersOrder(): Array<String>

  public fun addSource(id: String, source: SourceSpecification)

  public fun removeSource(id: String)

  public fun getSource(id: String): Source?

  public fun setLayoutProperty(layerId: String, name: String, value: Expression)

  public fun setPaintProperty(layerId: String, name: String, value: Expression)

  public fun setFilter(layerId: String, filter: Expression)
}

/** [Source](https://maplibre.org/maplibre-gl-js/docs/API/interfaces/Source/) */
public sealed external interface Source {
  public val type: String

  public val id: String

  public var minzoom: Number
  public var maxzoom: Number
  public var tileSize: Number
  public var attribution: String?
}

/** https://maplibre.org/maplibre-style-spec/sources/ */
public sealed external interface SourceSpecification {
  public var type: String
}

public sealed external interface VectorSourceSpecification : SourceSpecification {
  public var url: String?
  public var tiles: Array<String>?
  public var bounds: Array<Double>?
  public var scheme: String?
  public var minzoom: Int?
  public var maxzoom: Int?
  public var attribution: String?
  public var promoteId: dynamic // string or object
  public var volatile: Boolean?
}

public sealed external interface RasterSourceSpecification : SourceSpecification {
  public var url: String?
  public var tiles: Array<String>?
  public var bounds: Array<Double>?
  public var minzoom: Int?
  public var maxzoom: Int?
  public var tileSize: Int?
  public var scheme: String?
  public var attribution: String?
  public var volatile: Boolean?
}

public sealed external interface GeoJsonSourceSpecification : SourceSpecification {
  public var data: dynamic // string url or geojson object
  public var maxzoom: Int?
  public var attribution: String?
  public var buffer: Int?
  public var filter: Expression?
  public var tolerance: Double?
  public var cluster: Boolean?
  public var clusterRadius: Int?
  public var clusterMaxZoom: Int?
  public var clusterMinPoints: Int?
  public var clusterProperties: dynamic
  public var lineMetrics: Boolean?
  public var generateId: Boolean?
  public var progression: dynamic // string or object
  public var volatile: Boolean?
}

/** [StyleLayer](https://maplibre.org/maplibre-gl-js/docs/API/classes/StyleLayer/) */
public sealed external class StyleLayer {
  public val id: String
  public val type: String
  public var source: String
  public var sourceLayer: String?
  public var minzoom: Int?
  public var maxzoom: Int?
  public var filter: Expression?
  public var visibility: String?

  public fun getLayoutProperty(key: String): dynamic

  public fun setLayoutProperty(key: String, value: dynamic)

  public fun getPaintProperty(key: String): dynamic

  public fun setPaintProperty(key: String, value: dynamic)
}

public external class FillStyleLayer private constructor() : StyleLayer

public external class BackgroundStyleLayer private constructor() : StyleLayer

public external class CircleStyleLayer private constructor() : StyleLayer

public external class FillExtrusionStyleLayer private constructor() : StyleLayer

public external class HeatmapStyleLayer private constructor() : StyleLayer

public external class HillshadeStyleLayer private constructor() : StyleLayer

public external class LineStyleLayer private constructor() : StyleLayer

public external class RasterStyleLayer private constructor() : StyleLayer

public external class SymbolStyleLayer private constructor() : StyleLayer

public external interface LayerSpecification {
  public var id: String?
  public var source: String?
  public var sourceLayer: String?
  public var type: String?
  public var minzoom: Int?
  public var maxzoom: Int?
  public var filter: Expression?
  public var layout: dynamic
  public var paint: dynamic
}

/**
 * [QueryRenderedFeaturesOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/QueryRenderedFeaturesOptions/)
 */
public sealed external interface QueryRenderedFeaturesOptions {
  public var availableImages: Array<String>?
  public var layers: Array<String>?
  public var filter: Expression?
  public var validate: Boolean?
}

public sealed external interface AnyEvent

/** [MapLibreEvent](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/MapLibreEvent/) */
public external interface MapLibreEvent<T> : AnyEvent {
  public val originalEvent: T
  public val target: Map
  public val type: String
}

/** [MapMouseEvent](https://maplibre.org/maplibre-gl-js/docs/API/classes/MapMouseEvent/) */
public external class MapMouseEvent private constructor() : MapLibreEvent<MouseEvent> {
  public val defaultPrevented: Boolean
  public val lngLat: LngLat
  override val originalEvent: MouseEvent
  public val point: Point
  override val target: Map
  override val type: String

  public fun preventDefault()
}

/** [MapTouchEvent](https://maplibre.org/maplibre-gl-js/docs/API/classes/MapTouchEvent/) */
public external class MapTouchEvent private constructor() : MapLibreEvent<TouchEvent> {
  public val defaultPrevented: Boolean
  public val lngLat: LngLat
  public val lngLats: Array<LngLat>
  override val originalEvent: TouchEvent
  public val point: Point
  public val points: Array<Point>
  override val target: Map
  override val type: String

  public fun preventDefault()
}

/** [MapWheelEvent](https://maplibre.org/maplibre-gl-js/docs/API/classes/MapWheelEvent/) */
public external class MapWheelEvent private constructor() : MapLibreEvent<WheelEvent> {
  public val defaultPrevented: Boolean
  override val originalEvent: WheelEvent
  override val target: Map
  override val type: String

  public fun preventDefault()
}

/** [Point](https://github.com/mapbox/point-geometry/tree/main?tab=readme-ov-file#point) */
public external class Point public constructor(x: Double, y: Double) {
  public val x: Double
  public val y: Double
  // a whole bunch of methods I'm not going to bother with
}

/**
 * [DoubleClickZoomHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/DoubleClickZoomHandler/)
 */
public external class DoubleClickZoomHandler private constructor() {
  public fun disable()

  public fun enable()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean
}

/** [DragPanHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/DragPanHandler/) */
public external class DragPanHandler private constructor() {
  public fun disable()

  public fun enable()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean
}

/** [DragRotateHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/DragRotateHandler/) */
public external class DragRotateHandler private constructor() {
  public fun disable()

  public fun enable()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean
}

/** [KeyboardHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/KeyboardHandler/) */
public external class KeyboardHandler private constructor() {
  public fun disable()

  public fun disableRotation()

  public fun enable()

  public fun enableRotation()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean
}

/** [ScrollZoomHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/ScrollZoomHandler/) */
public external class ScrollZoomHandler private constructor() {
  public fun disable()

  public fun enable()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean

  public fun setWheelZoomRate(wheelZoomRate: Double)

  public fun setZoomRate(zoomRate: Double)
}

/**
 * [TwoFingersTouchPitchHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/TwoFingersTouchPitchHandler/)
 */
public external class TwoFingersTouchPitchHandler private constructor() {
  public fun disable()

  public fun enable()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean
}

/**
 * [TwoFingersTouchZoomRotateHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/TwoFingersTouchZoomRotateHandler/)
 */
public external class TwoFingersTouchZoomRotateHandler private constructor() {
  public fun disable()

  public fun disableRotation()

  public fun enable()

  public fun enableRotation()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean
}

/** [LogoControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/LogoControl/) */
public external class LogoControl
public constructor(options: LogoControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}

/**
 * [LogoControlOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/LogoControlOptions/)
 */
public external interface LogoControlOptions {
  public var compact: Boolean?
}

/** [ScaleControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/ScaleControl/) */
public external class ScaleControl
public constructor(options: ScaleControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}

/**
 * [ScaleControlOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/ScaleControlOptions/)
 */
public external interface ScaleControlOptions {
  public var maxWidth: Double?
  public var unit: String?
}

/**
 * [AttributionControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/AttributionControl/)
 */
public external class AttributionControl
public constructor(options: AttributionControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}

/**
 * [AttributionControlOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/AttributionControlOptions/)
 */
public external interface AttributionControlOptions {
  public var compact: Boolean?
  public var customAttribution: String?
}

/** [NavigationControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/NavigationControl/) */
public external class NavigationControl
public constructor(options: NavigationControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}

/**
 * [NavigationControlOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/NavigationControlOptions/)
 */
public external interface NavigationControlOptions {
  public var showCompass: Boolean?
  public var showZoom: Boolean?
  public var visualizePitch: Boolean?
}

/** [IControl](https://maplibre.org/maplibre-gl-js/docs/API/interfaces/IControl/) */
public external interface IControl {
  public fun onAdd(map: Map): HTMLElement

  public fun onRemove(map: Map)
}

/** [LngLat](https://maplibre.org/maplibre-gl-js/docs/API/classes/LngLat/) */
public external class LngLat(public val lng: Double, public val lat: Double) {
  public fun distanceTo(lngLat: LngLat): Double

  public fun toArray(): DoubleArray

  public fun wrap(): LngLat
}

/** [LngLatBounds](https://maplibre.org/maplibre-gl-js/docs/API/classes/LngLatBounds/) */
public external class LngLatBounds(sw: LngLat, ne: LngLat) {
  public fun adjustAntiMeridian(): LngLatBounds

  public fun contains(lngLat: LngLat): Boolean

  public fun extend(lngLat: LngLat): LngLatBounds

  public fun getCenter(): LngLat

  public fun getEast(): Double

  public fun getWest(): Double

  public fun getNorth(): Double

  public fun getSouth(): Double

  public fun getSouthWest(): LngLat

  public fun getNorthEast(): LngLat

  public fun getNorthWest(): LngLat

  public fun getSouthEast(): LngLat

  public fun isEmpty(): Boolean

  public fun setSouthWest(lngLat: LngLat): LngLatBounds

  public fun setNorthEast(lngLat: LngLat): LngLatBounds

  public fun toArray(): Array<DoubleArray>
}

/** [JumpToOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/JumpToOptions/) */
public sealed external interface JumpToOptions : CameraOptions {
  public var padding: PaddingOptions?
}

public sealed external interface EaseToOptions : CameraOptions {
  public var padding: PaddingOptions?
  public var duration: Double?
  public var easing: (t: Double) -> Double?
}

/**
 * [FitBoundsOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/FitBoundsOptions/)
 */
public sealed external interface FitBoundsOptions : FlyToOptions {
  public var linear: Boolean?
  public var maxZoom: Double?
  public var offset: Point?
}

/** [FlyToOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/FlyToOptions/) */
public sealed external interface FlyToOptions : CameraOptions {
  public var curve: Double?
  public var maxDuration: Double?
  public var minZoom: Double?
  public var padding: PaddingOptions?
  public var speed: Double?
  public var screenSpeed: Double?
}

/** [CameraOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/CameraOptions/) */
public sealed external interface CameraOptions : CenterZoomBearing {
  public var around: LngLat?
  public var pitch: Double?
}

/**
 * [CenterZoomBearing](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/CenterZoomBearing/)
 */
public sealed external interface CenterZoomBearing {
  public var bearing: Double?
  public var center: LngLat?
  public var zoom: Double?
}

/** [PaddingOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/PaddingOptions/) */
public sealed external interface PaddingOptions {
  public var bottom: Double
  public var left: Double
  public var right: Double
  public var top: Double
}

/** [MapOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/MapOptions/) */
public sealed external interface MapOptions {
  public var container: HTMLElement
  public var attributionControl: dynamic // false | AttributionControlOptions
}
