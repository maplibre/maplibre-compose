package org.maplibre.compose.style.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.maplibre.compose.core.source.Defaults
import org.maplibre.compose.core.source.RasterSource
import org.maplibre.compose.core.source.TileSetOptions

/** Remember a new [RasterSource] with the given [tileSize] from the given [uri]. */
@Composable
public fun rememberRasterSource(
  uri: String,
  tileSize: Int = Defaults.RASTER_TILE_SIZE,
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
  tileSize: Int = Defaults.RASTER_TILE_SIZE,
): RasterSource =
  key(tiles, options, tileSize) {
    rememberUserSource(
      factory = { RasterSource(id = it, tiles = tiles, options = options, tileSize = tileSize) },
      update = {},
    )
  }
