package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

public actual class VectorSource : Source {

  private val json: JsonObject

  public actual constructor(id: String, uri: String) : super(id) {
    json = buildJsonObject {
      put("type", "vector")
      put("url", uri)
    }
  }

  public actual constructor(id: String, tiles: List<String>, options: TileSetOptions) : super(id) {
    json = buildJsonObject {
      put("type", "vector")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      putTileSetOptions(options)
    }
  }

  override fun toJson(): JsonObject = json

  public actual fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature<Geometry, JsonObject?>> =
    binding.querySourceFeatures(id, sourceLayerIds, predicate.toFilterJson())

  public actual fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    binding.setFeatureState(id, sourceLayerId, featureId, state)
  }

  public actual fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    binding.featureState(id, sourceLayerId, featureId)

  public actual fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String?,
  ) {
    binding.removeFeatureState(id, sourceLayerId, featureId, stateKey)
  }

  public actual fun resetFeatureStates(sourceLayerId: String) {
    binding.resetFeatureStates(id, sourceLayerId)
  }
}

public actual class RasterDemSource : Source {

  private val json: JsonObject

  public actual constructor(id: String, uri: String, tileSize: Int) : super(id) {
    json = buildJsonObject {
      put("type", "raster-dem")
      put("url", uri)
      put("tileSize", tileSize)
    }
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
    demEncoding: RasterDemEncoding,
  ) : super(id) {
    // The style spec has no `scheme` on raster-dem; GL JS 6 rejects the source over it.
    require(options.tileCoordinateSystem == TileCoordinateSystem.XYZ) {
      "The style spec has no scheme on a raster-dem source, and MapLibre GL JS rejects the " +
        "source over that key. Use TileCoordinateSystem.XYZ."
    }
    json = buildJsonObject {
      put("type", "raster-dem")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      put("tileSize", tileSize)
      // Unlike MapLibre Native, GL JS implements the custom encoding.
      put("encoding", demEncoding.value)
      if (demEncoding is RasterDemEncoding.Custom) {
        put("redFactor", demEncoding.redFactor)
        put("greenFactor", demEncoding.greenFactor)
        put("blueFactor", demEncoding.blueFactor)
        put("baseShift", demEncoding.baseShift)
      }
      putTileSetOptions(options, includeScheme = false)
    }
  }

  override fun toJson(): JsonObject = json
}
