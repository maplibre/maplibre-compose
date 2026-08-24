package org.maplibre.compose.gljs

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import web.html.HTMLElement

// The hand-written subset of MapLibre GL JS this platform binds against. The declared set is
// reviewed against the MapLibre on the page when bumping the library.

internal external interface Subscription : JsAny {
  fun unsubscribe()
}

/** Only the `error` event carries an [error]. */
internal external interface MapEvent : JsAny {
  val error: JsError?
  val sourceId: String?
  val sourceDataType: String?
}

internal external interface JsError : JsAny {
  val message: String?
}

internal external interface MapOptions : JsAny {
  var container: HTMLElement
  var interactive: Boolean?
  var attributionControl: Boolean?
  var maplibreLogo: Boolean?
  var pixelRatio: Double?
  /** `[width, height]` in physical pixels, above which MapLibre lowers its own pixel ratio. */
  var maxCanvasSize: JsArray<JsNumber>?
}

internal external interface SetStyleOptions : JsAny {
  var diff: Boolean?
}

/** What MapLibre loads a style from; see [styleUrl] and [styleJson]. */
internal external interface StyleSource : JsAny

internal external interface StyleSpecification : JsAny {
  val layers: JsArray<LayerSpecification>
  val sources: JsRecord<SourceSpecification>
}

internal external interface LayerSpecification : JsAny {
  val id: String
}

internal external interface SourceSpecification : JsAny

internal external interface RequestParameters : JsAny {
  val url: String
}

internal external interface ProtocolResponse : JsAny {
  var data: ArrayBuffer
}

internal external interface ProtocolAbortController : JsAny {
  val signal: ProtocolAbortSignal
}

internal external interface ProtocolAbortSignal : JsAny {
  val aborted: Boolean

  fun addEventListener(type: String, listener: () -> Unit)

  fun removeEventListener(type: String, listener: () -> Unit)
}

internal external interface FilterSpecification : JsAny

/** MapLibre leaves both zoom bounds undefined on a layer whose stylesheet named neither. */
internal external interface StyleLayer : JsAny {
  val id: String
  val type: String
  val minzoom: Double?
  val maxzoom: Double?
}

internal external interface SourceHandle : JsAny {
  val type: String
  val attribution: String?
}

internal external interface GlJsGeoJsonSource : SourceHandle {
  fun setData(data: GeoJsonSourceData)

  fun getClusterExpansionZoom(clusterId: Double): Promise<JsNumber>

  fun getClusterChildren(clusterId: Double): Promise<JsArray<GeoJsonFeature>>

  fun getClusterLeaves(
    clusterId: Double,
    limit: Double,
    offset: Double,
  ): Promise<JsArray<GeoJsonFeature>>
}

/** GeoJSON to read, or a URL to fetch it from — the style spec's own rule for `data`. */
internal external interface GeoJsonSourceData : JsAny

internal external interface GlJsImageSource : SourceHandle {
  val coordinates: JsArray<JsArray<JsNumber>>

  fun setCoordinates(coordinates: JsArray<JsArray<JsNumber>>)

  fun updateImage(options: UpdateImageOptions)
}

internal external interface UpdateImageOptions : JsAny {
  var url: String
}

internal external interface PaddingOptions : JsAny {
  var top: Double
  var bottom: Double
  var left: Double
  var right: Double
}

/** A point, or the two corners of a box; see [queryBox]. */
internal external interface QueryGeometry : JsAny

internal external interface Point : QueryGeometry {
  var x: Double
  var y: Double
}

internal external interface QueryRenderedFeaturesOptions : JsAny {
  var layers: JsArray<JsString>?
  var filter: FilterSpecification?
}

internal external interface QuerySourceFeatureOptions : JsAny {
  var sourceLayer: String?
  var filter: FilterSpecification?
}

/** Identifies a feature for [org.maplibre.compose.gljs.MaplibreMap.setFeatureState]. */
internal external interface FeatureIdentifier : JsAny {
  var source: String
  var sourceLayer: String?
  /** A GeoJSON string id, or a number when the GeoJSON `id` was unquoted. */
  var id: JsAny?
}

internal external interface GeoJsonFeature : JsAny {
  val type: String
  val geometry: JsAny
  val properties: JsAny?
}

internal external interface MapGeoJsonFeature : GeoJsonFeature {
  val source: String
  val sourceLayer: String?
}

internal external interface StyleImageData : JsAny {
  var width: Double
  var height: Double
  var data: Uint8Array<ArrayBuffer>
}

internal external interface StyleImageMetadata : JsAny {
  var pixelRatio: Double
  var sdf: Boolean
  var stretchX: JsArray<JsArray<JsNumber>>?
  var stretchY: JsArray<JsArray<JsNumber>>?
  var content: JsArray<JsNumber>?
}

internal external interface CameraOptions : JsAny {
  var center: LngLat?
  var zoom: Double?
  var bearing: Double?
  var pitch: Double?
}

internal external interface CenterZoomBearing : JsAny {
  var center: LngLat?
  var zoom: Double?
  var bearing: Double?
}

internal external interface AnimationOptions : JsAny {
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

internal external interface Painter : JsAny {
  val context: Context
}

internal external interface Context : JsAny {
  fun setDirty()
}

/** A plain JavaScript object keyed by string. */
internal external interface JsRecord<out T : JsAny?> : JsAny

/** MapLibre fetches a string style and reads an object one as the stylesheet itself. */
internal fun styleUrl(url: String): StyleSource = jsUnsafeCast(url.toJsString())

internal fun styleJson(json: String): StyleSource = jsUnsafeCast(parseJson(json))

/** v6 composes a Camera instead of extending it. `isEasing` lives on that Camera, not on Map. */
internal fun MaplibreMap.isCameraEasing(): Boolean {
  val camera = jsGet(this, "_camera")
  check(camera != null && isJsFunction(jsGet(camera, "isEasing"))) {
    "MapLibre's Camera no longer has an isEasing method"
  }
  return call0Boolean(camera, "isEasing")
}

/**
 * Returns `[x, y]`. GL JS treats only an `Array` or a `Point` instance as query geometry. A plain
 * `{x, y}` object is not geometry, so the query uses the whole viewport.
 */
internal fun queryPoint(x: Double, y: Double): QueryGeometry = jsUnsafeCast(jsPair(x, y))

/** MapLibre tells a box from a point by shape, a box being a two-element array. */
internal fun queryBox(first: Point, second: Point): QueryGeometry =
  jsUnsafeCast(jsPairAny(first, second))

internal fun JsRecord<*>.keys(): List<String> = objectKeys(this).toKotlinStrings()

internal fun MaplibreMap.layerIds(): List<String> = getLayersOrder().toKotlinStrings()
