package org.maplibre.compose.gljs

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array
import kotlin.js.Promise
import web.html.HTMLElement

// The hand-written subset of MapLibre GL JS this platform binds against; GlJsDeclarationsTest
// checks it against the MapLibre actually on the page.

internal external interface Subscription {
  fun unsubscribe()
}

/** Only the `error` event carries an [error]. */
internal external interface MapEvent {
  val error: JsError?
  val sourceId: String?
  val sourceDataType: String?
}

internal external interface JsError {
  val message: String?
}

internal external interface MapOptions {
  var container: HTMLElement
  var interactive: Boolean?
  var attributionControl: Boolean?
  var maplibreLogo: Boolean?
  var pixelRatio: Double?
  /** `[width, height]` in physical pixels, above which MapLibre lowers its own pixel ratio. */
  var maxCanvasSize: Array<Double>?
}

internal external interface SetStyleOptions {
  var diff: Boolean?
}

/** What MapLibre loads a style from; see [styleUrl] and [styleJson]. */
internal external interface StyleSource

internal external interface StyleSpecification {
  val layers: Array<LayerSpecification>
  val sources: JsRecord<SourceSpecification>
}

internal external interface LayerSpecification {
  val id: String
}

internal external interface SourceSpecification

internal external interface RequestParameters {
  val url: String
}

internal external interface ProtocolResponse {
  var data: ArrayBuffer
}

internal external interface FilterSpecification

/** MapLibre leaves both zoom bounds undefined on a layer whose stylesheet named neither. */
internal external interface StyleLayer {
  val id: String
  val type: String
  val minzoom: Double?
  val maxzoom: Double?
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

internal external interface PaddingOptions {
  var top: Double
  var bottom: Double
  var left: Double
  var right: Double
}

/** A point, or the two corners of a box; see [queryPoint] and [queryBox]. */
internal external interface QueryGeometry

internal external interface Point : QueryGeometry {
  var x: Double
  var y: Double
}

internal external interface QueryRenderedFeaturesOptions {
  var layers: Array<String>?
  var filter: FilterSpecification?
}

internal external interface QuerySourceFeatureOptions {
  var sourceLayer: String?
  var filter: FilterSpecification?
}

/** Identifies a feature for [org.maplibre.compose.gljs.MaplibreMap.setFeatureState]. */
internal external interface FeatureIdentifier {
  var source: String
  var sourceLayer: String?
  /** A GeoJSON string id, or a number when the GeoJSON `id` was unquoted. */
  var id: Any?
}

internal external interface GeoJsonFeature {
  val type: String
  val geometry: Any
  val properties: Any?
}

internal external interface MapGeoJsonFeature : GeoJsonFeature {
  val source: String
  val sourceLayer: String?
}

internal external interface StyleImageData {
  var width: Double
  var height: Double
  var data: Uint8Array<ArrayBuffer>
}

internal external interface StyleImageMetadata {
  var pixelRatio: Double
  var sdf: Boolean
  var stretchX: Array<Array<Double>>?
  var stretchY: Array<Array<Double>>?
  var content: Array<Double>?
}

internal external interface CameraOptions {
  var center: LngLat?
  var zoom: Double?
  var bearing: Double?
  var pitch: Double?
}

internal external interface CenterZoomBearing {
  var center: LngLat?
  var zoom: Double?
  var bearing: Double?
}

internal external interface AnimationOptions {
  var duration: Double?
}

internal external interface JumpToOptions : CameraOptions {
  var padding: PaddingOptions?
}

internal external interface EaseToOptions : CameraOptions, AnimationOptions {
  var around: LngLat?
  var padding: PaddingOptions?
}

internal external interface FlyToOptions : CameraOptions, AnimationOptions {
  var padding: PaddingOptions?
}

internal external interface CameraForBoundsOptions : CameraOptions {
  var padding: PaddingOptions?
}

internal external interface Painter {
  val context: Context
}

internal external interface Context {
  fun setDirty()
}

/** A plain JavaScript object keyed by string. */
internal external interface JsRecord<out T>

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
 * A `[x, y]` number pair. GL JS only treats an `Array` or a `Point` instance as query geometry; a
 * plain `{x, y}` object is read as options and the query covers the whole viewport.
 */
internal fun queryPoint(x: Double, y: Double): QueryGeometry =
  arrayOf(x, y).unsafeCast<QueryGeometry>()

/** MapLibre tells a box from a point by shape, a box being a two-element array. */
internal fun queryBox(first: Point, second: Point): QueryGeometry =
  arrayOf(first, second).unsafeCast<QueryGeometry>()

internal fun JsRecord<*>.keys(): Array<String> = js("Object").keys(this).unsafeCast<Array<String>>()
