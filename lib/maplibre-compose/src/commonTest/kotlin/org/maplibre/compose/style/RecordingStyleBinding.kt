package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.util.ImageStretch
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * A loaded [StyleBinding] that keeps the JSON and descriptors it receives, in place of an engine.
 * [baseSources] and [baseLayers] model a loaded base style.
 */
internal open class RecordingStyleBinding(
  override val supportsCustomDemEncoding: Boolean = false,
  override val supportsRasterDemScheme: Boolean = true,
  baseSources: List<Source> = emptyList(),
  baseLayers: List<Layer> = emptyList(),
) : StyleBinding {

  val sources: MutableMap<String, JsonObject> = mutableMapOf()

  private val imageMap = mutableMapOf<String, ImageBitmap>()
  private val sourceMap = baseSources.associateBy { it.id }.toMutableMap()
  private val layerList = baseLayers.toMutableList()
  private val layerMap = baseLayers.associateBy { it.id }.toMutableMap()
  private val layerProperties = mutableMapOf<String, MutableMap<String, JsonElement>>()

  private var loaded = true
  private val unloadActions = mutableSetOf<() -> Unit>()

  override val isLoaded: Boolean
    get() = loaded

  override val logger: Logger? = null

  fun unload() {
    if (!loaded) return
    loaded = false
    val actions = unloadActions.toList()
    unloadActions.clear()
    actions.forEach { it() }
  }

  override fun onUnload(action: () -> Unit): () -> Unit {
    if (!loaded) {
      action()
      return {}
    }
    unloadActions += action
    return { unloadActions -= action }
  }

  override fun addImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?) {
    if (id in imageMap) error("Image ID '$id' already exists in style")
    imageMap[id] = image
  }

  override fun removeImage(id: String) {
    if (id !in imageMap) error("Image ID '$id' not found in style")
    imageMap.remove(id)
  }

  override fun getSource(id: String): Source? = sourceMap[id]

  override fun getSources(): List<Source> = sourceMap.values.toList()

  override fun addSource(source: Source): Boolean {
    if (!loaded) return false
    if (source.id in sourceMap) error("Source ID '${source.id}' already exists in style")
    val installed = source.install(this)
    if (installed) sourceMap[source.id] = source
    return installed
  }

  override fun removeSource(source: Source) {
    if (source.id !in sourceMap) error("Source ID '${source.id}' not found in style")
    removeSource(source.id)
  }

  internal fun replaceSource(source: Source) {
    if (source.id !in sourceMap) error("Source ID '${source.id}' not found in style")
    sourceMap[source.id] = source
  }

  override fun getLayer(id: String): Layer? = layerMap[id]

  override fun getLayers(): List<Layer> = layerList.toList()

  override fun layerIds(): List<String>? = if (loaded) layerList.map { it.id } else null

  override fun addLayer(layer: Layer) {
    if (!loaded) return
    requireNewLayer(layer)
    layerList.add(layer)
    layerMap[layer.id] = layer
  }

  override fun addLayerAbove(layerId: String, layer: Layer) {
    if (!loaded) return
    requireNewLayer(layer)
    val index = layerList.indexOfFirst { it.id == layerId }
    if (index == -1) error("Layer ID '$layerId' not found in base style")
    layerList.add(index + 1, layer)
    layerMap[layer.id] = layer
  }

  override fun addLayerBelow(layerId: String, layer: Layer) {
    if (!loaded) return
    requireNewLayer(layer)
    val index = layerList.indexOfFirst { it.id == layerId }
    if (index == -1) error("Layer ID '$layerId' not found in base style")
    layerList.add(index, layer)
    layerMap[layer.id] = layer
  }

  override fun addLayerAt(index: Int, layer: Layer) {
    if (!loaded) return
    requireNewLayer(layer)
    layerList.add(index, layer)
    layerMap[layer.id] = layer
  }

  override fun removeLayer(layer: Layer) {
    if (layer.id !in layerMap) error("Layer ID '${layer.id}' not found in style")
    if (!layerList.remove(layer)) error("Layer '$layer' not found in style")
    layerMap.remove(layer.id)
  }

  private fun requireNewLayer(layer: Layer) {
    if (layer.id in layerMap) error("Layer ID '${layer.id}' already exists in style")
  }

  override fun addSource(sourceId: String, source: JsonObject): Boolean {
    sources[sourceId] = source
    return true
  }

  override fun removeSource(sourceId: String) {
    sources.remove(sourceId)
    sourceMap.remove(sourceId)
  }

  override fun sourceExists(sourceId: String): Boolean =
    sourceId in sources || sourceId in sourceMap

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

  override fun setGeoJsonSourceData(sourceId: String, prepared: PreparedGeoJson) {
    installedGeoJson.getOrPut(sourceId) { mutableListOf() } +=
      (prepared as RecordedPreparedGeoJson).data
  }

  override fun setGeoJsonSourceUrl(sourceId: String, url: String) {
    installedGeoJson.getOrPut(sourceId) { mutableListOf() } += url
  }

  override fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean {
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

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean = true

  override fun removeLayer(layerId: String) = Unit

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    if (!loaded) return
    val layer = layerMap[layerId] ?: error("Layer ID '$layerId' not found in style")
    layerList.remove(layer)
    if (beforeLayerId.isEmpty()) {
      layerList.add(layer)
    } else {
      val index = layerList.indexOfFirst { it.id == beforeLayerId }
      if (index == -1) error("Layer ID '$beforeLayerId' not found in style")
      layerList.add(index, layer)
    }
  }

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ): Boolean {
    if (!loaded) return false
    layerProperties.getOrPut(layerId) { mutableMapOf() }[name] = value
    return true
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement): Boolean {
    if (!loaded) return false
    layerProperties.getOrPut(layerId) { mutableMapOf() }["filter"] = filter
    return true
  }

  override fun layerProperty(layerId: String, name: String): JsonElement? =
    layerProperties[layerId]?.get(name)

  override fun layerExists(layerId: String): Boolean = layerId in layerMap

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
