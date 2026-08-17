package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.util.toFeature
import org.maplibre.compose.util.toMLNCoordinateBounds
import org.maplibre.compose.util.toNSPredicate
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import platform.Foundation.NSURL
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNFeatureProtocol
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileCoordinateSystemTMS
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileCoordinateSystemXYZ
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionAttributionHTMLString
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionCoordinateBounds
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionMaximumZoomLevel
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionMinimumZoomLevel
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTileSourceOptionTileCoordinateSystem
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNVectorTileSource

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
  ): List<Feature<Geometry, JsonObject?>> {
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

  public actual fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    featureStateUnavailable()
  }

  public actual fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    featureStateUnavailable()

  public actual fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String?,
  ) {
    featureStateUnavailable()
  }

  public actual fun resetFeatureStates(sourceLayerId: String) {
    featureStateUnavailable()
  }
}
