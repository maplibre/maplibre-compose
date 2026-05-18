package org.maplibre.compose.sources

import org.maplibre.kmp.native.style.sources.RasterDemSource as MLNRasterDemSource

public actual class RasterDemSource : Source {
  override val impl: MLNRasterDemSource

  public actual constructor(id: String, uri: String, tileSize: Int) : super() {
    impl = MLNRasterDemSource(id, uri, tileSize)
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
    demEncoding: RasterDemEncoding,
  ) : super() {
    impl =
      MLNRasterDemSource(
        id,
        tiles,
        buildTileSetJson(tiles, options, tileSize = tileSize, demEncoding = demEncoding.value),
      )
  }
}
