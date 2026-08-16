package org.maplibre.compose.sources

import js.objects.unsafeJso
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.QuerySourceFeatureOptions
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toStyleJson
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
  ): List<Feature<Geometry, JsonObject?>> {
    if (sourceLayerIds.isEmpty()) return emptyList()
    // MapLibre reads an absent filter as "match everything", and a scalar true is not a filter.
    val filter: FilterSpecification? =
      predicate
        .takeUnless { it == const(true) }
        ?.compile(ExpressionContext.None)
        ?.toStyleJson()
        ?.toJsValue()
    // MapLibre GL JS queries one source layer per call, where the common API takes a set.
    return binding
      ?.withMap { map ->
        sourceLayerIds.flatMap { layer ->
          val options =
            unsafeJso<QuerySourceFeatureOptions> {
              sourceLayer = layer
              this.filter = filter
            }
          map.querySourceFeatures(id, options).map { it.toGeoJsonFeature() }
        }
      }
      .orEmpty()
  }

  public actual fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    setJsFeatureState(featureId = featureId, sourceLayerId = sourceLayerId, state = state)
  }

  public actual fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    jsFeatureState(featureId, sourceLayerId)

  public actual fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String?,
  ) {
    removeJsFeatureState(featureId = featureId, sourceLayerId = sourceLayerId, stateKey = stateKey)
  }

  public actual fun resetFeatureStates(sourceLayerId: String) {
    removeJsFeatureState(sourceLayerId = sourceLayerId)
  }
}

public actual class RasterSource : Source {

  private val json: JsonObject

  public actual constructor(id: String, uri: String, tileSize: Int) : super(id) {
    json = buildJsonObject {
      put("type", "raster")
      put("url", uri)
      // "tileSize" is one of the few camelCase names in the style spec; "tilesize" is ignored.
      put("tileSize", tileSize)
    }
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
  ) : super(id) {
    json = buildJsonObject {
      put("type", "raster")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      put("tileSize", tileSize)
      putTileSetOptions(options)
    }
  }

  override fun toJson(): JsonObject = json
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

/**
 * @param definition what MapLibre reports about the source: its `type` and, where the style
 *   declares one, its `attribution`.
 */
public actual class UnknownSource
internal constructor(id: String, internal val definition: JsonObject) : Source(id) {

  override fun toJson(): JsonObject = definition
}
