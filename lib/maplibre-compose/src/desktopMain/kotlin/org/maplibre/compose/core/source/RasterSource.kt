package org.maplibre.compose.core.source

public actual class RasterSource : Source {
  public actual constructor(id: String, uri: String, tileSize: Int) : super() {
    this.impl = TODO()
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
  ) : super() {
    this.impl = TODO()
  }

  override val impl: Nothing
}
