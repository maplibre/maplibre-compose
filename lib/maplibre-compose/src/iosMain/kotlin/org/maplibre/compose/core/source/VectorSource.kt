package org.maplibre.compose.core.source

import MapLibre.MLNFeatureProtocol
import MapLibre.MLNTileCoordinateSystemTMS
import MapLibre.MLNTileCoordinateSystemXYZ
import MapLibre.MLNTileSourceOptionAttributionHTMLString
import MapLibre.MLNTileSourceOptionCoordinateBounds
import MapLibre.MLNTileSourceOptionMaximumZoomLevel
import MapLibre.MLNTileSourceOptionMinimumZoomLevel
import MapLibre.MLNTileSourceOptionTileCoordinateSystem
import MapLibre.MLNVectorTileSource
import io.github.dellisd.spatialk.geojson.Feature
import org.maplibre.compose.core.util.toFeature
import org.maplibre.compose.core.util.toMLNCoordinateBounds
import org.maplibre.compose.core.util.toNSPredicate
import org.maplibre.compose.expressions.ExpressionContext
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import platform.Foundation.NSURL

public actual class VectorSource : Source {
  override val impl: MLNVectorTileSource

  internal constructor(source: MLNVectorTileSource) {
    impl = source
  }

  public actual constructor(id: String, uri: String) : super() {
    this.impl = MLNVectorTileSource(id, NSURL(string = uri))
  }

  public actual constructor(id: String, tiles: List<String>, options: TileSetOptions) : super() {
    this.impl =
      MLNVectorTileSource(
        identifier = id,
        tileURLTemplates = tiles,
        options =
          buildMap {
            put(MLNTileSourceOptionMinimumZoomLevel, options.minZoom.toDouble())
            put(MLNTileSourceOptionMaximumZoomLevel, options.maxZoom.toDouble())
            put(
              MLNTileSourceOptionTileCoordinateSystem,
              when (options.tileCoordinateSystem) {
                TileCoordinateSystem.XYZ -> MLNTileCoordinateSystemXYZ
                TileCoordinateSystem.TMS -> MLNTileCoordinateSystemTMS
              },
            )
            if (options.boundingBox != null)
              put(MLNTileSourceOptionCoordinateBounds, options.boundingBox.toMLNCoordinateBounds())
            if (options.attributionHtml != null)
              put(MLNTileSourceOptionAttributionHTMLString, options.attributionHtml)
          },
      )
  }

  public actual fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature> {
    return impl
      .featuresInSourceLayersWithIdentifiers(
        sourceLayerIdentifiers = sourceLayerIds,
        predicate =
          predicate
            .takeUnless { it == const(true) }
            ?.compile(ExpressionContext.None)
            ?.toNSPredicate(),
      )
      .map { (it as MLNFeatureProtocol).toFeature() }
  }
}
