@file:JsModule("@maplibre/vt-pbf")

package org.maplibre.compose.gljs

import js.buffer.ArrayBuffer
import js.typedarrays.Uint8Array

internal external fun fromGeojsonVt(
  layers: dynamic,
  options: VectorTileEncodingOptions,
): Uint8Array<ArrayBuffer>

internal external interface VectorTileEncodingOptions {
  var version: Double
  var extent: Double
}
