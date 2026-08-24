package org.maplibre.compose.gljs

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array
import js.date.Date
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
internal external interface TransitionSpecification {
  var duration: Double?
  var delay: Double?
}

/** A style-spec `light` object; keys index it. */
internal external interface LightSpecification

/** A style-spec `sky` object; keys index it. */
internal external interface SkySpecification

/** A style-spec `projection` object; keys index it. */
internal external interface ProjectionSpecification

internal external interface StyleSetterOptions {
  var validate: Boolean?
}

internal external interface LayerSpecification : JsAny {
  val id: String
}

internal external interface SourceSpecification : JsAny

internal external interface RequestParameters : JsAny {
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

