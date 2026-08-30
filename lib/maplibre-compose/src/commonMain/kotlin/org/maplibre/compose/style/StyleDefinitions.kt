package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.RasterDemEncoding
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.spatialk.geojson.Position

/** One immutable source definition that can be installed in any loaded style. */
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
    val image: ImageSnapshot?,
  ) : SourceDefinition

  data class CustomGeometry(
    override val id: String,
    val options: CustomGeometrySourceOptions,
    val provider: GeometryTileProvider,
  ) : SourceDefinition

  data class CustomVector(
    override val id: String,
    val options: CustomVectorSourceOptions,
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

/** One immutable layer definition. Layer placement belongs to the desired style revision. */
internal data class LayerDefinition(
  val id: String,
  val type: String,
  val sourceId: String?,
  val value: JsonObject,
  val unsupportedProperties: Map<String, String> = emptyMap(),
)

/**
 * One resolved image definition. It contains no painter, composition, or loaded-style reference.
 */
internal data class StyleImageDefinition(
  val id: String,
  val image: ImageSnapshot,
  val sdf: Boolean,
  val stretch: ImageStretch?,
)

/** An engine-neutral image value that stores an independent copy of its pixels. */
internal class ImageSnapshot
private constructor(
  val width: Int,
  val height: Int,
  private val pixels: IntArray,
) {
  fun toImageBitmap(): ImageBitmap = pixels.copyOf().toImageBitmap(width, height)

  override fun equals(other: Any?): Boolean =
    other is ImageSnapshot &&
      width == other.width &&
      height == other.height &&
      pixels.contentEquals(other.pixels)

  override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()

  companion object {
    fun capture(bitmap: ImageBitmap): ImageSnapshot {
      val pixels = IntArray(bitmap.width * bitmap.height)
      bitmap.readPixels(pixels)
      return ImageSnapshot(bitmap.width, bitmap.height, pixels)
    }
  }
}

internal data class RasterDemCapabilities(
  val supportsCustomDemEncoding: Boolean,
  val supportsRasterDemScheme: Boolean,
)
