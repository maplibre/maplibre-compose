package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.style.RasterDemCapabilities
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleMutationException

/** A map data source of DEM raster images. */
public class RasterDemTileSource : Source {

  /** The tiled form's inputs, or null for a TileJSON URL or a source from the style. */
  private val tileSet: TileSet?

  private val json: JsonObject

  /**
   * @param id Unique identifier for this source
   * @param uri URI pointing to a JSON file that conforms to the
   *   [TileJSON specification](https://github.com/mapbox/tilejson-spec/)
   * @param tileSize width and height (measured in points) of each tiled image in the raster tile
   *   source. Defaults to 512, the style spec default.
   */
  public constructor(
    id: String,
    uri: String,
    tileSize: Int = 512,
  ) : super(id) {
    tileSet = null
    json = buildJsonObject {
      put("type", "raster-dem")
      put("url", uri)
      // "tileSize" is one of the few camelCase names in the style spec; "tilesize" is ignored.
      put("tileSize", tileSize)
    }
  }

  /**
   * @param id Unique identifier for this source
   * @param tiles List of URIs pointing to tile images
   * @param options see [TileSetOptions]. [TileSetOptions.scheme] is a vector and raster key; a
   *   raster-dem source has no `scheme` in the style spec. MapLibre Native still honours TMS;
   *   adding such a source to a MapLibre GL JS map fails.
   * @param tileSize width and height (measured in points) of each tiled image in the raster tile
   *   source. Defaults to 512, the style spec default.
   * @param encoding How the tiles store elevation. Defaults to [RasterDemEncoding.Mapbox].
   * @param redFactor The number MapLibre multiplies the red channel by when decoding elevation.
   *   Used only with [RasterDemEncoding.Custom]. Defaults to 1.
   * @param greenFactor The number MapLibre multiplies the green channel by when decoding elevation.
   *   Used only with [RasterDemEncoding.Custom]. Defaults to 1.
   * @param blueFactor The number MapLibre multiplies the blue channel by when decoding elevation.
   *   Used only with [RasterDemEncoding.Custom]. Defaults to 1.
   * @param baseShift The number MapLibre subtracts from the sum of the scaled channels when
   *   decoding elevation. Used only with [RasterDemEncoding.Custom]. Defaults to 0.
   */
  public constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions = TileSetOptions(),
    tileSize: Int = 512,
    encoding: RasterDemEncoding = RasterDemEncoding.Mapbox,
    redFactor: Float = 1f,
    greenFactor: Float = 1f,
    blueFactor: Float = 1f,
    baseShift: Float = 0f,
  ) : super(id) {
    val tileSet =
      TileSet(
        tiles.toList(),
        options,
        tileSize,
        RasterDemDecoding(encoding, redFactor, greenFactor, blueFactor, baseShift),
      )
    this.tileSet = tileSet
    json =
      rasterDemSourceJson(
        tiles = tileSet.tiles,
        options = tileSet.options,
        tileSize = tileSet.tileSize,
        decoding = tileSet.decoding,
        capabilities =
          RasterDemCapabilities(
            supportsCustomDemEncoding = true,
            supportsRasterDemScheme = true,
          ),
      )
  }

  internal constructor(id: String, definition: JsonObject) : super(id) {
    tileSet = null
    json = definition
  }

  override fun toJson(): JsonObject = json

  override fun definition(): SourceDefinition {
    val tileSet = tileSet ?: return super.definition()
    return SourceDefinition.RasterDem(
      id = id,
      tiles = tileSet.tiles,
      options = tileSet.options,
      tileSize = tileSet.tileSize,
      decoding = tileSet.decoding,
    )
  }

  private class TileSet(
    val tiles: List<String>,
    val options: TileSetOptions,
    val tileSize: Int,
    val decoding: RasterDemDecoding,
  )
}

/** The style spec's `encoding` and the custom factors that go with it. */
internal data class RasterDemDecoding(
  val encoding: RasterDemEncoding,
  val redFactor: Float,
  val greenFactor: Float,
  val blueFactor: Float,
  val baseShift: Float,
)

internal fun rasterDemSourceJson(
  tiles: List<String>,
  options: TileSetOptions,
  tileSize: Int,
  decoding: RasterDemDecoding,
  capabilities: RasterDemCapabilities,
): JsonObject {
  if (!capabilities.supportsRasterDemScheme && options.scheme != TileScheme.Xyz) {
    throw StyleMutationException(
      "this engine has no scheme on a raster-dem source and reads only XYZ tiles; use " +
        "TileScheme.Xyz",
      null,
    )
  }
  val includeScheme = capabilities.supportsRasterDemScheme
  val custom = decoding.encoding == RasterDemEncoding.Custom
  val customSupported = capabilities.supportsCustomDemEncoding
  return buildJsonObject {
    put("type", "raster-dem")
    putJsonArray("tiles") { tiles.forEach { add(it) } }
    put("tileSize", tileSize)
    put(
      "encoding",
      if (custom && !customSupported) RasterDemEncoding.Mapbox.value else decoding.encoding.value,
    )
    if (custom && customSupported) {
      put("redFactor", decoding.redFactor)
      put("greenFactor", decoding.greenFactor)
      put("blueFactor", decoding.blueFactor)
      put("baseShift", decoding.baseShift)
    }
    putTileSetOptions(options, includeScheme = includeScheme)
  }
}

/** Remember a new [RasterDemTileSource] with the given [tileSize] from the given [uri]. */
@Composable
public fun rememberRasterDemTileSource(
  uri: String,
  tileSize: Int = 512,
): RasterDemTileSource =
  key(uri, tileSize) {
    rememberUserSource { RasterDemTileSource(id = it, uri = uri, tileSize = tileSize) }
  }

/**
 * Remember a new [RasterDemTileSource] from the given [tiles]. The parameters are those of the
 * [RasterDemTileSource] constructor.
 */
@Composable
public fun rememberRasterDemTileSource(
  tiles: List<String>,
  options: TileSetOptions = TileSetOptions(),
  tileSize: Int = 512,
  encoding: RasterDemEncoding = RasterDemEncoding.Mapbox,
  redFactor: Float = 1f,
  greenFactor: Float = 1f,
  blueFactor: Float = 1f,
  baseShift: Float = 0f,
): RasterDemTileSource =
  key(tiles, options, tileSize, encoding, redFactor, greenFactor, blueFactor, baseShift) {
    rememberUserSource {
      RasterDemTileSource(
        id = it,
        tiles = tiles,
        options = options,
        tileSize = tileSize,
        encoding = encoding,
        redFactor = redFactor,
        greenFactor = greenFactor,
        blueFactor = blueFactor,
        baseShift = baseShift,
      )
    }
  }
