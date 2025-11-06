package org.maplibre.compose.sources

public actual class RasterDemSource : Source {
  public actual constructor(id: String, uri: String, tileSize: Int) : super() {
    this.impl = TODO()
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
    demEncoding: RasterDemEncoding,
  ) : super() {
    this.impl = TODO()
  }

  override val impl: Nothing
}
