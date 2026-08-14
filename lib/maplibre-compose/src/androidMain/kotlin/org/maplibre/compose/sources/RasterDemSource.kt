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
    // TODO(#572): honour TileCoordinateSystem.TMS once Android uses the native core bindings.
    require(options.tileCoordinateSystem == TileCoordinateSystem.XYZ) {
      "The Android SDK does not apply TileSetOptions.tileCoordinateSystem to a raster-dem " +
        "source. Use TileCoordinateSystem.XYZ."
    }
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
