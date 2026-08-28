package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import js.objects.unsafeJso
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.GeoJsonSourceData
import org.maplibre.compose.gljs.GlJsGeoJsonSource
import org.maplibre.compose.gljs.GlJsImageSource
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.LayerSpecification
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.QuerySourceFeatureOptions
import org.maplibre.compose.gljs.SourceHandle
import org.maplibre.compose.gljs.SourceSpecification
import org.maplibre.compose.gljs.StyleImageMetadata
import org.maplibre.compose.gljs.UpdateImageOptions
import org.maplibre.compose.gljs.keys
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayerDescriptor
import org.maplibre.compose.sources.CLUSTER_ID_PROPERTY
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.featureIdentifiers
import org.maplibre.compose.sources.toDataJson
import org.maplibre.compose.sources.toJsonObjectOrEmpty
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.toDataUrl
import org.maplibre.compose.util.toFeatureCollection
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toGlJsImage
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toJsonElement
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** [StyleBinding] over a MapLibre GL JS map. One binding belongs to one loaded style. */
internal class GlJsStyleBinding(
  private val map: MaplibreMap,
  override val logger: Logger?,
  /**
   * The display scale the images handed to [addImage] were rasterized at. MapLibre sizes a style
   * image as `pixels / pixelRatio`.
   */
  private val getScale: () -> Float = { 1f },
) : StyleBinding {

  private var loaded = true
  private val unloadActions = mutableSetOf<() -> Unit>()
  private val customVectorAttachments = mutableMapOf<String, GlJsCustomVectorAttachment>()

  /**
   * GL JS reports a style change it will not make by firing an `error` event rather than throwing,
   * so a mutation's outcome is read by watching this across the call.
   */
  private var errorCount = 0
  private var lastError: String? = null

  internal val lastReportedError: String?
    get() = lastError

  private val errors: GlJsSubscription =
    map.subscribe("error") { event ->
      errorCount++
      lastError = event.error?.message
    }

  override val isLoaded: Boolean
    get() = loaded

  fun unload() {
    if (!loaded) return
    loaded = false
    errors.cancel()
    val attachments = customVectorAttachments.values.toList()
    customVectorAttachments.clear()
    attachments.forEach { it.close() }
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

  override val supportsCustomDemEncoding: Boolean = true

  /** GL JS rejects a raster-dem source that carries a `scheme`, and reads only XYZ tiles. */
  override val supportsRasterDemScheme: Boolean = false

  override fun addSource(sourceId: String, source: JsonObject): Boolean {
    if (!loaded) return false
    mutate("add source '$sourceId'") {
      map.addSource(sourceId, source.toJsValue<SourceSpecification>())
    }
    return true
  }

  /** Routed through [mutate]: GL JS reports an in-use removal as an `error` event. */
  override fun removeSource(sourceId: String) {
    // A refused removal keeps the source in the style, so its attachment must keep serving tiles.
    if (loaded) mutate("remove source '$sourceId'") { map.removeSource(sourceId) }
    customVectorAttachments.remove(sourceId)?.close()
  }

  override fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean =
    throw UnsupportedOperationException(
      "Custom geometry source '$sourceId' is not available in the browser. Use " +
        "CustomVectorSource when the provider can return MVT data, or use GeoJsonSource for " +
        "geographic features."
    )

  override fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox): Unit =
    throw UnsupportedOperationException(
      "Custom geometry source '$sourceId' is not available in the browser."
    )

  override fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate): Unit =
    throw UnsupportedOperationException(
      "Custom geometry source '$sourceId' is not available in the browser."
    )

  override fun addCustomVectorSource(
    sourceId: String,
    options: CustomVectorSourceOptions,
    provider: VectorTileProvider,
  ): Boolean {
    if (!loaded) return false
    customVectorAttachments.remove(sourceId)?.close()
    val attachment = GlJsCustomVectorAttachment(sourceId, provider)
    customVectorAttachments[sourceId] = attachment
    val added =
      try {
        addSource(
          sourceId,
          buildJsonObject {
            put("type", "vector")
            putJsonArray("tiles") { add(attachment.tileUrlTemplate) }
            put("minzoom", options.minZoom)
            put("maxzoom", options.maxZoom)
          },
        )
      } catch (error: Throwable) {
        customVectorAttachments.remove(sourceId)?.close()
        throw error
      }
    if (!added) customVectorAttachments.remove(sourceId)?.close()
    return added
  }

  override fun invalidateCustomVectorSourceTile(sourceId: String, tile: TileCoordinate): Unit =
    throw UnsupportedOperationException(
      "CustomVectorSource.invalidateTile is not available in the browser because MapLibre GL JS " +
        "has no public per-tile invalidation operation."
    )

  override fun sourceExists(sourceId: String): Boolean? =
    if (!loaded) null else map.getSource<SourceHandle>(sourceId) != null

  /** MapLibre GL JS names images by URL, so the bitmap is encoded to a `data:` URL. */
  override fun addImageSourceImage(
    sourceId: String,
    coordinates: List<Position>,
    image: ImageBitmap,
  ): Boolean =
    addSource(
      sourceId,
      buildJsonObject {
        put("type", "image")
        put("url", image.toDataUrl())
        putJsonArray("coordinates") {
          coordinates.forEach { corner ->
            addJsonArray {
              add(corner.longitude)
              add(corner.latitude)
            }
          }
        }
      },
    )

  override fun setImageSourceImage(sourceId: String, image: ImageBitmap) {
    setImageSourceUrl(sourceId, image.toDataUrl())
  }

  override fun setImageSourceUrl(sourceId: String, url: String) {
    if (!loaded) return
    val options = unsafeJso<UpdateImageOptions> { this.url = url }
    map.getSource<GlJsImageSource>(sourceId)?.updateImage(options)
  }

  override fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>) {
    if (!loaded) return
    val corners = coordinates.map { arrayOf(it.longitude, it.latitude) }.toTypedArray()
    map.getSource<GlJsImageSource>(sourceId)?.setCoordinates(corners)
  }

  override fun imageSourceCoordinates(sourceId: String): List<Position>? {
    if (!loaded) return null
    return map.getSource<GlJsImageSource>(sourceId)?.coordinates?.map {
      Position(longitude = it[0], latitude = it[1])
    }
  }

  override fun prepareGeoJson(data: GeoJsonData, options: GeoJsonOptions): PreparedGeoJson =
    GlJsPreparedGeoJson(data.toDataJson().toJsValue())

  override fun setGeoJsonSourceData(
    sourceId: String,
    prepared: PreparedGeoJson,
    claim: () -> Boolean,
  ) {
    if (!claim() || !loaded) return
    map.getSource<GlJsGeoJsonSource>(sourceId)?.setData((prepared as GlJsPreparedGeoJson).data)
  }

  override fun setGeoJsonSourceUrl(sourceId: String, url: String, claim: () -> Boolean) {
    if (!claim() || !loaded) return
    map.getSource<GlJsGeoJsonSource>(sourceId)?.setData(url.unsafeCast<GeoJsonSourceData>())
  }

  override suspend fun clusterExpansionZoom(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): Double? {
    val query = clusterQuery(sourceId, feature) ?: return null
    return query.source.getClusterExpansionZoom(query.clusterId).await()
  }

  override suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>? {
    val query = clusterQuery(sourceId, feature) ?: return null
    return query.source.getClusterChildren(query.clusterId).await().toFeatureCollection()
  }

  override suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>? {
    val query = clusterQuery(sourceId, feature) ?: return null
    return query.source
      .getClusterLeaves(
        query.clusterId,
        limit.coerceAtLeast(0).toDouble(),
        offset.coerceAtLeast(0).toDouble(),
      )
      .await()
      .toFeatureCollection()
  }

  private class ClusterQuery(val source: GlJsGeoJsonSource, val clusterId: Double)

  /** Null when the feature is not a cluster or the style has unloaded. */
  private fun clusterQuery(sourceId: String, feature: Feature<*, JsonObject?>): ClusterQuery? {
    val clusterId =
      (feature.properties?.get(CLUSTER_ID_PROPERTY) as? JsonPrimitive)?.doubleOrNull
        ?: run {
          logger?.w {
            "Cluster query on a feature with no '$CLUSTER_ID_PROPERTY' in source '$sourceId'"
          }
          return null
        }
    if (!loaded) return null
    val source = map.getSource<GlJsGeoJsonSource>(sourceId) ?: return null
    return ClusterQuery(source, clusterId)
  }

  override fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ) {
    if (!loaded) return
    val js = state.toJsValue<Any>()
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
      map.setFeatureState(ident, js)
    }
  }

  /**
   * Merged across the identifier forms: MapLibre keys state by the feature id's JS type, and a
   * feature the common API names as text may be stored under either.
   */
  override fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject {
    if (!loaded) return JsonObject(emptyMap())
    var merged = JsonObject(emptyMap())
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
      val next = map.getFeatureState(ident).toJsonObjectOrEmpty()
      if (next.isNotEmpty()) merged = JsonObject(merged + next)
    }
    return merged
  }

  override fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    if (!loaded) return
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
      if (stateKey == null) map.removeFeatureState(ident)
      else map.removeFeatureState(ident, stateKey)
    }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    if (!loaded) return
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId = null)) {
      map.removeFeatureState(ident)
    }
  }

  /** MapLibre GL JS queries one source layer per call, where the common contract takes a set. */
  override fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> {
    if (!loaded || sourceLayerIds.isEmpty()) return emptyList()
    val js = filter?.toJsValue<FilterSpecification>()
    return sourceLayerIds.flatMap { layer ->
      val options =
        unsafeJso<QuerySourceFeatureOptions> {
          sourceLayer = layer
          this.filter = js
        }
      map.querySourceFeatures(sourceId, options).map { it.toGeoJsonFeature() }
    }
  }

  /** Null once the style has unloaded. */
  fun <T> withMap(action: (MaplibreMap) -> T): T? = if (loaded) action(map) else null

  override fun addImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?) {
    if (!loaded) return
    val scale = getScale()
    val pixels = image.toGlJsImage()
    val stretchPx = stretch?.resolve(image.width, image.height, scale)
    val metadata =
      unsafeJso<StyleImageMetadata> {
        pixelRatio = scale.toDouble()
        this.sdf = sdf
        stretchPx?.let { px ->
          if (px.stretchX.isNotEmpty()) {
            stretchX = px.stretchX.toGlJsStretch()
          }
          if (px.stretchY.isNotEmpty()) {
            stretchY = px.stretchY.toGlJsStretch()
          }
          px.content?.let { box ->
            content =
              arrayOf(
                box.left.toDouble(),
                box.top.toDouble(),
                box.right.toDouble(),
                box.bottom.toDouble(),
              )
          }
        }
      }
    // Replacing an image is an update, not a second add; MapLibre warns and ignores a duplicate.
    if (map.hasImage(id)) map.removeImage(id)
    map.addImage(id, pixels, metadata)
  }

  override fun removeImage(id: String) {
    if (loaded && map.hasImage(id)) map.removeImage(id)
  }

  override fun layerIds(): List<String>? = withMap { it.getLayersOrder().toList() }

  override fun getLayer(id: String): Layer? = withMap { map ->
    map.getLayer(id)?.let { reconstructLayer(map, id) }
  }

  override fun getLayers(): List<Layer> = withMap { map ->
    map.getLayersOrder().map { reconstructLayer(map, it) }
  }
    .orEmpty()

  override fun getSource(id: String): Source? = withMap { map ->
    if (map.getSource<SourceHandle>(id) == null) null else reconstructSource(id)
  }

  override fun getSources(): List<Source> = withMap { map ->
    map.getStyle().sources.keys().toList().map { reconstructSource(it) }
  }
    .orEmpty()

  /**
   * Rebuilds a source that the base style owns. The definition comes from the source object, not
   * `getStyle()`, which reports the stylesheet as written and so omits anything resolved from a
   * TileJSON URL.
   */
  private fun reconstructSource(id: String): Source =
    UnknownSource(id, sourceDefinition(id)).also { it.bindExisting(this) }

  private fun sourceDefinition(id: String): JsonObject = buildJsonObject {
    val source = map.getSource<SourceHandle>(id) ?: return@buildJsonObject
    put("type", source.type)
    source.attribution?.let { put("attribution", it) }
  }

  /**
   * From the stylesheet, not the live layer: `StyleLayer` reports evaluated properties where
   * re-adding one needs the source expressions.
   */
  private fun reconstructLayer(map: MaplibreMap, id: String): Layer {
    val definition =
      map.getStyle().layers.firstOrNull { it.id == id }?.toJsonElement() as? JsonObject
        ?: buildJsonObject {
          put("id", id)
          map.getLayer(id)?.let { put("type", it.type) }
        }
    return UnknownLayerDescriptor(id, definition).also { it.bindExisting(this) }
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean {
    if (!loaded) return false
    mutate("add layer") {
      val spec = layer.toJsValue<LayerSpecification>()
      // MapLibre reads an absent `beforeId` as "on top"; an empty string is a layer id it will not
      // find.
      if (beforeLayerId.isEmpty()) map.addLayer(spec) else map.addLayer(spec, beforeLayerId)
    }
    return true
  }

  override fun removeLayer(layerId: String) {
    if (!loaded) return
    map.removeLayer(layerId)
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    if (!loaded) return
    if (beforeLayerId.isEmpty()) map.moveLayer(layerId) else map.moveLayer(layerId, beforeLayerId)
  }

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ): Boolean {
    if (!loaded) return false
    val js = value.toJsValue<Any?>()
    mutate("set '$name' on layer '$layerId'") {
      when (kind) {
        LayerPropertyKind.LAYOUT -> map.setLayoutProperty(layerId, name, js)
        LayerPropertyKind.PAINT -> map.setPaintProperty(layerId, name, js)
        LayerPropertyKind.ROOT -> setRootProperty(layerId, name, value)
      }
    }
    return true
  }

  /**
   * GL JS fixes a layer's own keys at construction, except the zoom range, which moves as a pair —
   * so the half that was not asked for is read back off the live layer.
   */
  private fun setRootProperty(layerId: String, name: String, value: JsonElement) {
    val number = (value as? JsonPrimitive)?.content?.toDoubleOrNull()
    val layer = map.getLayer(layerId)
    if (number == null || layer == null || (name != "minzoom" && name != "maxzoom")) {
      logger?.w {
        "Layer '$layerId' cannot change '$name' once it is in the style; MapLibre GL JS fixes it " +
          "at construction."
      }
      return
    }
    val minZoom = if (name == "minzoom") number else layer.minzoom ?: 0.0
    val maxZoom = if (name == "maxzoom") number else layer.maxzoom ?: 24.0
    map.setLayerZoomRange(layerId, minZoom, maxZoom)
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement): Boolean {
    if (!loaded) return false
    // The style spec has no null filter; absent means "match every feature".
    val js = if (filter is JsonNull) null else filter.toJsValue<FilterSpecification>()
    mutate("set the filter on layer '$layerId'") { map.setFilter(layerId, js) }
    return true
  }

  /**
   * Trying paint before layout is safe: the style spec gives no layer type a name in both. MapLibre
   * throws rather than answering for a name it does not have.
   */
  override fun layerProperty(layerId: String, name: String): JsonElement? {
    if (!loaded || map.getLayer(layerId) == null) return null
    val value =
      runCatching { map.getPaintProperty(layerId, name) }.getOrNull()
        ?: runCatching { map.getLayoutProperty(layerId, name) }.getOrNull()
    return value?.toJsonElement()
  }

  override fun layerExists(layerId: String): Boolean? =
    if (!loaded) null else map.getLayer(layerId) != null

  /** The engine parses in a web worker of its own, so there is nothing to prepare here. */
  private class GlJsPreparedGeoJson(val data: GeoJsonSourceData) : PreparedGeoJson {
    override fun close() = Unit
  }

  private inline fun mutate(what: String, action: () -> Unit) {
    val before = errorCount
    try {
      action()
    } catch (error: Throwable) {
      throw StyleMutationException("MapLibre could not $what: ${error.message}", error)
    }
    if (errorCount != before) {
      throw StyleMutationException("MapLibre could not $what: ${lastError ?: "unknown"}", null)
    }
  }
}

private fun List<Pair<Float, Float>>.toGlJsStretch(): Array<Array<Double>> = map { (start, end) ->
  arrayOf(start.toDouble(), end.toDouble())
}
  .toTypedArray()
