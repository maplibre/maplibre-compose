package org.maplibre.compose.gljs

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array
import kotlin.js.Promise
import web.html.HTMLElement

// The hand-written subset of MapLibre GL JS this platform binds against; GlJsDeclarationsTest
// checks it against the MapLibre actually on the page.

public external interface Subscription {
  public fun unsubscribe()
}

/** Only the `error` event carries an [error]. */
public external interface MapEvent {
  public val error: JsError?
  public val sourceId: String?
  public val sourceDataType: String?
  /** Present on tile failures; a style-document failure has none. */
  public val tile: Any?
}

/**
 * True when this error is the style document failing to load, not a later source, tile, or sprite
 * fetch. MapLibre fires every one of those as `error`.
 */
internal fun MapEvent.isStyleDocumentError(): Boolean = sourceId == null && tile == null

public external interface JsError {
  public val message: String?
}

public external interface MapOptions {
  public var container: HTMLElement
  public var interactive: Boolean?
  public var attributionControl: Boolean?
  public var maplibreLogo: Boolean?
  public var pixelRatio: Double?
  /** `[width, height]` in physical pixels, above which MapLibre lowers its own pixel ratio. */
  public var maxCanvasSize: Array<Double>?
}

public external interface SetStyleOptions {
  public var diff: Boolean?
}

/** What MapLibre loads a style from; see [styleUrl] and [styleJson]. */
public external interface StyleSource

public external interface StyleSpecification {
  public val layers: Array<LayerSpecification>
  public val sources: JsRecord<SourceSpecification>
}

public external interface LayerSpecification {
  public val id: String
}

public external interface SourceSpecification

internal external interface RequestParameters {
  val url: String
}

internal external interface ProtocolResponse {
  var data: ArrayBuffer
}

public external interface FilterSpecification

/** MapLibre leaves both zoom bounds undefined on a layer whose stylesheet named neither. */
public external interface StyleLayer {
  public val id: String
  public val type: String
  public val minzoom: Double?
  public val maxzoom: Double?
}

internal external interface SourceHandle {
  val type: String
  val attribution: String?
}

internal external interface GlJsGeoJsonSource : SourceHandle {
  fun setData(data: GeoJsonSourceData)

  fun getClusterExpansionZoom(clusterId: Double): Promise<Double>

  fun getClusterChildren(clusterId: Double): Promise<Array<GeoJsonFeature>>

  fun getClusterLeaves(
    clusterId: Double,
    limit: Double,
    offset: Double,
  ): Promise<Array<GeoJsonFeature>>
}

/** GeoJSON to read, or a URL to fetch it from — the style spec's own rule for `data`. */
internal external interface GeoJsonSourceData

internal external interface GlJsImageSource : SourceHandle {
  val coordinates: Array<Array<Double>>

  fun setCoordinates(coordinates: Array<Array<Double>>)

  fun updateImage(options: UpdateImageOptions)
}

internal external interface UpdateImageOptions {
  var url: String
}

public external interface PaddingOptions {
  public var top: Double
  public var bottom: Double
  public var left: Double
  public var right: Double
}

/** Geometry for [org.maplibre.compose.gljs.MaplibreMap.queryRenderedFeatures]. */
public external interface QueryGeometry

public external interface Point {
  public var x: Double
  public var y: Double
}

public external interface QueryRenderedFeaturesOptions {
  public var layers: Array<String>?
  public var filter: FilterSpecification?
}

public external interface QuerySourceFeatureOptions {
  public var sourceLayer: String?
  public var filter: FilterSpecification?
}

/** Identifies a feature for [org.maplibre.compose.gljs.MaplibreMap.setFeatureState]. */
public external interface FeatureIdentifier {
  public var source: String
  public var sourceLayer: String?
  /** A GeoJSON string id, or a number when the GeoJSON `id` was unquoted. */
  public var id: Any?
}

public external interface GeoJsonFeature {
  public val type: String
  public val geometry: Any
  public val properties: Any?
}

public external interface MapGeoJsonFeature : GeoJsonFeature {
  public val source: String
  public val sourceLayer: String?
}

public external interface StyleImageData {
  public var width: Double
  public var height: Double
  public var data: Uint8Array<ArrayBuffer>
}

public external interface StyleImageMetadata {
  public var pixelRatio: Double
  public var sdf: Boolean
  public var stretchX: Array<Array<Double>>?
  public var stretchY: Array<Array<Double>>?
  public var content: Array<Double>?
}

public external interface CameraOptions {
  public var center: LngLat?
  public var zoom: Double?
  public var bearing: Double?
  public var pitch: Double?
}

public external interface CenterZoomBearing {
  public var center: LngLat?
  public var zoom: Double?
  public var bearing: Double?
}

public external interface AnimationOptions {
  public var duration: Double?
}

public external interface JumpToOptions : CameraOptions {
  public var padding: PaddingOptions?
}

public external interface EaseToOptions : CameraOptions, AnimationOptions {
  public var around: LngLat?
  public var padding: PaddingOptions?
}

public external interface FlyToOptions : CameraOptions, AnimationOptions {
  public var padding: PaddingOptions?
}

public external interface CameraForBoundsOptions : CameraOptions {
  public var padding: PaddingOptions?
}

public external interface Painter {
  public val context: Context
}

public external interface Context {
  public fun setDirty()
}

/** A plain JavaScript object keyed by string. */
public external interface JsRecord<out T>

/** MapLibre fetches a string style and reads an object one as the stylesheet itself. */
internal fun styleUrl(url: String): StyleSource = url.unsafeCast<StyleSource>()

internal fun styleJson(json: String): StyleSource = JSON.parse(json)

/** v6 composes a Camera instead of extending it. `isEasing` lives on that Camera, not on Map. */
internal fun MaplibreMap.isCameraEasing(): Boolean {
  val camera = asDynamic()._camera
  check(jsTypeOf(camera.isEasing) == "function") {
    "MapLibre's Camera no longer has an isEasing method"
  }
  return camera.isEasing() as Boolean
}

/**
 * Returns `[x, y]`. GL JS treats only an `Array` or a `Point` instance as query geometry. A plain
 * `{x, y}` object is not geometry, so the query uses the whole viewport.
 */
internal fun queryPoint(x: Double, y: Double): QueryGeometry =
  arrayOf(x, y).unsafeCast<QueryGeometry>()

/** MapLibre distinguishes a box from a point by shape: a box is a two-element array. */
internal fun queryBox(first: Point, second: Point): QueryGeometry =
  arrayOf(first, second).unsafeCast<QueryGeometry>()

internal fun JsRecord<*>.keys(): Array<String> = js("Object").keys(this).unsafeCast<Array<String>>()
