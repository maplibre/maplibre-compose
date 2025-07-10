package dev.sargunv.maplibrecompose.compose.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.sargunv.maplibrecompose.core.source.TileSetOptions
import dev.sargunv.maplibrecompose.core.source.VectorSource

/** Remember a new [VectorSource] from the given [uri]. */
@Composable
public fun rememberVectorSource(uri: String): VectorSource =
  key(uri) { rememberUserSource(factory = { VectorSource(id = it, uri = uri) }, update = {}) }

@Composable
public fun rememberVectorSource(
  tiles: List<String>,
  options: TileSetOptions = TileSetOptions(),
): VectorSource =
  key(tiles, options) {
    rememberUserSource(
      factory = { VectorSource(id = it, tiles = tiles, options = options) },
      update = {},
    )
  }
