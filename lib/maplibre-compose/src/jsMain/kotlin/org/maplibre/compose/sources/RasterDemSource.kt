package org.maplibre.compose.sources

import org.maplibre.kmp.js.stylespec.sources.SourceSpecification

public actual class RasterDemSource : Source {
  public actual constructor(id: String, uri: String, tileSize: Int) : super() {
    this.impl = TODO()
  }

  override val spec: SourceSpecification
    get() = TODO("Not yet implemented")

  override fun bind(source: org.maplibre.kmp.js.source.Source) {
    TODO("Not yet implemented")
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
