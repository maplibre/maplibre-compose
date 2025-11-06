package org.maplibre.compose.sources

import org.maplibre.android.style.sources.RasterDemSource as MLNRasterDemSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.compose.util.correctedAndroidUri
import org.maplibre.compose.util.toLatLngBounds

public actual class RasterDemSource : Source {
  override val impl: MLNRasterDemSource

  internal constructor(source: MLNRasterDemSource) {
    impl = source
  }

  public actual constructor(id: String, uri: String, tileSize: Int) {
    impl = MLNRasterDemSource(id, uri.correctedAndroidUri(), tileSize)
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
    demEncoding: RasterDemEncoding,
  ) {
    impl =
      MLNRasterDemSource(
        id,
        TileSet(
            "{\"type\": \"raster-dem\"}",
            *tiles.map { it.correctedAndroidUri() }.toTypedArray(),
          )
          .apply {
            minZoom = options.minZoom.toFloat()
            maxZoom = options.maxZoom.toFloat()
            encoding = demEncoding.value
            options.boundingBox?.let { setBounds(it.toLatLngBounds()) }
            attribution = options.attributionHtml
          },
        tileSize,
      )
  }
}
