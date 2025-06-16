package dev.sargunv.maplibrecompose.core.source

import MapLibre.MLNFeatureProtocol
import MapLibre.MLNTileCoordinateSystemTMS
import MapLibre.MLNTileCoordinateSystemXYZ
import MapLibre.MLNTileSourceOptionAttributionHTMLString
import MapLibre.MLNTileSourceOptionCoordinateBounds
import MapLibre.MLNTileSourceOptionMaximumZoomLevel
import MapLibre.MLNTileSourceOptionMinimumZoomLevel
import MapLibre.MLNTileSourceOptionTileCoordinateSystem
import MapLibre.MLNVectorTileSource
import dev.sargunv.maplibrecompose.core.util.toFeature
import dev.sargunv.maplibrecompose.core.util.toMLNCoordinateBounds
import dev.sargunv.maplibrecompose.core.util.toNSPredicate
import dev.sargunv.maplibrecompose.expressions.ExpressionContext
import dev.sargunv.maplibrecompose.expressions.ast.Expression
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.value.BooleanValue
import io.github.dellisd.spatialk.geojson.Feature
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
