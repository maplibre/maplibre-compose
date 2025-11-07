package org.maplibre.compose.sources

import MapLibre.MLNDEMEncodingMapbox
import MapLibre.MLNDEMEncodingTerrarium
import MapLibre.MLNRasterDEMSource
import MapLibre.MLNTileCoordinateSystemTMS
import MapLibre.MLNTileCoordinateSystemXYZ
import MapLibre.MLNTileSourceOptionAttributionHTMLString
import MapLibre.MLNTileSourceOptionCoordinateBounds
import MapLibre.MLNTileSourceOptionDEMEncoding
import MapLibre.MLNTileSourceOptionMaximumZoomLevel
import MapLibre.MLNTileSourceOptionMinimumZoomLevel
import MapLibre.MLNTileSourceOptionTileCoordinateSystem
import MapLibre.MLNTileSourceOptionTileSize
import org.maplibre.compose.util.toMLNCoordinateBounds
import platform.Foundation.NSURL

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
