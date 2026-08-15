@file:JvmName("MlnFfiVectorSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.util.toGeoJsonFeatures
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.compose.util.toStyleJson
import org.maplibre.nativeffi.query.SourceFeatureQueryOptions
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

    val options =
      SourceFeatureQueryOptions().also {
        it.sourceLayerIds = sourceLayerIds.toList()
        // A scalar true is not a valid filter; MapLibre reads an absent one as "match everything".
        it.filter =
          predicate
            .takeUnless { expression -> expression == const(true) }
            ?.compile(ExpressionContext.None)
            ?.toStyleJson()
            ?.toJsonBytes()
      }
    // Empty rather than an exception when no session is attached.
    return binding
      .withRenderSession { session -> session.querySourceFeatures(id, options) }
      ?.toGeoJsonFeatures()
      .orEmpty()
  }
}
