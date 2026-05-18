package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.util.toJsonString
import org.maplibre.kmp.native.style.sources.VectorSource as MLNVectorSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

public actual class VectorSource : Source {
  override val impl: MLNVectorSource

  public actual constructor(id: String, uri: String) : super() {
    impl = MLNVectorSource(id, uri)
  }

  public actual constructor(id: String, tiles: List<String>, options: TileSetOptions) : super() {
    impl = MLNVectorSource(id, tiles, buildTileSetJson(tiles, options))
  }

  public actual fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature<Geometry, JsonObject?>> {
    val sourceLayersJson =
      if (sourceLayerIds.isNotEmpty()) {
        JsonArray(sourceLayerIds.map { JsonPrimitive(it) }).toString()
      } else {
        null
      }

    val filterJson =
      predicate.takeUnless { it == const(true) }?.compile(ExpressionContext.None)?.toJsonString()

    val resultJson = impl.querySourceFeatures(sourceLayersJson, filterJson)
    return try {
      val fc: FeatureCollection<Geometry?, JsonObject?> = FeatureCollection.fromJson(resultJson)
      @Suppress("UNCHECKED_CAST")
      fc.features as List<Feature<Geometry, JsonObject?>>
    } catch (_: Exception) {
      emptyList()
    }
  }
}
