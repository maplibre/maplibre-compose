package org.maplibre.compose.sources

import MapLibre.MLNShapeSource
import MapLibre.MLNShapeSourceOptionBuffer
import MapLibre.MLNShapeSourceOptionClusterMinPoints
import MapLibre.MLNShapeSourceOptionClusterProperties
import MapLibre.MLNShapeSourceOptionClusterRadius
import MapLibre.MLNShapeSourceOptionClustered
import MapLibre.MLNShapeSourceOptionLineDistanceMetrics
import MapLibre.MLNShapeSourceOptionMaximumZoomLevel
import MapLibre.MLNShapeSourceOptionMaximumZoomLevelForClustering
import MapLibre.MLNShapeSourceOptionMinimumZoomLevel
import MapLibre.MLNShapeSourceOptionSimplificationTolerance
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.util.toMLNShape
import org.maplibre.compose.util.toNSExpression
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

public actual class GeoJsonSource : Source {
  override val impl: MLNShapeSource

  internal constructor(source: MLNShapeSource) {
    impl = source
  }

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) {
    impl =
      when (data) {
        is GeoJsonData.Features ->
          MLNShapeSource(
            identifier = id,
            shape = data.geoJson.toMLNShape(),
            options = buildOptionMap(options),
          )
        is GeoJsonData.JsonString ->
          MLNShapeSource(
            identifier = id,
            shape = data.json.toMLNShape(),
            options = buildOptionMap(options),
          )
        is GeoJsonData.Uri ->
          MLNShapeSource(
            identifier = id,
            URL = NSURL(string = data.uri),
            options = buildOptionMap(options),
          )
      }
  }

  private fun buildOptionMap(options: GeoJsonOptions) =
    buildMap<Any?, Any?> {
      put(MLNShapeSourceOptionMinimumZoomLevel, NSNumber(options.minZoom))
      put(MLNShapeSourceOptionMaximumZoomLevel, NSNumber(options.maxZoom))
      put(MLNShapeSourceOptionBuffer, NSNumber(options.buffer))
      put(MLNShapeSourceOptionLineDistanceMetrics, NSNumber(options.lineMetrics))
      put(MLNShapeSourceOptionSimplificationTolerance, NSNumber(options.tolerance.toDouble()))
      put(MLNShapeSourceOptionClustered, NSNumber(options.cluster))
      put(MLNShapeSourceOptionMaximumZoomLevelForClustering, NSNumber(options.clusterMaxZoom))
      put(MLNShapeSourceOptionClusterRadius, NSNumber(options.clusterRadius))
      put(MLNShapeSourceOptionClusterMinPoints, NSNumber(options.clusterMinPoints))
      put(
        MLNShapeSourceOptionClusterProperties,
        options.clusterProperties.mapValues { (name, aggregator) ->
          listOf(
            aggregator.reducer.compile(ExpressionContext.None).toNSExpression(),
            aggregator.mapper.compile(ExpressionContext.None).toNSExpression(),
          )
        },
      )
    }

  public actual fun setData(data: GeoJsonData) {
    when (data) {
      is GeoJsonData.Uri -> impl.setURL(NSURL(string = data.uri))
      is GeoJsonData.Features -> impl.setShape(data.geoJson.toMLNShape())
      is GeoJsonData.JsonString -> impl.setShape(data.json.toMLNShape())
    }
  }
}
