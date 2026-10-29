@file:JsModule("@maplibre/vt-pbf")

package org.maplibre.compose.gljs

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array
import kotlin.js.JsAny

internal external fun fromGeojsonVt(
  layers: JsAny,
  options: VectorTileEncodingOptions,
): Uint8Array<ArrayBuffer>

internal external interface VectorTileEncodingOptions : JsAny {
  var version: Double
  var extent: Double
}
