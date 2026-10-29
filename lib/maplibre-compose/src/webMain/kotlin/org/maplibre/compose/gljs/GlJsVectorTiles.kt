@file:JsModule("@maplibre/geojson-vt")

package org.maplibre.compose.gljs

import kotlin.js.JsAny
import kotlin.js.JsArray

internal external fun geoJSONToTile(
  data: JsAny?,
  z: Int,
  x: Int,
  y: Int,
  options: GeoJsonToTileOptions,
): VectorTile?

internal external interface GeoJsonToTileOptions : JsAny {
  var extent: Double
  var buffer: Double
  var tolerance: Double
  var maxZoom: Int
  var wrap: Boolean
  var clip: Boolean
}

internal external interface VectorTile : JsAny {
  val features: JsArray<JsAny>
}
