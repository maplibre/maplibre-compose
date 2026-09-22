package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpRect
import js.objects.unsafeJso
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.GeoJsonSourceData
import org.maplibre.compose.gljs.GlJsGeoJsonSource
import org.maplibre.compose.gljs.GlJsImageSource
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.GlJsVectorSource
import org.maplibre.compose.gljs.JsRecord
import org.maplibre.compose.gljs.LayerSpecification
import org.maplibre.compose.gljs.LightSpecification
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.ProjectionSpecification
import org.maplibre.compose.gljs.QuerySourceFeatureOptions
import org.maplibre.compose.gljs.SkySpecification
import org.maplibre.compose.gljs.SourceHandle
import org.maplibre.compose.gljs.SourceSpecification
import org.maplibre.compose.gljs.StyleImageMetadata
import org.maplibre.compose.gljs.StyleLayer
import org.maplibre.compose.gljs.StyleSetterOptions
import org.maplibre.compose.gljs.TransitionSpecification
import org.maplibre.compose.gljs.UpdateImageOptions
import org.maplibre.compose.gljs.keys
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.layers.GlJsLocationIndicator
import org.maplibre.compose.layers.IndicatorImage
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.sources.CLUSTER_ID_PROPERTY
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.featureIdentifiers
import org.maplibre.compose.sources.reconstructedSource
import org.maplibre.compose.sources.toDataJson
import org.maplibre.compose.sources.toJsonObjectOrEmpty
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
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** [StyleBinding] for one loaded style in a MapLibre GL JS map. */
internal class GlJsStyleBinding(
  private val map: MaplibreMap,
  override val logger: MapLog?,
  private val getScale: () -> Float,
) : StyleBinding {

  override val identity: StyleIdentity = StyleIdentity.create()

  override val animatorDurationScale: Float
    get() = systemAnimatorDurationScale()

  private val indicators = mutableMapOf<String, GlJsLocationIndicator>()
  private val indicatorImages = mutableMapOf<String, IndicatorImage>()

  internal fun indicator(id: String): GlJsLocationIndicator? = indicators[id]

  private var loaded = true
  private val customVectorAttachments = mutableMapOf<String, GlJsProtocolTileAttachment>()
  private val customGeometryAttachments = mutableMapOf<String, GlJsCustomGeometryAttachment>()

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

  private val lightErrors: GlJsSubscription =
    map.style.light.subscribe("error") { event ->
      errorCount++
      lastError = event.error?.message
    }

  private val skyErrors: GlJsSubscription =
    map.style.sky.subscribe("error") { event ->
      errorCount++
      lastError = event.error?.message
    }

  private val pendingCustomGeometryReloads = mutableSetOf<String>()

  // Reload only after outstanding tiles settle. GL JS otherwise re-parses their old responses.
  private val customGeometryReloads: GlJsSubscription =
    map.subscribe("sourcedata") { event ->
      val sourceId = event.sourceId ?: return@subscribe
      if (!loaded || sourceId !in pendingCustomGeometryReloads) return@subscribe
      if (map.getSource<GlJsVectorSource>(sourceId) == null || map.isSourceLoaded(sourceId) != true)
        return@subscribe
      pendingCustomGeometryReloads.remove(sourceId)
      posted("Custom geometry source '$sourceId'", null) {
        invalidateCustomGeometrySource(sourceId)
      }
    }

  // GL JS serializes only JSON layers when recovering a lost context. Retain custom layer
  // positions before it destroys the style, then reattach them after the restored style loads.
  private var layerOrder = map.getLayersOrder().toList()
  private var restoringContext = false
  private val orderChanges =
    map.subscribe("styledata") {
      if (!restoringContext && map.asDynamic().style != null)
        layerOrder = map.getLayersOrder().toList()
    }
  private val contextLost = map.subscribe("webglcontextlost") { restoringContext = true }
  private val contextStyleLoaded =
    map.subscribe("style.load") {
      if (restoringContext && loaded) {
        restoringContext = false
        val order = layerOrder
        var before: String? = null
        for (id in order.asReversed()) {
          val renderer = indicators[id]
          if (renderer != null && map.getLayer(id) == null) {
            if (before == null) map.addLayer(renderer.layer)
            else map.addLayer(renderer.layer, before)
          }
          if (map.getLayer(id) != null) before = id
        }
        layerOrder = map.getLayersOrder().toList()
        map.triggerRepaint()
      }
    }

  override val isLoaded: Boolean
    get() = loaded

  override fun invalidate() {
    if (!loaded) return
    loaded = false
    indicators.values.forEach { it.close() }
    indicators.clear()
    indicatorImages.clear()
    orderChanges.cancel()
    contextLost.cancel()
    contextStyleLoaded.cancel()
    errors.cancel()
    lightErrors.cancel()
    skyErrors.cancel()
    customGeometryReloads.cancel()
    pendingCustomGeometryReloads.clear()
    val vectorAttachments = customVectorAttachments.values.toList()
    val geometryAttachments = customGeometryAttachments.values.toList()
    customVectorAttachments.clear()
    customGeometryAttachments.clear()
    vectorAttachments.forEach { it.close() }
    geometryAttachments.forEach { it.close() }
  }

  private fun requireLoaded() {
    check(loaded) { "Style operation belongs to a stale loaded-style identity" }
  }

  override val supportsCustomDemEncoding: Boolean = true

  /** GL JS rejects a raster-dem source that carries a `scheme`, and reads only XYZ tiles. */
  override val supportsRasterDemScheme: Boolean = false

  override fun addImage(definition: StyleImageDefinition) {
    requireLoaded()
    if (map.hasImage(definition.id)) {
      throw StyleMutationException("Image ID '${definition.id}' already exists in style", null)
    }
    setImage(definition)
  }

  // GL JS runs the remove and add in one task, so no frame renders between them.
  override fun setImage(definition: StyleImageDefinition) {
    requireLoaded()
    val (id, snapshot, sdf, stretch) = definition
    val image = snapshot.toImageBitmap()
    val scale = getScale()
    val pixels = image.toGlJsImage()
    val stretchPx = stretch?.resolve(image.width, image.height, scale)
    val metadata =
      unsafeJso<StyleImageMetadata> {
        pixelRatio = scale.toDouble()
        this.sdf = sdf
        stretchPx?.let { px ->
          if (px.stretchX.isNotEmpty()) stretchX = px.stretchX.toGlJsStretch()
          if (px.stretchY.isNotEmpty()) stretchY = px.stretchY.toGlJsStretch()
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
    mutate("add image '$id'") {
      if (map.hasImage(id)) map.removeImage(id)
      map.addImage(id, pixels, metadata)
      indicatorImages[id] = IndicatorImage(pixels, scale.toDouble())
      indicators.values.forEach { it.resourceChanged() }
    }
  }

  override fun removeImage(id: String): Boolean {
    requireLoaded()
    indicatorImages.remove(id)
    indicators.values.forEach { it.resourceChanged() }
    if (!map.hasImage(id)) return false
    mutate("remove image '$id'") { map.removeImage(id) }
    return true
  }

  override fun imageExists(id: String): Boolean {
    requireLoaded()
    return map.hasImage(id)
  }

  override fun getSource(id: String): Source? {
    requireLoaded()
    return reconstructSource(id)
  }

  override fun getSources(): List<Source> {
    requireLoaded()
    return map.getStyle().sources.keys().mapNotNull(::reconstructSource)
  }

  override fun sourceIds(): List<String> {
    requireLoaded()
    return map.getStyle().sources.keys().toList()
  }

  override fun getLayer(id: String): ResolvedLayerDefinition? {
    requireLoaded()
    indicators[id]?.let {
      return resolvedLayerDefinition(id, it.definition)
    }
    return map.getLayer(id)?.let(::reconstructLayer)
  }

  internal fun indicatorFeatures(rect: DpRect, layerIds: Set<String>?) =
    indicators.mapNotNull { (id, renderer) ->
      if (layerIds != null && id !in layerIds) return@mapNotNull null
      if (
        !renderer.hitTest(
          rect.left.value.toDouble(),
          rect.top.value.toDouble(),
          rect.right.value.toDouble(),
          rect.bottom.value.toDouble(),
        )
      )
        return@mapNotNull null
      val position = renderer.renderedPosition ?: return@mapNotNull null
      id to
        Feature(
          Point(position),
          JsonObject(emptyMap()),
        )
    }

  override fun layerIds(): List<String> {
    requireLoaded()
    return if (restoringContext) layerOrder else map.getLayersOrder().toList()
  }

  override fun layerSummaries(): Map<String, LayerSummary> {
    requireLoaded()
    return map
      .getLayersOrder()
      .mapNotNull { id ->
        map.getLayer(id)?.let {
          id to
            LayerSummary(
              if (id in indicators) "location-indicator" else it.type,
              it.source,
              it.sourceLayer,
            )
        }
      }
      .toMap()
  }

  private fun reconstructSource(id: String): Source? {
    val source = map.getSource<SourceHandle>(id) ?: return null
    return reconstructedSource(
      id,
      buildJsonObject {
        put("type", source.type)
        source.attribution?.let { put("attribution", it) }
      },
    )
  }

  private fun reconstructLayer(layer: StyleLayer): ResolvedLayerDefinition {
    val definition =
      layer.serialize().toJsonElement() as? JsonObject
        ?: buildJsonObject {
          put("id", layer.id)
          put("type", layer.type)
        }
    return resolvedLayerDefinition(layer.id, definition)
  }

  override fun addSource(sourceId: String, source: JsonObject): Boolean {
    requireLoaded()
    mutate("add source '$sourceId'") {
      map.addSource(sourceId, source.toJsValue<SourceSpecification>())
    }
    return true
  }

  override fun removeSource(sourceId: String) {
    requireLoaded()
    mutate("remove source '$sourceId'") { map.removeSource(sourceId) }
    pendingCustomGeometryReloads.remove(sourceId)
    customVectorAttachments.remove(sourceId)?.close()
    customGeometryAttachments.remove(sourceId)?.close()
  }

  override fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean {
    requireLoaded()
    val attachment = GlJsCustomGeometryAttachment(sourceId, options, provider)
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
        attachment.close()
        throw error
      }
    if (added) customGeometryAttachments[sourceId] = attachment else attachment.close()
    return added
  }

  override fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox) {
    invalidateCustomGeometrySource(sourceId)
  }

  override fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate) {
    invalidateCustomGeometrySource(sourceId)
  }

  private fun invalidateCustomGeometrySource(sourceId: String) {
    requireLoaded()
    val attachment = customGeometryAttachments[sourceId] ?: return
    val source = map.getSource<GlJsVectorSource>(sourceId) ?: return
    if (map.isSourceLoaded(sourceId) != true) {
      pendingCustomGeometryReloads += sourceId
      return
    }
    mutate("invalidate custom geometry source '$sourceId'") {
      source.setTiles(arrayOf(attachment.invalidate()))
    }
  }

  override fun addCustomVectorSource(
    sourceId: String,
    options: CustomVectorTileSourceOptions,
    provider: VectorTileProvider,
  ): Boolean {
    requireLoaded()
    customVectorAttachments.remove(sourceId)?.close()
    val attachment =
      GlJsProtocolTileAttachment(
        name = "custom-vector-$sourceId",
        loadTile = provider::loadTile,
      )
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
      "Custom vector tile invalidation is not available in the browser because MapLibre GL JS " +
        "has no public per-tile invalidation operation."
    )

  override fun sourceExists(sourceId: String): Boolean? {
    requireLoaded()
    return map.getSource<SourceHandle>(sourceId) != null
  }

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
    requireLoaded()
    val options = unsafeJso<UpdateImageOptions> { this.url = url }
    map.getSource<GlJsImageSource>(sourceId)?.updateImage(options)
  }

  override fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>) {
    requireLoaded()
    val corners = coordinates.map { arrayOf(it.longitude, it.latitude) }.toTypedArray()
    map.getSource<GlJsImageSource>(sourceId)?.setCoordinates(corners)
  }

  override fun imageSourceCoordinates(sourceId: String): List<Position>? {
    requireLoaded()
    return map.getSource<GlJsImageSource>(sourceId)?.coordinates?.map {
      Position(longitude = it[0], latitude = it[1])
    }
  }

  override fun submitGeoJsonData(
    sourceId: String,
    data: GeoJsonData,
    fallbackOptions: GeoJsonOptions,
  ) {
    requireLoaded()
    mutate("set data on source '$sourceId'") {
      val value =
        if (data is GeoJsonData.Uri) data.uri.unsafeCast<GeoJsonSourceData>()
        else data.toDataJson().toJsValue<GeoJsonSourceData>()
      map.getSource<GlJsGeoJsonSource>(sourceId)?.setData(value)
    }
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
    requireLoaded()
    val source = map.getSource<GlJsGeoJsonSource>(sourceId) ?: return null
    return ClusterQuery(source, clusterId)
  }

  override fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ) {
    requireLoaded()
    val js = state.toJsValue<Any>()
    posted("Feature '$featureId' in source '$sourceId'", state) {
      for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
        mutate("set the feature state") { map.setFeatureState(ident, js) }
      }
    }
  }

  /**
   * Merged across the identifier forms: MapLibre keys state by the feature id's JS type, and a
   * feature the common API names as text may be stored under either.
   */
  override suspend fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject {
    requireLoaded()
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
    requireLoaded()
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
      if (stateKey == null) map.removeFeatureState(ident)
      else map.removeFeatureState(ident, stateKey)
    }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    requireLoaded()
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId = null)) {
      map.removeFeatureState(ident)
    }
  }

  /** MapLibre GL JS queries one source layer per call, where the common contract takes a set. */
  override suspend fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> {
    if (sourceLayerIds.isEmpty()) return emptyList()
    requireLoaded()
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
  fun <T> withMap(action: (MaplibreMap) -> T): T? {
    requireLoaded()
    return action(map)
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean {
    requireLoaded()
    mutate("add layer") {
      val id = layer.getValue("id").jsonPrimitive.content
      val renderer =
        if (layer["type"]?.jsonPrimitive?.content == "location-indicator")
          GlJsLocationIndicator(layer, map) { imageId ->
            indicatorImages[imageId]
              ?: map.getImage(imageId)?.let { sprite ->
                IndicatorImage(sprite.data, sprite.pixelRatio).also {
                  indicatorImages[imageId] = it
                }
              }
          }
        else null
      val spec = renderer?.layer ?: normalizeSourceLayer(layer).toJsValue<LayerSpecification>()
      // MapLibre reads an absent `beforeId` as "on top"; an empty string is a layer id it will not
      // find.
      if (beforeLayerId.isEmpty()) map.addLayer(spec) else map.addLayer(spec, beforeLayerId)
      if (renderer != null) indicators[id] = renderer
      layerOrder = map.getLayersOrder().toList()
    }
    return true
  }

  /**
   * MapLibre Native ignores `source-layer` on a custom geometry source because its tiles hold one
   * unnamed layer. GL JS validates that `source-layer` is a non-empty string and matches a layer
   * name in the tile, so a layer on such a source is pointed at the source's canonical name.
   */
  private fun normalizeSourceLayer(layer: JsonObject): JsonObject {
    val sourceId = (layer["source"] as? JsonPrimitive)?.contentOrNull ?: return layer
    val attachment = customGeometryAttachments[sourceId] ?: return layer
    if (layer["source-layer"] == JsonPrimitive(attachment.sourceLayerName)) return layer
    return JsonObject(layer + ("source-layer" to JsonPrimitive(attachment.sourceLayerName)))
  }

  override fun removeLayer(layerId: String) {
    requireLoaded()
    map.removeLayer(layerId)
    indicators.remove(layerId)?.close()
    layerOrder = map.getLayersOrder().toList()
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    requireLoaded()
    if (beforeLayerId.isEmpty()) map.moveLayer(layerId) else map.moveLayer(layerId, beforeLayerId)
    layerOrder = map.getLayersOrder().toList()
  }

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    requireLoaded()
    indicators[layerId]?.let {
      it.update(name, value, kind)
      return
    }
    val js = value.toJsValue<Any?>()
    mutate("set '$name' on layer '$layerId'") {
      when (kind) {
        LayerPropertyKind.LAYOUT -> map.setLayoutProperty(layerId, name, js)
        LayerPropertyKind.PAINT -> map.setPaintProperty(layerId, name, js)
        LayerPropertyKind.ROOT -> setRootProperty(layerId, name, value)
      }
    }
  }

  /** GL JS sets both zoom bounds together, so preserve the bound that the caller did not change. */
  private fun setRootProperty(layerId: String, name: String, value: JsonElement) {
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    val layer = map.getLayer(layerId)
    if (number == null || layer == null || (name != "minzoom" && name != "maxzoom")) {
      throw StyleMutationException(
        "Layer '$layerId' cannot change '$name' once it is in the style",
        null,
      )
    }
    val minZoom = if (name == "minzoom") number else layer.minzoom ?: 0.0
    val maxZoom = if (name == "maxzoom") number else layer.maxzoom ?: 24.0
    map.setLayerZoomRange(layerId, minZoom, maxZoom)
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    requireLoaded()
    // The style spec has no null filter; absent means "match every feature".
    val js = if (filter is JsonNull) null else filter.toJsValue<FilterSpecification>()
    mutate("set the filter on layer '$layerId'") { map.setFilter(layerId, js) }
  }

  /**
   * Trying paint before layout is safe: the style spec gives no layer type a name in both. MapLibre
   * throws rather than answering for a name it does not have.
   */
  override suspend fun layerProperty(layerId: String, name: String): JsonElement? {
    requireLoaded()
    indicators[layerId]?.let {
      return it.propertyValue(name)
    }
    val layer = map.getLayer(layerId) ?: return null
    val root =
      when (name) {
        "id" -> JsonPrimitive(layer.id)
        "type" -> JsonPrimitive(layer.type)
        "source" -> layer.source?.let(::JsonPrimitive)
        "source-layer" -> layer.sourceLayer?.let(::JsonPrimitive)
        "minzoom" -> layer.minzoom?.let(::JsonPrimitive)
        "maxzoom" -> layer.maxzoom?.let(::JsonPrimitive)
        "filter" -> map.getFilter(layerId)?.toJsonElement()
        else -> null
      }
    if (root != null) return root
    val value =
      runCatching { map.getPaintProperty(layerId, name) }.getOrNull()
        ?: runCatching { map.getLayoutProperty(layerId, name) }.getOrNull()
    return value?.toJsonElement()
  }

  override suspend fun transition(): TransitionOptions? {
    requireLoaded()
    val transition = map.style.getTransition()
    return TransitionOptions(
      duration = transition.duration?.milliseconds ?: 300.milliseconds,
      delay = transition.delay?.milliseconds ?: Duration.ZERO,
    )
  }

  override fun setTransition(options: TransitionOptions) {
    requireLoaded()
    map.style.stylesheet.transition =
      unsafeJso<TransitionSpecification> {
        duration = options.duration.toDouble(DurationUnit.MILLISECONDS)
        delay = options.delay.toDouble(DurationUnit.MILLISECONDS)
      }
  }

  override val supportsPlacementTransitions: Boolean = false

  override suspend fun placementTransitions(): Boolean? {
    requireLoaded()
    return true
  }

  override fun setPlacementTransitions(enabled: Boolean) {
    requireLoaded()
    if (!enabled) {
      logger?.w { "MapLibre GL JS cannot switch the symbol placement cross-fade at runtime" }
    }
  }

  override suspend fun globalState(): JsonObject {
    requireLoaded()
    return map.getGlobalState().toJsonElement().jsonObject
  }

  override fun setGlobalStateProperty(name: String, value: JsonElement) {
    requireLoaded()
    val js = value.toJsValue<Any?>()
    posted("Global state '$name'", value) {
      mutate("set global state") { map.setGlobalStateProperty(name, js) }
    }
  }

  override suspend fun lightProperty(name: String): JsonElement? {
    requireLoaded()
    return map.getLight().asDynamic()[name].unsafeCast<Any?>()?.toJsonElement()
  }

  /**
   * MapLibre merges the given properties into the light, so every property it holds and [light]
   * omits is cleared with a null in a second, unvalidated write, after the first write has
   * validated the values.
   */
  override fun setLight(light: JsonObject) {
    requireLoaded()
    posted("The light", light) {
      replace<LightSpecification>("set the light", map.getLight(), light) { value, options ->
        map.setLight(value, options)
      }
    }
  }

  override val supportsSky: Boolean = true

  override suspend fun skyProperty(name: String): JsonElement? {
    requireLoaded()
    val sky = map.getSky() ?: return null
    return sky.asDynamic()[name].unsafeCast<Any?>()?.toJsonElement()
  }

  /** Merges like the light. MapLibre treats an absent sky as no sky. */
  override fun setSky(sky: JsonObject?) {
    requireLoaded()
    posted("The sky", sky) {
      if (sky == null) {
        val options = unsafeJso<StyleSetterOptions> { validate = false }
        mutate("remove the sky") { map.setSky(null, options) }
      } else {
        replace<SkySpecification>("set the sky", map.getSky(), sky) { value, options ->
          map.setSky(value, options)
        }
      }
    }
  }

  override val supportsProjection: Boolean = true

  override suspend fun projectionProperty(name: String): JsonElement? {
    requireLoaded()
    val projection = map.getProjection() ?: return null
    return projection.asDynamic()[name].unsafeCast<Any?>()?.toJsonElement()
  }

  /** MapLibre falls back to Mercator for an unknown name with a console warning, not an error. */
  override fun setProjection(projection: JsonObject) {
    requireLoaded()
    posted("The projection", projection) {
      mutate("set the projection") {
        map.setProjection(projection.toJsValue<ProjectionSpecification>())
      }
    }
  }

  /**
   * Writes [next] validated, so a rejected value changes nothing, then writes it again unvalidated
   * with a null for every property of [current] that [next] omits. Validation rejects a null, and
   * only an unvalidated null clears a property.
   */
  private inline fun <T : Any> replace(
    what: String,
    current: Any?,
    next: JsonObject,
    set: (T, StyleSetterOptions) -> Unit,
  ) {
    mutate(what) { set(next.toJsValue(), unsafeJso { validate = true }) }
    val stale = current?.unsafeCast<JsRecord<*>>()?.keys()?.filter { it !in next }.orEmpty()
    if (stale.isEmpty()) return
    val cleared = next.toJsValue<T>()
    for (key in stale) cleared.asDynamic()[key] = null
    mutate(what) { set(cleared, unsafeJso { validate = false }) }
  }

  override fun layerExists(layerId: String): Boolean? {
    requireLoaded()
    return map.getLayer(layerId) != null
  }

  /**
   * Runs a write that the common contract posts. MapLibre GL JS applies it inline and reports a
   * rejection through the logger.
   */
  private inline fun posted(target: String, value: JsonElement?, action: () -> Unit) {
    try {
      action()
    } catch (error: StyleMutationException) {
      reportRejectedWrite(target, value, error)
    }
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
