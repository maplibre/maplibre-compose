package org.maplibre.compose.style

import androidx.compose.ui.unit.DpRect
import js.objects.unsafeJso
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import org.maplibre.compose.gljs.CanonicalTileId
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.GeoJsonSourceData
import org.maplibre.compose.gljs.GlJsGeoJsonSource
import org.maplibre.compose.gljs.GlJsImageSource
import org.maplibre.compose.gljs.GlJsMapEvent
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.GlJsVectorSource
import org.maplibre.compose.gljs.JsRecord
import org.maplibre.compose.gljs.LayerSpecification
import org.maplibre.compose.gljs.LightSpecification
import org.maplibre.compose.gljs.LngLat
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
import org.maplibre.compose.layers.LayerSummary
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.sources.ClusterIdProperty
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
import org.maplibre.compose.util.PreparedImage
import org.maplibre.compose.util.toFeatureCollection
import org.maplibre.compose.util.toGeoJsonFeature
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
  /** Receives the exception of a failed custom source provider call. */
  private val customTileFailed: (Throwable) -> Unit = {},
  private val getScale: () -> Float,
) : StyleBinding {

  override val identity: StyleIdentity = StyleIdentity.create()

  override val animatorDurationScale: Float
    get() = systemAnimatorDurationScale()

  private val indicators = mutableMapOf<String, GlJsLocationIndicator>()
  private val indicatorImages = mutableMapOf<String, IndicatorImage>()

  /**
   * The latest prepared pixels of each image source until a URL replacement succeeds. GL JS
   * recovers a lost context by serializing the style, which keeps only an image source's URL, so
   * these are applied again.
   */
  private val imageSourceImages = mutableMapOf<String, PreparedImage>()

  private class PendingImageSourceUrl(val source: GlJsImageSource, val url: String)

  private val pendingImageSourceUrls = mutableMapOf<String, PendingImageSourceUrl>()
  private val imageSourceLoads =
    map.subscribe("sourcedata") { event ->
      val id = event.sourceId ?: return@subscribe
      val pending = pendingImageSourceUrls[id] ?: return@subscribe
      if (
        event.sourceDataType == "metadata" && map.getSource<GlJsImageSource>(id) === pending.source
      ) {
        pendingImageSourceUrls.remove(id)
        imageSourceImages.remove(id)
      }
    }

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

  private val errors: List<GlJsSubscription> =
    listOf(
      map.subscribe("error", ::recordError),
      map.style.light.subscribe("error", ::recordError),
      map.style.sky.subscribe("error", ::recordError),
    )

  private fun recordError(event: GlJsMapEvent) {
    errorCount++
    lastError = event.error?.message
    event.sourceId?.let { pendingImageSourceUrls.remove(it) }
  }

  private val pendingCustomSourceReloads = mutableSetOf<String>()

  // Reload only after outstanding tiles settle. GL JS otherwise re-parses their old responses. A
  // tile settles with `sourcedata` when it loads and with `error` when it fails.
  private val customSourceReloads: List<GlJsSubscription> =
    listOf("sourcedata", "error").map { type ->
      map.subscribe(type) { event -> reloadPendingCustomSource(event.sourceId) }
    }

  private fun reloadPendingCustomSource(sourceId: String?) {
    if (sourceId == null || !loaded || sourceId !in pendingCustomSourceReloads) return
    if (map.getSource<GlJsVectorSource>(sourceId) == null || map.isSourceLoaded(sourceId) != true)
      return
    pendingCustomSourceReloads.remove(sourceId)
    postWrite("Custom source '$sourceId'") { reloadCustomSource(sourceId) }
  }

  // GL JS serializes only JSON layers when recovering a lost context. Retain custom layer
  // positions before it destroys the style, then reattach them after the restored style loads.
  // Image sources come back without their pixels, which are applied again the same way.
  private var layerOrder = map.getLayersOrder().toList()
  private var restoringContext = false
  private val orderChanges =
    map.subscribe("styledata") {
      if (!restoringContext && map.asDynamic().style != null)
        layerOrder = map.getLayersOrder().toList()
    }
  private val contextLost =
    map.subscribe("webglcontextlost") {
      restoringContext = true
    }
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
        val pendingUrls = pendingImageSourceUrls.toMap()
        pendingImageSourceUrls.clear()
        imageSourceImages.forEach { (id, image) -> updateImageSource(id, image) }
        // Applying fallback pixels cancels the rebuilt source's URL request. Start it again.
        pendingUrls.forEach { (id, pending) -> setImageSourceUrl(id, pending.url) }
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
    imageSourceImages.clear()
    pendingImageSourceUrls.clear()
    imageSourceLoads.cancel()
    orderChanges.cancel()
    contextLost.cancel()
    contextStyleLoaded.cancel()
    errors.forEach { it.cancel() }
    customSourceReloads.forEach { it.cancel() }
    pendingCustomSourceReloads.clear()
    val vectorAttachments = customVectorAttachments.values.toList()
    val geometryAttachments = customGeometryAttachments.values.toList()
    customVectorAttachments.clear()
    customGeometryAttachments.clear()
    vectorAttachments.forEach { it.close() }
    geometryAttachments.forEach { it.close() }
  }

  override val supportsCustomDemEncoding: Boolean = true

  /** GL JS rejects a raster-dem source that carries a `scheme`, and reads only XYZ tiles. */
  override val supportsRasterDemScheme: Boolean = false

  override val baseLayers: List<LayerSummary> = layerSummaries()
  override val baseSources: Map<String, Source> =
    sourceIds().mapNotNull { id -> getSource(id)?.let { id to it } }.toMap()

  // GL JS runs the remove and add in one task, so no frame renders between them.
  override fun setImage(definition: StyleImageDefinition) {
    requireCurrent()
    val (id, image, sdf, stretch) = definition
    val scale = getScale()
    val pixels = image.pixels.styleImageData()
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
      val previous = map.getImage(id)
      if (previous != null) map.removeImage(id)
      val before = errorCount
      try {
        map.addImage(id, pixels, metadata)
      } finally {
        // A rejected replacement keeps the previous image instead of leaving none.
        if (previous != null && (errorCount != before || !map.hasImage(id))) {
          map.addImage(id, previous.data, previous.unsafeCast<StyleImageMetadata>())
        }
      }
      if (errorCount == before) {
        indicatorImages[id] = IndicatorImage(pixels, scale.toDouble())
        indicators.values.forEach { it.resourceChanged() }
      }
    }
  }

  override fun removeImage(id: String): Boolean {
    requireCurrent()
    indicatorImages.remove(id)
    indicators.values.forEach { it.resourceChanged() }
    if (!map.hasImage(id)) return false
    mutate("remove image '$id'") { map.removeImage(id) }
    return true
  }

  override fun imageExists(id: String): Boolean {
    requireCurrent()
    return map.hasImage(id)
  }

  override fun getSource(id: String): Source? {
    requireCurrent()
    return reconstructSource(id)
  }

  override fun sourceIds(): List<String> {
    requireCurrent()
    return map.style.tileManagers.keys().toList()
  }

  override fun layerSummaries(): List<LayerSummary> {
    requireCurrent()
    return layerIds().mapNotNull { id ->
      indicators[id]?.let {
        return@mapNotNull layerDefinitionFromJson(id, it.definition).summary()
      }
      map.getLayer(id)?.let { LayerSummary(id, it.type, it.source, it.sourceLayer) }
    }
  }

  override fun getLayer(id: String): LayerDefinition? {
    requireCurrent()
    indicators[id]?.let {
      return layerDefinitionFromJson(id, it.definition)
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
    requireCurrent()
    return if (restoringContext) layerOrder else map.getLayersOrder().toList()
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

  private fun reconstructLayer(layer: StyleLayer): LayerDefinition {
    val definition =
      layer.serialize().toJsonElement() as? JsonObject
        ?: buildJsonObject {
          put("id", layer.id)
          put("type", layer.type)
        }
    return layerDefinitionFromJson(layer.id, definition)
  }

  override fun addSource(sourceId: String, source: JsonObject): Boolean {
    requireCurrent()
    mutate("add source '$sourceId'") {
      map.addSource(sourceId, source.toJsValue<SourceSpecification>())
    }
    return true
  }

  override fun removeSource(sourceId: String) {
    requireCurrent()
    mutate("remove source '$sourceId'") { map.removeSource(sourceId) }
    imageSourceImages.remove(sourceId)
    pendingImageSourceUrls.remove(sourceId)
    pendingCustomSourceReloads.remove(sourceId)
    customVectorAttachments.remove(sourceId)?.close()
    customGeometryAttachments.remove(sourceId)?.close()
  }

  override fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean {
    requireCurrent()
    val attachment =
      GlJsCustomGeometryAttachment(sourceId, options, provider, logger, customTileFailed)
    val added =
      try {
        addSource(
          sourceId,
          buildJsonObject {
            put("type", "vector")
            putJsonArray("tiles") { add(attachment.tiles.tileUrlTemplate) }
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
    reloadCustomSource(sourceId)
  }

  override fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate) {
    reloadCustomSource(sourceId)
  }

  /**
   * Reloads every tile of a custom geometry or custom vector source. GL JS's `refreshTiles` skips
   * cached and still-loading tiles, so a fresh tile URL makes GL JS refetch every tile instead.
   */
  private fun reloadCustomSource(sourceId: String) {
    requireCurrent()
    val attachment =
      customVectorAttachments[sourceId] ?: customGeometryAttachments[sourceId]?.tiles ?: return
    val source = map.getSource<GlJsVectorSource>(sourceId) ?: return
    if (map.isSourceLoaded(sourceId) != true) {
      pendingCustomSourceReloads += sourceId
      return
    }
    val failed = attachment.failedTiles.toList()
    mutate("invalidate custom source '$sourceId'") {
      source.setTiles(arrayOf(attachment.invalidate()))
      // The new URL reloads an errored tile as loading, and a vector source then waits for a
      // response to a request it never sends. Refreshing the tile requests it again.
      if (failed.isNotEmpty())
        map.refreshTiles(
          sourceId,
          failed
            .map { tile ->
              unsafeJso<CanonicalTileId> {
                x = tile.x.toInt()
                y = tile.y.toInt()
                z = tile.zoomLevel
              }
            }
            .toTypedArray(),
        )
    }
  }

  override fun addCustomVectorSource(
    sourceId: String,
    options: CustomVectorTileSourceOptions,
    provider: VectorTileProvider,
  ): Boolean {
    requireCurrent()
    customVectorAttachments.remove(sourceId)?.close()
    val attachment =
      GlJsProtocolTileAttachment(
        name = "custom-vector-$sourceId",
        loadTile = { tile ->
          try {
            provider.loadTile(tile)
          } catch (error: Throwable) {
            // A cancelled job means the request ended. The provider's own cancellation, such as a
            // timeout, leaves the job active and fails like any other exception.
            if (error is CancellationException) currentCoroutineContext().ensureActive()
            // MapLibre reports the tile error as an `error` event with the message alone; this
            // record carries the exception.
            logger?.w(error) { "Custom vector tile source '$sourceId' failed to load $tile" }
            customTileFailed(error)
            throw error
          }
        },
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

  override fun invalidateCustomVectorSourceTile(sourceId: String, tile: TileCoordinate) {
    reloadCustomSource(sourceId)
  }

  override fun sourceExists(sourceId: String): Boolean? {
    requireCurrent()
    return map.getSource<SourceHandle>(sourceId) != null
  }

  /**
   * Adds the source without a URL and gives it the pixels in the same task, so no frame renders it
   * empty.
   */
  override fun addImageSourceImage(
    sourceId: String,
    coordinates: List<Position>,
    image: PreparedImage,
  ): Boolean {
    addSource(
      sourceId,
      buildJsonObject {
        put("type", "image")
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
    imageSourceImages[sourceId] = image
    updateImageSource(sourceId, image)
    return true
  }

  /** GL JS shows the pixels at once, cancelling a URL that is still loading. */
  override fun setImageSourceImage(sourceId: String, image: PreparedImage) {
    requireCurrent()
    pendingImageSourceUrls.remove(sourceId)
    imageSourceImages[sourceId] = image
    updateImageSource(sourceId, image)
  }

  private fun updateImageSource(sourceId: String, image: PreparedImage) {
    map
      .getSource<GlJsImageSource>(sourceId)
      ?.updateImage(unsafeJso { this.image = image.pixels.imageData() })
  }

  override fun setImageSourceUrl(sourceId: String, url: String) {
    requireCurrent()
    val source = map.getSource<GlJsImageSource>(sourceId) ?: return
    // Only prepared pixels need fallback recovery while their replacement URL loads.
    // An empty URL cancels a pending request without replacing the image in GL JS.
    if (url.isNotEmpty() && sourceId in imageSourceImages) {
      pendingImageSourceUrls[sourceId] = PendingImageSourceUrl(source, url)
    } else {
      pendingImageSourceUrls.remove(sourceId)
    }
    val options = unsafeJso<UpdateImageOptions> { this.url = url }
    source.updateImage(options)
  }

  override fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>) {
    requireCurrent()
    mutate("set the bounds of image source '$sourceId'") {
      // GL JS stores the coordinates before validating them. Validate before changing the source.
      coordinates.forEach { LngLat(it.longitude, it.latitude) }
      val corners = coordinates.map { arrayOf(it.longitude, it.latitude) }.toTypedArray()
      map.getSource<GlJsImageSource>(sourceId)?.setCoordinates(corners)
    }
  }

  override fun submitGeoJsonData(
    sourceId: String,
    data: GeoJsonData,
    fallbackOptions: GeoJsonOptions,
  ) {
    requireCurrent()
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
  ): Double? =
    queryCluster(sourceId, feature) { query ->
      query.source.getClusterExpansionZoom(query.clusterId).await()
    }

  override suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>? =
    queryCluster(sourceId, feature) { query ->
      query.source.getClusterChildren(query.clusterId).await().toFeatureCollection()
    }

  override suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>? =
    queryCluster(sourceId, feature) { query ->
      query.source
        .getClusterLeaves(
          query.clusterId,
          limit.coerceAtLeast(0).toDouble(),
          offset.coerceAtLeast(0).toDouble(),
        )
        .await()
        .toFeatureCollection()
    }

  private suspend fun <T> queryCluster(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    action: suspend (ClusterQuery) -> T,
  ): T? {
    val query = clusterQuery(sourceId, feature) ?: return null
    return try {
      action(query)
    } catch (error: Throwable) {
      // The worker transfers the missing-cluster error as an ordinary JavaScript Error.
      if (
        error is CancellationException ||
          error.message != "No cluster with the specified id: ${query.clusterId}"
      )
        throw error
      logger?.w { "Cluster query matched no cluster in source '$sourceId'" }
      null
    }
  }

  private class ClusterQuery(val source: GlJsGeoJsonSource, val clusterId: Double)

  /** Null when the feature is not a cluster or the style has unloaded. */
  private fun clusterQuery(sourceId: String, feature: Feature<*, JsonObject?>): ClusterQuery? {
    val clusterId =
      (feature.properties?.get(ClusterIdProperty) as? JsonPrimitive)?.doubleOrNull
        ?: run {
          logger?.w {
            "Cluster query on a feature with no '$ClusterIdProperty' in source '$sourceId'"
          }
          return null
        }
    requireCurrent()
    val source = map.getSource<GlJsGeoJsonSource>(sourceId) ?: return null
    return ClusterQuery(source, clusterId)
  }

  override fun prepareFeatureStateUpdate(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ): () -> Unit {
    val js = state.toJsValue<Any>()
    return {
      requireCurrent()
      for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
        mutate("set the feature state") { map.setFeatureState(ident, js) }
      }
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
    requireCurrent()
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
    requireCurrent()
    for (ident in featureIdentifiers(sourceId, sourceLayerId, featureId)) {
      if (stateKey == null) map.removeFeatureState(ident)
      else map.removeFeatureState(ident, stateKey)
    }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    requireCurrent()
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
    requireCurrent()
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
    requireCurrent()
    return action(map)
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean {
    requireCurrent()
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
    requireCurrent()
    map.removeLayer(layerId)
    indicators.remove(layerId)?.close()
    layerOrder = map.getLayersOrder().toList()
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    requireCurrent()
    if (beforeLayerId.isEmpty()) map.moveLayer(layerId) else map.moveLayer(layerId, beforeLayerId)
    layerOrder = map.getLayersOrder().toList()
  }

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    requireCurrent()
    indicators[layerId]?.let {
      it.update(name, value, kind)
      return
    }
    val js = value.toJsValue<Any?>()
    mutate("set '$name' on layer '$layerId'") {
      when (kind) {
        LayerPropertyKind.Layout -> map.setLayoutProperty(layerId, name, js)
        LayerPropertyKind.Paint -> map.setPaintProperty(layerId, name, js)
        LayerPropertyKind.Root -> setRootProperty(layerId, name, value)
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
    requireCurrent()
    // The style spec has no null filter; absent means "match every feature".
    val js = if (filter is JsonNull) null else filter.toJsValue<FilterSpecification>()
    mutate("set the filter on layer '$layerId'") { map.setFilter(layerId, js) }
  }

  /**
   * Trying paint before layout is safe: the style spec gives no layer type a name in both. MapLibre
   * throws rather than answering for a name it does not have.
   */
  override fun layerProperty(layerId: String, name: String): JsonElement? {
    requireCurrent()
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

  override fun transition(): TransitionOptions? {
    requireCurrent()
    val transition = map.style.getTransition()
    return TransitionOptions(
      duration = transition.duration?.milliseconds ?: 300.milliseconds,
      delay = transition.delay?.milliseconds ?: Duration.ZERO,
    )
  }

  override fun setTransition(options: TransitionOptions) {
    requireCurrent()
    map.style.stylesheet.transition =
      unsafeJso<TransitionSpecification> {
        duration = options.duration.toDouble(DurationUnit.MILLISECONDS)
        delay = options.delay.toDouble(DurationUnit.MILLISECONDS)
      }
  }

  override val supportsPlacementTransitions: Boolean = false

  override fun placementTransitions(): Boolean? {
    requireCurrent()
    return true
  }

  override fun setPlacementTransitions(enabled: Boolean) {
    requireCurrent()
    if (!enabled) {
      logger?.w { "MapLibre GL JS cannot switch the symbol placement cross-fade at runtime" }
    }
  }

  override fun globalState(): JsonObject {
    requireCurrent()
    return map.getGlobalState().toJsonElement().jsonObject
  }

  override fun setGlobalStateProperty(name: String, value: JsonElement) {
    requireCurrent()
    val js = value.toJsValue<Any?>()
    mutate("set global state") { map.setGlobalStateProperty(name, js) }
  }

  override fun lightProperty(name: String): JsonElement? {
    requireCurrent()
    return map.getLight().asDynamic()[name].unsafeCast<Any?>()?.toJsonElement()
  }

  /**
   * MapLibre merges the given properties into the light, so every property it holds and [light]
   * omits is cleared with a null in a second, unvalidated write, after the first write has
   * validated the values.
   */
  override fun setLight(light: JsonObject) {
    requireCurrent()
    replace<LightSpecification>("set the light", map.getLight(), light) { value, options ->
      map.setLight(value, options)
    }
  }

  override val supportsSky: Boolean = true

  override fun skyProperty(name: String): JsonElement? {
    requireCurrent()
    val sky = map.getSky() ?: return null
    return sky.asDynamic()[name].unsafeCast<Any?>()?.toJsonElement()
  }

  /** Merges like the light. MapLibre treats an absent sky as no sky. */
  override fun setSky(sky: JsonObject?) {
    requireCurrent()
    if (sky == null) {
      val options = unsafeJso<StyleSetterOptions> { validate = false }
      mutate("remove the sky") { map.setSky(null, options) }
    } else {
      replace<SkySpecification>("set the sky", map.getSky(), sky) { value, options ->
        map.setSky(value, options)
      }
    }
  }

  override val supportsProjection: Boolean = true

  override fun projectionProperty(name: String): JsonElement? {
    requireCurrent()
    val projection = map.getProjection() ?: return null
    return projection.asDynamic()[name].unsafeCast<Any?>()?.toJsonElement()
  }

  /** MapLibre falls back to Mercator for an unknown name with a console warning, not an error. */
  override fun setProjection(projection: JsonObject) {
    requireCurrent()
    mutate("set the projection") {
      map.setProjection(projection.toJsValue<ProjectionSpecification>())
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
    requireCurrent()
    return map.getLayer(layerId) != null
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
