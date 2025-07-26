package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/** A map data source of tiled map pictures. */
public expect class RasterSource : Source {

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
   */
  public constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions = TileSetOptions(),
    tileSize: Int = SourceDefaults.RASTER_TILE_SIZE,
  )
}

/** Remember a new [RasterSource] with the given [tileSize] from the given [uri]. */
@Composable
public fun rememberRasterSource(
  uri: String,
  tileSize: Int = SourceDefaults.RASTER_TILE_SIZE,
): RasterSource =
  key(uri, tileSize) {
    rememberUserSource(
      factory = { RasterSource(id = it, uri = uri, tileSize = tileSize) },
      update = {},
    )
  }

@Composable
public fun rememberRasterSource(
  tiles: List<String>,
  options: TileSetOptions = TileSetOptions(),
  tileSize: Int = SourceDefaults.RASTER_TILE_SIZE,
): RasterSource =
  key(tiles, options, tileSize) {
    rememberUserSource(
      factory = { RasterSource(id = it, tiles = tiles, options = options, tileSize = tileSize) },
      update = {},
    )
  }
