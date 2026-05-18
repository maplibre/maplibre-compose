package org.maplibre.compose.sources

import org.maplibre.kmp.native.style.sources.RasterSource as MLNRasterSource

public actual class RasterSource : Source {
  override val impl: MLNRasterSource

  public actual constructor(id: String, uri: String, tileSize: Int) : super() {
    impl = MLNRasterSource(id, uri, tileSize)
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
  ) : super() {
    impl = MLNRasterSource(id, tiles, buildTileSetJson(tiles, options, tileSize = tileSize))
  }
}
