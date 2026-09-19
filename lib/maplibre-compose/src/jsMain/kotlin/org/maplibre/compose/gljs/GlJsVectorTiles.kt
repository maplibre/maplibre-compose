@file:JsModule("@maplibre/geojson-vt")

package org.maplibre.compose.gljs

internal external fun geoJSONToTile(
  data: dynamic,
  z: Int,
  x: Int,
  y: Int,
  options: GeoJSONToTileOptions,
): dynamic

internal external interface GeoJSONToTileOptions {
  var extent: Double
  var buffer: Double
  var tolerance: Double
  var maxZoom: Int
  var wrap: Boolean
  var clip: Boolean
}
