package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

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
