package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.util.jso
import org.maplibre.kmp.js.stylespec.sources.GeoJSONSourceSpecification
import org.maplibre.kmp.js.stylespec.sources.GeoJsonDataDefinition
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.toJson
import org.maplibre.kmp.js.source.GeoJsonSource as JsGeoJsonSource
import org.maplibre.kmp.js.source.Source as JsSource

public actual class GeoJsonSource public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : Source() {

  override val spec: GeoJSONSourceSpecification = jso {
    this.type = "geojson"
    this.data = data.unwrap()
  }

  private var internalBinding: JsGeoJsonSource? = null
  override val impl: JsGeoJsonSource
    get() = requireNotNull(internalBinding)

  override fun bind(source: JsSource) {
    internalBinding = source.unsafeCast<JsGeoJsonSource>()
  }

  public actual fun setData(data: GeoJsonData) {
    if (internalBinding != null) {
      internalBinding?.setData(data.unwrap())
    } else {
      spec.data = data.unwrap()
    }
  }

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean {
    TODO()
  }

  public actual fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    TODO()
  }

  public actual fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> {
    TODO()
  }

  public actual fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> {
    TODO()
  }

  private fun GeoJsonData.unwrap(): GeoJsonDataDefinition {
    return when (this) {
      is GeoJsonData.Features -> geoJson.toJson()
      is GeoJsonData.JsonString -> json
      is GeoJsonData.Uri -> uri
    }.unsafeCast<GeoJsonDataDefinition>()
  }
}
