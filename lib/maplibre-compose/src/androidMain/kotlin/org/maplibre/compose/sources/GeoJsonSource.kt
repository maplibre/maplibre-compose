package org.maplibre.compose.sources

import java.net.URI
import org.maplibre.android.style.sources.GeoJsonOptions as MLNGeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource as MLNGeoJsonSource
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.util.correctedAndroidUri
import org.maplibre.compose.util.toMLNExpression
import org.maplibre.spatialk.geojson.toJson

public actual class GeoJsonSource : Source {
  override val impl: MLNGeoJsonSource

  internal constructor(source: MLNGeoJsonSource) {
    impl = source
  }

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) {
    impl =
      when (data) {
        is GeoJsonData.Features ->
          MLNGeoJsonSource(id, data.geoJson.toJson(), buildOptionMap(options))
        is GeoJsonData.JsonString -> MLNGeoJsonSource(id, data.json, buildOptionMap(options))
        is GeoJsonData.Uri -> MLNGeoJsonSource(id, URI(data.uri), buildOptionMap(options))
      }
  }

  private fun buildOptionMap(options: GeoJsonOptions) =
    MLNGeoJsonOptions().apply {
      withMinZoom(options.minZoom)
      withMaxZoom(options.maxZoom)
      withBuffer(options.buffer)
      withTolerance(options.tolerance)
      withLineMetrics(options.lineMetrics)
      withCluster(options.cluster)
      withClusterMaxZoom(options.clusterMaxZoom)
      withClusterRadius(options.clusterRadius)
      withClusterMinPoints(options.clusterMinPoints)
      options.clusterProperties.forEach { (name, aggregator) ->
        withClusterProperty(
          name,
          aggregator.reducer.compile(ExpressionContext.None).toMLNExpression()!!,
          aggregator.mapper.compile(ExpressionContext.None).toMLNExpression()!!,
        )
      }
    }

  public actual fun setData(data: GeoJsonData) {
    when (data) {
      is GeoJsonData.Features -> impl.setGeoJson(data.geoJson.toJson())
      is GeoJsonData.JsonString -> impl.setGeoJson(data.json)
      is GeoJsonData.Uri -> impl.setUri(data.uri.correctedAndroidUri())
    }
  }
}
