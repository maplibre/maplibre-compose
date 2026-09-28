package org.maplibre.compose.style

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.RasterDemEncoding
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Position

/** Defines an immutable source that can be installed in any loaded style. */
internal sealed interface SourceDefinition {
  val id: String

  data class Json(override val id: String, val value: JsonObject) : SourceDefinition

  data class GeoJson(
    override val id: String,
    val data: GeoJsonData,
    val options: GeoJsonOptions,
  ) : SourceDefinition

  data class Image(
    override val id: String,
    val value: JsonObject,
    val coordinates: List<Position>,
    val image: PreparedImage?,
  ) : SourceDefinition

  data class CustomGeometry(
    override val id: String,
    val options: CustomGeometrySourceOptions,
    val provider: GeometryTileProvider,
  ) : SourceDefinition

  data class CustomVector(
    override val id: String,
    val options: CustomVectorTileSourceOptions,
    val provider: VectorTileProvider,
  ) : SourceDefinition

  data class RasterDem(
    override val id: String,
    val tiles: List<String>,
    val options: TileSetOptions,
    val tileSize: Int,
    val demEncoding: RasterDemEncoding,
  ) : SourceDefinition
}

/** Defines an immutable layer. The desired style revision specifies its placement. */
internal data class LayerDefinition(
  val value: JsonObject,
  val unsupportedProperties: Map<String, String> = emptyMap(),
  val filterUnsupportedProperties: Boolean = false,
) {
  val id: String = (value.getValue("id") as JsonPrimitive).content
  val type: String = (value["type"] as? JsonPrimitive)?.content.orEmpty()
  val sourceId: String? = (value["source"] as? JsonPrimitive)?.content
}

/** Defines a resolved image without a painter, composition, or loaded-style reference. */
internal data class StyleImageDefinition(
  val id: String,
  val image: PreparedImage,
  val sdf: Boolean,
  val stretch: ImageStretch?,
)

internal data class RasterDemCapabilities(
  val supportsCustomDemEncoding: Boolean,
  val supportsRasterDemScheme: Boolean,
)

/** Preserve every engine-reported field when describing an existing layer. */
internal fun layerDefinitionFromJson(id: String, value: JsonObject): LayerDefinition =
  LayerDefinition(JsonObject(value + ("id" to JsonPrimitive(id))))

/** Compares construction inputs without allocating filtered property maps. */
internal fun LayerDefinition.hasSameConstructionProperties(other: LayerDefinition): Boolean {
  if (value === other.value) return true
  return value.all { (name, value) ->
    name in MUTABLE_LAYER_PROPERTIES || other.value[name] == value
  } && other.value.keys.all { it in MUTABLE_LAYER_PROPERTIES || it in value }
}

private val MUTABLE_LAYER_PROPERTIES = setOf("layout", "paint", "filter", "minzoom", "maxzoom")
