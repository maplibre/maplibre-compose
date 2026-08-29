package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleMutationException

/** A map data source of DEM raster images. */
public class RasterDemSource : Source {

  /** The tiled form's inputs, or null when this source is a TileJSON URL. */
  private val tileSet: TileSet?

  private val json: JsonObject

  /**
   * @param id Unique identifier for this source
   * @param uri URI pointing to a JSON file that conforms to the
   *   [TileJSON specification](https://github.com/mapbox/tilejson-spec/)
   * @param tileSize width and height (measured in points) of each tiled image in the raster tile
   *   source
   */
  public constructor(
    id: String,
    uri: String,
    tileSize: Int = SourceDefaults.RASTER_TILE_SIZE,
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
   * @param options see [TileSetOptions]. [TileSetOptions.tileCoordinateSystem] is a vector and
   *   raster key; a raster-dem source has no `scheme` in the style spec. MapLibre Native still
   *   honours TMS; adding such a source to a MapLibre GL JS map fails.
   * @param tileSize width and height (measured in points) of each tiled image in the raster tile
   *   source
   * @param demEncoding The encoding used by this source. Mapbox Terrain RGB is used by default.
   */
  public constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions = TileSetOptions(),
    tileSize: Int = SourceDefaults.RASTER_TILE_SIZE,
    demEncoding: RasterDemEncoding = RasterDemEncoding.Mapbox,
  ) : super(id) {
    val tileSet = TileSet(tiles.toList(), options, tileSize, demEncoding)
    this.tileSet = tileSet
    json = tileSet.toJson(customEncoding = true, includeScheme = true)
  }

  override fun toJson(): JsonObject = json

  override fun definition(): SourceDefinition {
    val tileSet = tileSet ?: return super.definition()
    return SourceDefinition.RasterDem(id) { capabilities ->
      if (
        !capabilities.supportsRasterDemScheme &&
          tileSet.options.tileCoordinateSystem != TileCoordinateSystem.XYZ
      ) {
        throw StyleMutationException(
          "this engine has no scheme on a raster-dem source and reads only XYZ tiles; use " +
            "TileCoordinateSystem.XYZ",
          null,
        )
      }
      tileSet.toJson(
        customEncoding = capabilities.supportsCustomDemEncoding,
        includeScheme = capabilities.supportsRasterDemScheme,
      )
    }
  }

  private class TileSet(
    val tiles: List<String>,
    val options: TileSetOptions,
    val tileSize: Int,
    val demEncoding: RasterDemEncoding,
  ) {
    fun toJson(customEncoding: Boolean, includeScheme: Boolean): JsonObject = buildJsonObject {
      put("type", "raster-dem")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      put("tileSize", tileSize)
      val custom = demEncoding as? RasterDemEncoding.Custom
      // An engine that does not implement the custom factors decodes as Mapbox instead.
      put(
        "encoding",
        if (custom != null && !customEncoding) RasterDemEncoding.Mapbox.value
        else demEncoding.value,
      )
      if (custom != null && customEncoding) {
        put("redFactor", custom.redFactor)
        put("greenFactor", custom.greenFactor)
        put("blueFactor", custom.blueFactor)
        put("baseShift", custom.baseShift)
      }
      putTileSetOptions(options, includeScheme = includeScheme)
    }
  }
}

/** The encoding used by a Raster DEM source. */
public sealed class RasterDemEncoding(internal val value: String) {
  /**
   * Mapbox Terrain RGB tiles. See
   * https://www.mapbox.com/help/access-elevation-data/#mapbox-terrain-rgb for more info
   */
  public data object Mapbox : RasterDemEncoding("mapbox")

  /**
   * Terrarium format PNG tiles. See https://aws.amazon.com/es/public-datasets/terrain/ for more
   * info.
   */
  public data object Terrarium : RasterDemEncoding("terrarium")

  /**
   * Custom format using the given [redFactor], [blueFactor], [greenFactor] and [baseShift]
   * parameters.
   *
   * Unsupported on Android, iOS, and Desktop
   * [#2783](https://github.com/maplibre/maplibre-native/issues/2783).
   */
  public data class Custom(
    /** Value that will be multiplied by the red channel value when decoding. */
    public val redFactor: Float = 1f,
    /** Value that will be multiplied by the blue channel value when decoding. */
    public val blueFactor: Float = 1f,
    /** Value that will be multiplied by the green channel value when decoding. */
    public val greenFactor: Float = 1f,
    /** Value that will be added to the encoding mix when decoding. */
    public val baseShift: Float = 0f,
  ) : RasterDemEncoding("custom")
}

/** Remember a new [RasterDemSource] with the given [tileSize] from the given [uri]. */
@Composable
public fun rememberRasterDemSource(
  uri: String,
  tileSize: Int = SourceDefaults.RASTER_TILE_SIZE,
): RasterDemSource =
  key(uri, tileSize) {
    rememberUserSource(
      factory = { RasterDemSource(id = it, uri = uri, tileSize = tileSize) },
      update = {},
    )
  }

@Composable
public fun rememberRasterDemSource(
  tiles: List<String>,
  options: TileSetOptions = TileSetOptions(),
  tileSize: Int = SourceDefaults.RASTER_TILE_SIZE,
  encoding: RasterDemEncoding = RasterDemEncoding.Mapbox,
): RasterDemSource =
  key(tiles, options, tileSize) {
    rememberUserSource(
      factory = {
        RasterDemSource(
          id = it,
          tiles = tiles,
          options = options,
          tileSize = tileSize,
          demEncoding = encoding,
        )
      },
      update = {},
    )
  }
