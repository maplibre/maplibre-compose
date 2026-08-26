package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** A loaded [StyleBinding] that keeps the JSON it is handed, standing in for an engine. */
internal class RecordingStyleBinding(
  override val supportsCustomDemEncoding: Boolean = false,
  override val supportsRasterDemScheme: Boolean = true,
) : StyleBinding {

  val sources: MutableMap<String, JsonObject> = mutableMapOf()

  override val isLoaded: Boolean = true

  override val logger: Logger? = null

  override fun onUnload(action: () -> Unit): () -> Unit = {}

  override fun addSource(sourceId: String, source: JsonObject): Boolean {
    sources[sourceId] = source
    return true
  }

  override fun removeSource(sourceId: String) {
    sources.remove(sourceId)
  }

  override fun sourceExists(sourceId: String): Boolean = sourceId in sources

  override fun addImageSourceImage(
    sourceId: String,
    coordinates: List<Position>,
    image: ImageBitmap,
  ): Boolean {
    sources[sourceId] = JsonObject(mapOf("type" to JsonPrimitive("image")))
    return true
  }

  override fun setImageSourceImage(sourceId: String, image: ImageBitmap) = Unit

  override fun setImageSourceUrl(sourceId: String, url: String) = Unit

  override fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>) = Unit

  override fun imageSourceCoordinates(sourceId: String): List<Position>? = null

  /** The GeoJSON data each install applied, in order, keyed by source. */
  val installedGeoJson: MutableMap<String, MutableList<Any>> = mutableMapOf()

  override fun prepareGeoJson(data: GeoJsonData, options: GeoJsonOptions): PreparedGeoJson =
    RecordedPreparedGeoJson(data)

  override fun setGeoJsonSourceData(
    sourceId: String,
    prepared: PreparedGeoJson,
    claim: () -> Boolean,
  ) {
    if (!claim()) return
    installedGeoJson.getOrPut(sourceId) { mutableListOf() } +=
      (prepared as RecordedPreparedGeoJson).data
  }

  override fun setGeoJsonSourceUrl(sourceId: String, url: String, claim: () -> Boolean) {
    if (!claim()) return
    installedGeoJson.getOrPut(sourceId) { mutableListOf() } += url
  }

  override suspend fun clusterExpansionZoom(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): Double? = null

  override suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>? = null

  override suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>? = null

  class RecordedPreparedGeoJson(val data: GeoJsonData) : PreparedGeoJson {
    var closed: Boolean = false
      private set

    override fun close() {
      closed = true
    }
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean = true

  override fun removeLayer(layerId: String) = Unit

  override fun moveLayer(layerId: String, beforeLayerId: String) = Unit

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) = Unit

  override fun setLayerFilter(layerId: String, filter: JsonElement) = Unit

  override fun layerProperty(layerId: String, name: String): JsonElement? = null

  override fun layerExists(layerId: String): Boolean = false

  override fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ) = Unit

  override fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject = JsonObject(emptyMap())

  override fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) = Unit

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) = Unit

  override fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> = emptyList()
}
