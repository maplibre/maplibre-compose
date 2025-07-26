package org.maplibre.compose.sources

import MapLibre.MLNRasterTileSource
import MapLibre.MLNTileCoordinateSystemTMS
import MapLibre.MLNTileCoordinateSystemXYZ
import MapLibre.MLNTileSourceOptionAttributionHTMLString
import MapLibre.MLNTileSourceOptionCoordinateBounds
import MapLibre.MLNTileSourceOptionMaximumZoomLevel
import MapLibre.MLNTileSourceOptionMinimumZoomLevel
import MapLibre.MLNTileSourceOptionTileCoordinateSystem
import MapLibre.MLNTileSourceOptionTileSize
import org.maplibre.compose.util.toMLNCoordinateBounds
import platform.Foundation.NSURL

public actual class RasterSource : Source {
  override val impl: MLNRasterTileSource

  internal constructor(source: MLNRasterTileSource) {
    this.impl = source
  }

  public actual constructor(id: String, uri: String, tileSize: Int) : super() {
    this.impl = MLNRasterTileSource(id, NSURL(string = uri), tileSize.toDouble())
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
  ) : super() {
    this.impl =
      MLNRasterTileSource(
        identifier = id,
        tileURLTemplates = tiles,
        options =
          buildMap {
            this[MLNTileSourceOptionMinimumZoomLevel] = options.minZoom.toDouble()
            this[MLNTileSourceOptionMaximumZoomLevel] = options.maxZoom.toDouble()
            this[MLNTileSourceOptionTileSize] = tileSize.toDouble()
            this[MLNTileSourceOptionTileCoordinateSystem] =
              when (options.tileCoordinateSystem) {
                TileCoordinateSystem.XYZ -> MLNTileCoordinateSystemXYZ
                TileCoordinateSystem.TMS -> MLNTileCoordinateSystemTMS
              }
            if (options.boundingBox != null)
              this[MLNTileSourceOptionCoordinateBounds] =
                options.boundingBox.toMLNCoordinateBounds()
            if (options.attributionHtml != null)
              this[MLNTileSourceOptionAttributionHTMLString] = options.attributionHtml
          },
      )
  }
}
