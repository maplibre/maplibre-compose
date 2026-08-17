package org.maplibre.compose.sources

import org.maplibre.compose.util.toMLNCoordinateBounds
import platform.Foundation.NSURL
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNRasterTileSource
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileCoordinateSystemTMS
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileCoordinateSystemXYZ
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionAttributionHTMLString
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionCoordinateBounds
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionMaximumZoomLevel
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionMinimumZoomLevel
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionTileCoordinateSystem
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionTileSize

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
