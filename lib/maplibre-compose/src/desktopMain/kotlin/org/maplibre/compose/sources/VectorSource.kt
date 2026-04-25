package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.util.jsonEscape
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue

public actual class VectorSource : Source {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val _sourceId: String
  private val json: String

  public actual constructor(id: String, uri: String) {
    _sourceId = id
    json = """{"type":"vector","url":${jsonEscape(uri)}}"""
  }

  public actual constructor(id: String, tiles: List<String>, options: TileSetOptions) {
    _sourceId = id
    json = buildString {
      append("""{"type":"vector","tiles":[""")
      tiles.joinTo(this, ",") { jsonEscape(it) }
      append("]")
      append(""","minzoom":${options.minZoom}""")
      append(""","maxzoom":${options.maxZoom}""")
      if (options.tileCoordinateSystem == TileCoordinateSystem.TMS) append(""","scheme":"tms"""")
      options.boundingBox?.let { bb ->
        append(""","bounds":[${bb.southwest.longitude},${bb.southwest.latitude},${bb.northeast.longitude},${bb.northeast.latitude}]""")
      }
      options.attributionHtml?.let { append(""","attribution":${jsonEscape(it)}""") }
      append("}")
    }
  }

  override fun toJson(): String = json

  public actual fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature<Geometry, JsonObject?>> = emptyList()
}
