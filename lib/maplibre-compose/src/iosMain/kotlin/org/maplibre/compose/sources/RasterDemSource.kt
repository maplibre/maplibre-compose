package org.maplibre.compose.sources

import org.maplibre.compose.util.toMLNCoordinateBounds
import platform.Foundation.NSURL
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNDEMEncodingMapbox
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNDEMEncodingTerrarium
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNRasterDEMSource
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileCoordinateSystemTMS
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileCoordinateSystemXYZ
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionAttributionHTMLString
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionCoordinateBounds
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionDEMEncoding
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionMaximumZoomLevel
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionMinimumZoomLevel
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionTileCoordinateSystem
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionTileSize

public actual class RasterDemSource : Source {
  override val impl: MLNRasterDEMSource

  internal constructor(source: MLNRasterDEMSource) {
    this.impl = source
  }

  public actual constructor(id: String, uri: String, tileSize: Int) : super() {
    this.impl =
      MLNRasterDEMSource(
        identifier = id,
        configurationURL = NSURL(string = uri),
        tileSize = tileSize.toDouble(),
      )
  }

  public actual constructor(
    id: String,
    tiles: List<String>,
    options: TileSetOptions,
    tileSize: Int,
    demEncoding: RasterDemEncoding,
  ) : super() {
    this.impl =
      MLNRasterDEMSource(
        identifier = id,
        tileURLTemplates = tiles,
        options =
          buildMap {
            this[MLNTileSourceOptionDEMEncoding] =
              when (demEncoding) {
                RasterDemEncoding.Mapbox -> MLNDEMEncodingMapbox
                RasterDemEncoding.Terrarium -> MLNDEMEncodingTerrarium
                else -> demEncoding.value // not supported but let's not crash it
              }
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
