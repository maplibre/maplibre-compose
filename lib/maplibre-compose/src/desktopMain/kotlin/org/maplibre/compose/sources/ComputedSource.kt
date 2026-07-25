package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection

/**
 * A computed source, which desktop can currently only stand in for.
 *
 * The style spec has no custom-geometry source — MapLibre Native accepts only `vector`, `raster`,
 * `raster-dem`, `geojson`, and `image` from source JSON — so a source that is attached by emitting
 * JSON cannot be one. What is added instead is an empty GeoJSON source carrying the tiling options
 * that do translate, which keeps the style valid and keeps layers that name this source resolvable;
 * they just draw nothing.
 *
 * TODO(maplibre-compose): attach this through the FFI's `addCustomGeometrySource`, which takes a
 *   `CustomGeometrySourceCallback` whose `fetchTile` is where [getFeatures] belongs, and route the
 *   three methods below to `setCustomGeometrySourceTileData`, `invalidateCustomGeometrySourceTile`,
 *   and `invalidateCustomGeometrySourceRegion`. That needs [Source] to offer a per-source
 *   attachment path instead of always emitting source JSON.
 */
public actual class ComputedSource : Source {

  private val options: ComputedSourceOptions
  private val getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
  ) : super(id) {
    this.options = options
    this.getFeatures = getFeatures
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    putJsonObject("data") {
      put("type", "FeatureCollection")
      putJsonArray("features") {}
    }
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
    put("buffer", options.buffer)
    put("tolerance", options.tolerance)
    // `clip` and `wrap` have no GeoJSON-source equivalent; they belong to the custom geometry
    // source options the TODO above describes.
  }

  public actual fun invalidateBounds(bounds: BoundingBox) {
    // Nothing to invalidate: no tile was ever requested. See the TODO on the class.
  }

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {
    // Nothing to invalidate: no tile was ever requested. See the TODO on the class.
  }

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>) {
    // The stand-in source is not tiled, so there is no tile to attach this data to. See the TODO on
    // the class.
  }
}
