package org.maplibre.compose.gljs

import js.buffer.ArrayBuffer
import js.date.Date
import js.typedarrays.Uint8Array
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import web.html.HTMLElement
import web.images.ImageData

// The hand-written subset of MapLibre GL JS this platform binds against; GlJsDeclarationsTest
// checks it against the loaded MapLibre version.

internal external interface Subscription : JsAny {
  fun unsubscribe()
}

/** Only the `error` event carries an [error]. */
internal external interface GlJsMapEvent : JsAny {
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
  var trackResize: Boolean?
  var attributionControl: Boolean?
  var maplibreLogo: Boolean?
  var pixelRatio: Double?
  var canvasContextAttributes: CanvasContextAttributes?
  /** `[width, height]` in physical pixels, above which MapLibre lowers its own pixel ratio. */
  var maxCanvasSize: JsArray<JsNumber>?
  var transformRequest: ((url: String, resourceType: String?) -> JsAny?)?
}

internal external interface CanvasContextAttributes : JsAny {
  var preserveDrawingBuffer: Boolean?
}

internal external interface SetStyleOptions : JsAny {
  var diff: Boolean?
}

/** What MapLibre loads a style from; see [styleUrl] and [styleJson]. */
internal external interface StyleSource : JsAny

internal external interface StyleSpecification : JsAny {
  val layers: JsArray<LayerSpecification>
  val sources: JsRecord<SourceSpecification>
  var transition: TransitionSpecification?
}

/** Milliseconds; MapLibre fills in the style-spec defaults when it reads the style's own. */
internal external interface TransitionSpecification : JsAny {
  var duration: Double?
  var delay: Double?
}

/** A style-spec `light` object; keys index it. */
internal external interface LightSpecification : JsAny

/** A style-spec `sky` object; keys index it. */
internal external interface SkySpecification : JsAny

/** A style-spec `projection` object; keys index it. */
internal external interface ProjectionSpecification : JsAny

internal external interface StyleSetterOptions : JsAny {
  var validate: Boolean?
}

internal external interface LayerSpecification : JsAny {
  val id: String
}

internal external interface SourceSpecification : JsAny

internal external interface RequestParameters {
  val url: String
  val headers: Any?
  /** How MapLibre GL JS reads the response, such as `arrayBuffer` or `image`. */
  val type: String?
}

internal external interface ProtocolResponse : JsAny {
  var data: ArrayBuffer
  var expires: Date?
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
  val source: String?
  val sourceLayer: String?
  val minzoom: Double?
  val maxzoom: Double?

  /** Serializes this one layer as its style-spec object. */
  fun serialize(): LayerSpecification
}

internal external interface SourceHandle : JsAny {
  val type: String
  val attribution: String?
}

internal external interface GlJsGeoJsonSource : SourceHandle {
  fun setData(data: GeoJsonSourceData)

  /** False while a data update is waiting or being processed, including the first load. */
  fun loaded(): Boolean

  // The cluster queries resolve null for a source that does not cluster, although GL JS's
  // declarations omit it.
  fun getClusterExpansionZoom(clusterId: Double): Promise<Double?>

  fun getClusterChildren(clusterId: Double): Promise<Array<GeoJsonFeature>?>

  fun getClusterLeaves(
    clusterId: Double,
    limit: Double,
    offset: Double,
  ): Promise<Array<GeoJsonFeature>?>
}

internal external interface CanonicalTileId {
  var x: Int
  var y: Int
  var z: Int
}

internal external interface GlJsVectorSource : SourceHandle {
  /** Replaces the source's tile URL templates and reloads its tiles. */
  fun setTiles(tiles: Array<String>)
}

/** GeoJSON data or its URL, as defined by the style spec's `data` property. */
internal external interface GeoJsonSourceData : JsAny

internal external interface GlJsImageSource : SourceHandle {
  val coordinates: JsArray<JsArray<JsNumber>>

  fun setCoordinates(coordinates: JsArray<JsArray<JsNumber>>)

  fun updateImage(options: UpdateImageOptions)
}

/** Set exactly one of [url] and [image]. */
internal external interface UpdateImageOptions {
  var url: String
  var image: ImageData
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
  val layer: LayerSpecification
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

internal external interface AnimationOptions {
  var duration: Double?
  var easing: ((Double) -> Double)?
}

internal external interface PaddedCameraOptions : CameraOptions {
  var padding: PaddingOptions?
}

internal external interface JumpToOptions : PaddedCameraOptions

internal external interface EaseToOptions : PaddedCameraOptions, AnimationOptions {
  var around: LngLat?
}

internal external interface FlyToOptions : PaddedCameraOptions, AnimationOptions {
  var screenSpeed: Double?
  var minZoom: Double?
  var curve: Double?
  var maxDuration: Double?
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
 * Use the transform's surface test: projecting an intersection behind the camera can round-trip.
 */
internal fun MaplibreMap.isPointOnMapSurface(point: Point): Boolean {
  val transform = asDynamic()._camera.transform
  check(jsTypeOf(transform.isPointOnMapSurface) == "function") {
    "MapLibre's transform no longer has an isPointOnMapSurface method"
  }
  return transform.isPointOnMapSurface(point, asDynamic().terrain) as Boolean
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

internal fun JsRecord<*>.keys(): Array<String> = js("Object").keys(this).unsafeCast<Array<String>>()

/** GL JS 6 camera state, including the projection used by its renderer. */
internal external interface GlJsCamera {
  val transform: GlJsTransform
}

/**
 * The clone retains camera matrices and the globe/mercator transition for one rendered frame. The
 * setters apply the map's zoom, pitch, and bounds constraints and recompute the matrices.
 */
internal external interface GlJsTransform {
  val center: LngLat

  val zoom: Double

  val centerPoint: Point

  fun clone(): GlJsTransform

  fun setCenter(center: LngLat)

  fun setZoom(zoom: Double)

  fun setBearing(bearing: Double)

  fun setPitch(pitch: Double)

  fun setPadding(padding: PaddingOptions)

  fun setLocationAtPoint(location: LngLat, point: Point)

  fun locationToScreenPoint(position: LngLat, terrain: GlJsTerrain? = definedExternally): Point

  fun screenPointToLocation(point: Point, terrain: GlJsTerrain? = definedExternally): LngLat

  fun isPointOnMapSurface(point: Point, terrain: GlJsTerrain? = definedExternally): Boolean
}

internal external interface GlJsTerrain

internal external interface CameraForBoundsOptions {
  var bearing: Double
  var pitch: Double
  var absolutePadding: Boolean
  var padding: PaddingOptions
  var maxZoom: Double
}

internal external interface BoundsCamera {
  val center: LngLat
  val zoom: Double
  val bearing: Double
  val pitch: Double
}
