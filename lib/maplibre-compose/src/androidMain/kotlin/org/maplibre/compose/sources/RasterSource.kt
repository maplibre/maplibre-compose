package org.maplibre.compose.sources

import org.maplibre.android.style.sources.RasterSource as MLNRasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.compose.util.correctedAndroidUri
import org.maplibre.compose.util.toLatLngBounds

public actual class RasterSource : Source {
  override val impl: MLNRasterSource

  internal constructor(source: MLNRasterSource) {
    impl = source
  }

  public actual constructor(id: String, uri: String, tileSize: Int) {
    impl = MLNRasterSource(id, uri.correctedAndroidUri(), tileSize)
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
  ) {
    impl =
      MLNRasterSource(
        id,
        TileSet("{\"type\": \"raster\"}", *tiles.map { it.correctedAndroidUri() }.toTypedArray())
          .apply {
            minZoom = options.minZoom.toFloat()
            maxZoom = options.maxZoom.toFloat()
            scheme =
              when (options.tileCoordinateSystem) {
                TileCoordinateSystem.XYZ -> "xyz"
                TileCoordinateSystem.TMS -> "tms"
              }
            options.boundingBox?.let { setBounds(it.toLatLngBounds()) }
            attribution = options.attributionHtml
          },
        tileSize,
      )
  }
}
