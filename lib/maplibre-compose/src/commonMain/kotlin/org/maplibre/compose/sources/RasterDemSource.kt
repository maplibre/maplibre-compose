package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/** A map data source of DEM raster images. */
public expect class RasterDemSource : Source {

  /**
   * @param id Unique identifier for this source
   * @param uri URI pointing to a JSON file that conforms to the
   *   [TileJSON specification](https://github.com/mapbox/tilejson-spec/)
   * @param tileSize width and height (measured in points) of each tiled image in the raster tile
   *   source
   */
  public constructor(id: String, uri: String, tileSize: Int = 512)

  /**
   * @param id Unique identifier for this source
   * @param tiles List of URIs pointing to tile images
   * @param options see [TileSetOptions]
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
  )
}

/** The encoding used by a Raster DEM source. */
public sealed class RasterDemEncoding(internal val value: String) {
  /** Mapbox Terrain RGB tiles. See https://www.mapbox.com/help/access-elevation-data/#mapbox-terrain-rgb for more info */
  public data object Mapbox : RasterDemEncoding("mapbox")

  /** Terrarium format PNG tiles. See https://aws.amazon.com/es/public-datasets/terrain/ for more info. */
  public data object Terrarium : RasterDemEncoding("terrarium")
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
      factory = { RasterDemSource(id = it, tiles = tiles, options = options, tileSize = tileSize, demEncoding = encoding) },
      update = {},
    )
  }
