package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** A [StyleBinding] that records one fake engine's base style and installed definitions. */
internal class RecordingStyleBinding(
  images: List<Pair<String, ImageBitmap>> = emptyList(),
  sources: List<Source> = emptyList(),
  layers: List<Layer> = emptyList(),
  override val supportsCustomDemEncoding: Boolean = false,
  override val supportsRasterDemScheme: Boolean = true,
) : StyleBinding {

  override val identity: StyleIdentity = StyleIdentity.create()

  val sources: MutableMap<String, JsonObject> = mutableMapOf()
  val layers: MutableMap<String, JsonObject> = mutableMapOf()
  private val images =
    images.associate { (id, bitmap) -> id to ImageSnapshot.capture(bitmap) }.toMutableMap()
  private val baseSources = sources.associateBy { it.id }.toMutableMap()
  private val baseLayers = layers.associateBy { it.id }.toMutableMap()
  private val orderedLayerIds = mutableListOf<String>()
  private val featureStates = mutableMapOf<Triple<String, String?, String>, JsonObject>()

  override var isLoaded: Boolean = true
    private set

  val installedSourceIds: Set<String>
    get() = this.sources.keys - baseSources.keys

  val installedLayerIds: Set<String>
    get() = this.layers.keys - baseLayers.keys

  var customGeometryProvider: GeometryTileProvider? = null
    private set

  var customVectorProvider: VectorTileProvider? = null
    private set

  init {
    sources.forEach { addSource(it.definition()) }
    layers.forEach { addLayer(it.definition(), beforeLayerId = "") }
  }

  override fun invalidate() {
    isLoaded = false
  }

  override val logger: Logger? = null

  override fun addImage(definition: StyleImageDefinition) {
    check(definition.id !in images) { "Image ID '${definition.id}' already exists in style" }
    images[definition.id] = definition.image
  }

  override fun removeImage(id: String) {
    check(images.remove(id) != null) { "Image ID '$id' not found in style" }
  }

  override fun getSource(id: String): Source? =
    baseSources[id] ?: sources[id]?.let { UnknownSource(id, it) }

  override fun getSources(): List<Source> = sources.keys.mapNotNull(::getSource)

  override fun getLayer(id: String): Layer? =
    baseLayers[id] ?: layers[id]?.let { UnknownLayer(id, it) }

  override fun getLayers(): List<Layer> = orderedLayerIds.mapNotNull(::getLayer)

  override fun layerIds() = orderedLayerIds.toList()

  override fun addSource(sourceId: String, source: JsonObject): Boolean {
    check(sourceId !in sources) { "Source ID '$sourceId' already exists in style" }
    sources[sourceId] = source
    return true
  }

  override fun removeSource(sourceId: String) {
    sources.remove(sourceId)
    baseSources.remove(sourceId)
  }

  fun replaceSource(source: Source) {
    check(source.id in baseSources) { "Source ID '${source.id}' not found in style" }
    sources[source.id] = source.toJson()
    baseSources[source.id] = source
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

  override fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean {
    customGeometryProvider = provider
    sources[sourceId] = JsonObject(mapOf("type" to JsonPrimitive("custom-geometry")))
    return true
  }

  override fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox) = Unit

  override fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate) = Unit

  override fun addCustomVectorSource(
    sourceId: String,
    options: CustomVectorSourceOptions,
    provider: VectorTileProvider,
  ): Boolean {
    customVectorProvider = provider
    sources[sourceId] = JsonObject(mapOf("type" to JsonPrimitive("vector")))
    return true
  }

  override fun invalidateCustomVectorSourceTile(sourceId: String, tile: TileCoordinate) = Unit

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

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean {
    val id = (layer["id"] as JsonPrimitive).content
    check(id !in layers) { "Layer ID '$id' already exists in style" }
    val index =
      if (beforeLayerId.isEmpty()) orderedLayerIds.size else orderedLayerIds.indexOf(beforeLayerId)
    require(index >= 0) { "Layer ID '$beforeLayerId' not found in style" }
    orderedLayerIds.add(index, id)
    layers[id] = layer
    return true
  }

  override fun removeLayer(layerId: String) {
    layers.remove(layerId)
    baseLayers.remove(layerId)
    orderedLayerIds.remove(layerId)
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    check(orderedLayerIds.remove(layerId)) { "Layer ID '$layerId' not found in style" }
    val index =
      if (beforeLayerId.isEmpty()) orderedLayerIds.size else orderedLayerIds.indexOf(beforeLayerId)
    require(index >= 0) { "Layer ID '$beforeLayerId' not found in style" }
    orderedLayerIds.add(index, layerId)
  }

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    val layer = checkNotNull(layers[layerId]) { "Layer ID '$layerId' not found in style" }
    val section =
      when (kind) {
        LayerPropertyKind.LAYOUT -> "layout"
        LayerPropertyKind.PAINT -> "paint"
        LayerPropertyKind.ROOT -> null
      }
    layers[layerId] =
      if (section == null) JsonObject(layer + (name to value))
      else {
        val properties = (layer[section] as? JsonObject).orEmpty()
        JsonObject(layer + (section to JsonObject(properties + (name to value))))
      }
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    val layer = checkNotNull(layers[layerId]) { "Layer ID '$layerId' not found in style" }
    layers[layerId] =
      if (filter is kotlinx.serialization.json.JsonNull) JsonObject(layer - "filter")
      else JsonObject(layer + ("filter" to filter))
  }

  override fun layerProperty(layerId: String, name: String): JsonElement? {
    val layer = layers[layerId] ?: return null
    return layer[name]
      ?: (layer["layout"] as? JsonObject)?.get(name)
      ?: (layer["paint"] as? JsonObject)?.get(name)
  }

  override fun layerExists(layerId: String): Boolean = layerId in layers

  override fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ) {
    val key = Triple(sourceId, sourceLayerId, featureId)
    val previous = featureStates[key].orEmpty()
    val removed = state.filterValues { it is kotlinx.serialization.json.JsonNull }.keys
    featureStates[key] =
      JsonObject(
        (previous - removed) + state.filterValues { it !is kotlinx.serialization.json.JsonNull }
      )
  }

  override fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject =
    featureStates[Triple(sourceId, sourceLayerId, featureId)] ?: JsonObject(emptyMap())

  override fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    val key = Triple(sourceId, sourceLayerId, featureId)
    if (stateKey == null) featureStates.remove(key)
    else featureStates[key]?.let { featureStates[key] = JsonObject(it - stateKey) }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    featureStates.keys.removeAll { it.first == sourceId && it.second == sourceLayerId }
  }

  override fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> = emptyList()
}
