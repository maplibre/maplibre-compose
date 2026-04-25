package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.util.jsonEscape
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.toJson

public actual class GeoJsonSource : Source {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val _sourceId: String

  private val options: GeoJsonOptions
  private var _data: GeoJsonData

  // Set by DesktopStyle after adding to the map; used to push data updates
  internal var style: org.maplibre.compose.style.DesktopStyle? = null

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) {
    _sourceId = id
    _data = data
    this.options = options
  }

  public actual fun setData(data: GeoJsonData) {
    _data = data
    // Use the direct GeoJSON update path to avoid a remove+add cycle, which would fail
    // if any layer currently references this source.
    style?.updateGeoJsonData(this, dataToJson(data))
  }

  // Cluster operations are not supported on desktop
  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean = false

  public actual fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double = 0.0

  public actual fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> = FeatureCollection<Geometry, JsonObject?>(emptyList())

  public actual fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> = FeatureCollection<Geometry, JsonObject?>(emptyList())

  override fun toJson(): String = buildString {
    append("""{"type":"geojson"""")
    append(""","data":""")
    append(dataToJson(_data))
    if (options.minZoom != SourceDefaults.MIN_ZOOM) append(""","minzoom":${options.minZoom}""")
    if (options.maxZoom != SourceDefaults.MAX_ZOOM) append(""","maxzoom":${options.maxZoom}""")
    if (options.buffer != 128) append(""","buffer":${options.buffer}""")
    if (options.tolerance != 0.375f) append(""","tolerance":${options.tolerance}""")
    if (options.cluster) {
      append(""","cluster":true""")
      append(""","clusterRadius":${options.clusterRadius}""")
      append(""","clusterMaxZoom":${options.clusterMaxZoom}""")
      append(""","clusterMinPoints":${options.clusterMinPoints}""")
    }
    if (options.lineMetrics) append(""","lineMetrics":true""")
    append("}")
  }

  private fun dataToJson(data: GeoJsonData): String = when (data) {
    is GeoJsonData.Uri -> jsonEscape(data.uri)
    is GeoJsonData.JsonString -> data.json
    is GeoJsonData.Features -> data.geoJson.toJson()
  }
}
