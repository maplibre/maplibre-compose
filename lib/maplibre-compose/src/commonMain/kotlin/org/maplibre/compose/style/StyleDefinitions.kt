package org.maplibre.compose.style

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.RasterDemDecoding
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.style.internal.StyleValue
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
    val decoding: RasterDemDecoding,
  ) : SourceDefinition
}

/** Defines an immutable layer. The desired style revision specifies its placement. */
internal data class LayerDefinition(
  val properties: Map<String, StyleValue>,
  val unsupportedProperties: Map<String, String> = emptyMap(),
  val filterUnsupportedProperties: Boolean = false,
) {
  constructor(
    value: JsonObject,
    unsupportedProperties: Map<String, String> = emptyMap(),
    filterUnsupportedProperties: Boolean = false,
  ) : this(
    value.mapValues { StyleValue.Json(it.value) },
    unsupportedProperties,
    filterUnsupportedProperties,
  )

  val value: JsonObject by lazy { JsonObject(properties.mapValues { it.value.json }) }
  val id: String = (properties.getValue("id").json as JsonPrimitive).content
  val type: String = (properties["type"]?.json as? JsonPrimitive)?.content.orEmpty()
  val sourceId: String? = (properties["source"]?.json as? JsonPrimitive)?.content
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
  if (properties === other.properties) return true
  return properties.all { (name, value) ->
    name in MutableLayerProperties || other.properties[name] == value
  } && other.properties.keys.all { it in MutableLayerProperties || it in properties }
}

private val MutableLayerProperties = setOf("layout", "paint", "filter", "minzoom", "maxzoom")
