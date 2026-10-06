package org.maplibre.compose.style

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.layers.LayerSummary
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MlnFfiMapRuntimeLoop
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.MlnFfiTileRequestCoordinator
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.featureStateSelector
import org.maplibre.compose.sources.putClusterProperties
import org.maplibre.compose.sources.reconstructedSource
import org.maplibre.compose.sources.toInlineUtf8
import org.maplibre.compose.sources.toMlnFfiTileId
import org.maplibre.compose.sources.toStyleSpecEncoding
import org.maplibre.compose.sources.toStyleSpecType
import org.maplibre.compose.sources.toTileCoordinate
import org.maplibre.compose.util.PreparedImage
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toFfiClusterFeature
import org.maplibre.compose.util.toGeoJsonFeatures
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.compose.util.toJsonElement
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.error.NativeErrorException
import org.maplibre.nativeffi.geo.CanonicalTileId
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.query.SourceFeatureQueryOptions
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.style.CustomGeometrySourceCallback
import org.maplibre.nativeffi.style.CustomGeometrySourceOptions as FfiCustomGeometrySourceOptions
import org.maplibre.nativeffi.style.CustomMvtVectorSourceCallback
import org.maplibre.nativeffi.style.CustomMvtVectorSourceOptions
import org.maplibre.nativeffi.style.GeoJsonSourceDataHandle
import org.maplibre.nativeffi.style.GeoJsonSourceOptions
import org.maplibre.nativeffi.style.ImageContent
import org.maplibre.nativeffi.style.ImageStretch as FfiImageStretch
import org.maplibre.nativeffi.style.SourceType
import org.maplibre.nativeffi.style.StyleImageOptions
import org.maplibre.nativeffi.style.TileJson
import org.maplibre.nativeffi.style.TileScheme
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

/** Reaches the render session of a map, which lives on its renderer thread. */
internal interface MlnFfiRenderSessions {
  /**
   * Runs [action] with the ready render session and suspends until it returns. Returns null when no
   * render session is ready, or its thread can no longer run [action]. The handle must not escape
   * [action].
   */
  suspend fun <T> awaitRenderSession(action: (RenderSessionHandle) -> T): T?

  object None : MlnFfiRenderSessions {
    override suspend fun <T> awaitRenderSession(action: (RenderSessionHandle) -> T): T? = null
  }
}

/**
 * [StyleBinding] for one loaded style in a MapLibre Native map. Construction runs on [loop]'s owner
 * thread while [map] is alive; initial metadata is captured before publication.
 *
 * Engine reads and mutations call the map directly on the shared runtime owner thread. Handles and
 * commands use [awaitOwner] or [postOwner]; accepted visits apply their operations inline. Feature
 * queries stay on the renderer, and data preparation stays on workers.
 */
internal open class MlnFfiStyleBinding(
  map: MapHandle,
  /** The loop whose owner thread runs this binding's engine calls. Internal for tests. */
  internal val loop: MlnFfiMapRuntimeLoop,
  override val identity: StyleIdentity = StyleIdentity.create(),
  private val loggerProvider: () -> MapLog? = { null },
  private val sessionOpen: () -> Boolean = { false },
  private val renderSessions: MlnFfiRenderSessions = MlnFfiRenderSessions.None,
  private val sourceChanged: (String) -> Unit = {},
  private val sourceDataFailed: (StyleIdentity, String, Throwable) -> Unit = { _, _, _ -> },
  private val getScale: () -> Float = { 1f },
) : StyleBinding {
  @Volatile private var loaded = true
  private val geoJsonCoordinators =
    mutableMapOf<String, MlnFfiGeoJsonCoordinator<GeoJsonSourceDataHandle>>()
  private val geoJsonLock = MlnFfiLock()

  /** The tile coordinators serving this loaded style's custom sources, by source id. */
  private val tileCoordinators = mutableMapOf<String, MlnFfiTileRequestCoordinator<*>>()
  private val tileLock = MlnFfiLock()

  override val isLoaded: Boolean
    get() = loaded && sessionOpen()

  override val animatorDurationScale: Float
    get() = systemAnimatorDurationScale()

  override val logger: MapLog?
    get() = loggerProvider()

  private var declaredSources: JsonObject? = null

  override val baseLayers: List<LayerSummary> =
    map.styleLayers().map { layer ->
      LayerSummary(layer.id, layer.type, layer.sourceId, layer.sourceLayer)
    }
  override val baseSources: Map<String, Source> =
    map.styleSourceIds().associateWith { reconstructSource(map, it) }

  /** Runs [action] through [MlnFfiMapRuntimeLoop.await]. */
  override suspend fun <T> awaitOwner(action: () -> T): T? {
    if (!isLoaded) return null
    return loop.await { if (isLoaded) action() else null }
  }

  override fun postOwner(action: () -> Unit) {
    requireCurrent()
    submit {
      try {
        action()
      } catch (error: StyleHandleException) {
        // A style unloaded during this accepted visit has nothing left to update.
        if (isLoaded) throw error
      }
    }
  }

  /**
   * Runs [action] with the map, on the owner thread only.
   *
   * @throws IllegalStateException on any other thread.
   * @throws StyleHandleException when the style has unloaded.
   */
  internal fun <T> withMap(action: (MapHandle) -> T): T {
    check(loop.isOwnerThread()) {
      "A MapLibre Native style call ran off the map's owner thread; use awaitOwner or postOwner"
    }
    requireCurrent()
    return action(checkNotNull(loop.map) { "The map owner loop has no map" })
  }

  override fun setImage(definition: StyleImageDefinition) = mutateMap { map ->
    val (id, image, sdf, stretch) = definition
    val scale = getScale()
    val stretchPx = stretch?.resolve(image.width, image.height, scale)
    // The engine replaces an existing image in place, so no existence read is needed.
    map.setStyleImage(
      imageId = id,
      image = image.pixels.ffi,
      options =
        StyleImageOptions().also { options ->
          options.sdf = sdf
          options.pixelRatio = scale
          stretchPx?.let { px ->
            if (px.stretchX.isNotEmpty()) {
              options.stretchX = px.stretchX.map { (start, end) -> FfiImageStretch(start, end) }
            }
            if (px.stretchY.isNotEmpty()) {
              options.stretchY = px.stretchY.map { (start, end) -> FfiImageStretch(start, end) }
            }
            px.content?.let { box ->
              options.content = ImageContent(box.left, box.top, box.right, box.bottom)
            }
          }
        },
    )
  }

  override fun removeImage(id: String): Boolean = mutateMap {
    it.removeStyleImage(id)
  }

  override fun imageExists(id: String): Boolean? = withMap { it.styleImageInfo(id) != null }

  override fun getSource(id: String): Source? = withMap { map ->
    if (!map.styleSourceExists(id)) null else reconstructSource(map, id)
  }

  override fun sourceIds(): List<String> = withMap { it.styleSourceIds() }

  override fun getLayer(id: String): LayerDefinition? = withMap { map ->
    if (!map.styleLayerExists(id)) null else reconstructLayer(map, id)
  }

  /** The full engine order: insertions and moves are relative to it. */
  override fun layerIds(): List<String> = withMap { it.styleLayerIds() }

  override fun layerSummaries(): List<LayerSummary> = withMap { map ->
    map.styleLayers().map { LayerSummary(it.id, it.type, it.sourceId, it.sourceLayer) }
  }

  private fun reconstructSource(map: MapHandle, id: String): Source =
    reconstructedSource(id, sourceDefinition(map, id))

  private fun sourceDefinition(map: MapHandle, id: String): JsonObject {
    val info = map.styleSourceInfo(id)
    return buildJsonObject {
      (info?.type ?: map.styleSourceType(id))?.toStyleSpecType()?.let { put("type", it) }
      val attribution =
        info?.attribution?.takeIf { it.isNotEmpty() } ?: declaredAttribution(map, id)
      attribution?.let { put("attribution", it) }
      info?.tileSize?.takeIf { it > 0 }?.let { put("tileSize", it) }
      if (info?.volatileSource == true) put("volatile", true)
      if (info?.type == SourceType.VECTOR)
        info.vectorEncoding?.toStyleSpecEncoding()?.let { put("encoding", it) }
      if (info?.type == SourceType.RASTER_DEM)
        info.rasterDemEncoding?.toStyleSpecEncoding()?.let { put("encoding", it) }
      val url = info?.url?.takeIf { it.isNotEmpty() }
      if (url != null) put("url", url) else info?.tileJson?.let { putTileJson(it) }
    }
  }

  private fun JsonObjectBuilder.putTileJson(tileJson: TileJson) {
    if (tileJson.tileUrls.isNotEmpty())
      putJsonArray("tiles") { tileJson.tileUrls.forEach { add(it) } }
    put("minzoom", tileJson.minZoom)
    put("maxzoom", tileJson.maxZoom)
    when (tileJson.scheme) {
      TileScheme.XYZ -> put("scheme", "xyz")
      TileScheme.TMS -> put("scheme", "tms")
      else -> Unit
    }
    tileJson.bounds?.toBoundingBox()?.let { box ->
      putJsonArray("bounds") {
        add(box.west)
        add(box.south)
        add(box.east)
        add(box.north)
      }
    }
  }

  private fun declaredAttribution(map: MapHandle, id: String): String? {
    val sources =
      declaredSources
        ?: run {
          val document = runCatching { map.loadedStyleJson().toJsonElement() }.getOrNull()
          ((document as? JsonObject)?.get("sources") as? JsonObject ?: JsonObject(emptyMap()))
            .also { declaredSources = it }
        }
    return ((sources[id] as? JsonObject)?.get("attribution") as? JsonPrimitive)?.contentOrNull
  }

  private fun reconstructLayer(map: MapHandle, id: String): LayerDefinition {
    val definition =
      (map.styleLayerJson(id)?.toJsonElement() as? JsonObject)
        ?: buildJsonObject { map.styleLayerType(id)?.let { put("type", it) } }
    return layerDefinitionFromJson(id, definition)
  }

  override fun invalidate() {
    if (!loaded) return
    loaded = false
    val coordinators = geoJsonLock.withLock {
      geoJsonCoordinators.values.toList().also { geoJsonCoordinators.clear() }
    }
    coordinators.forEach { it.close() }
    val tiles = tileLock.withLock {
      tileCoordinators.values.toList().also { tileCoordinators.clear() }
    }
    tiles.forEach { it.close() }
  }

  /**
   * Runs [action] through [MlnFfiMapRuntimeLoop.submit] and returns at once. [onDropped] runs
   * instead when the style has unloaded or the loop stops first, and after [action] when it throws.
   */
  open fun submit(onDropped: () -> Unit = {}, action: (MapHandle) -> Unit) {
    if (!isLoaded) return onDropped()
    loop.submit(onDropped = onDropped) { map -> if (isLoaded) action(map) else onDropped() }
  }

  /** Owner thread only. Reports an engine refusal as a [StyleMutationException]. */
  private fun <T> mutateMap(action: (MapHandle) -> T): T = withMap { map ->
    try {
      action(map)
    } catch (error: MaplibreException) {
      throw StyleMutationException(error.message, error)
    }
  }

  fun setSourceVolatile(sourceId: String, value: Boolean) {
    mutateMap { it.setStyleSourceVolatile(sourceId, value) }
  }

  /** Delegates to the map's [MlnFfiRenderSessions]. */
  suspend fun <T> awaitRenderSession(action: (RenderSessionHandle) -> T): T? {
    requireCurrent()
    return renderSessions.awaitRenderSession(action).also {
      if (it == null) logger?.d { "Ignoring a render session call: no session is ready yet" }
    }
  }

  /**
   * MapLibre Native implements no encoding but mapbox and terrarium.
   * [#2783](https://github.com/maplibre/maplibre-native/issues/2783)
   */
  final override val supportsCustomDemEncoding: Boolean
    get() = false

  final override val supportsRasterDemScheme: Boolean
    get() = true

  override fun addSource(sourceId: String, source: JsonObject): Boolean =
    addSourceWith(sourceId) { map -> map.addStyleSourceJson(sourceId, source.toJsonBytes()) }

  /**
   * Adds a source on the owner thread, for the types MapLibre Native creates from a typed adder
   * rather than from source JSON. Wraps a refusal the way [addSource] does.
   *
   * @return false if the style has unloaded, in which case [add] did not run.
   */
  fun addSourceWith(sourceId: String, add: (MapHandle) -> Unit): Boolean = mutateMap { map ->
    add(map)
    sourceChanged(sourceId)
    true
  }

  override fun removeSource(sourceId: String) {
    mutateMap { map ->
      map.removeStyleSource(sourceId)
      identity.sources.remove(sourceId)
      geoJsonLock.withLock { geoJsonCoordinators.remove(sourceId) }?.close()
      sourceChanged(sourceId)
    }
    removeTileCoordinator(sourceId)
  }

  override fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean {
    val coordinator =
      MlnFfiTileRequestCoordinator(
        name = "maplibre-custom-geometry-$sourceId",
        binding = this,
        load = { tile -> provider.loadTile(tile).toJson().encodeToByteArray() },
        deliver = { map, tile, data -> map.setCustomGeometrySourceTileData(sourceId, tile, data) },
        fail = { map, tile, error ->
          logger?.e(error) {
            "Loading tile ${tile.toTileCoordinate()} of source '$sourceId' failed"
          }
          map.setCustomGeometrySourceTileData(sourceId, tile, EmptyFeatureCollection)
        },
      )
    val callback =
      object : CustomGeometrySourceCallback {
        override fun fetchTile(tileId: CanonicalTileId) {
          coordinator.fetch(tileId)
        }

        override fun cancelTile(tileId: CanonicalTileId) {
          coordinator.cancel(tileId)
        }
      }
    return installCoordinator(sourceId, coordinator) { map ->
      map.addCustomGeometrySource(
        sourceId,
        FfiCustomGeometrySourceOptions(callback).also {
          it.minZoom = options.minZoom.toDouble()
          it.maxZoom = options.maxZoom.toDouble()
          it.buffer = options.buffer
          it.tolerance = options.tolerance.toDouble()
          it.clip = options.clip
          it.wrap = options.wrap
        },
      )
    }
  }

  override fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox) {
    mutateMap { map ->
      map.invalidateCustomGeometrySourceRegion(sourceId, bounds.toLatLngBounds())
    }
  }

  override fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate) {
    mutateMap { map ->
      map.invalidateCustomGeometrySourceTile(sourceId, tile.toMlnFfiTileId())
    }
  }

  override fun addCustomVectorSource(
    sourceId: String,
    options: CustomVectorTileSourceOptions,
    provider: VectorTileProvider,
  ): Boolean {
    val coordinator =
      MlnFfiTileRequestCoordinator(
        name = "maplibre-custom-vector-$sourceId",
        binding = this,
        load = { tile ->
          try {
            provider.loadTile(tile)
          } catch (error: Throwable) {
            rethrowIfFatal(error)
            // A cancelled job means the request ended. The provider's own cancellation, such as a
            // timeout, leaves the job active and fails like any other exception.
            if (error is CancellationException) currentCoroutineContext().ensureActive()
            // Logged here rather than in `fail`, which a cancelled or replaced request skips.
            // MapLibre logs the tile error as an error with the message alone; this record
            // carries the exception.
            logger?.w(error) { "Custom vector tile source '$sourceId' failed to load $tile" }
            throw error
          }
        },
        deliver = { map, tile, data -> map.setCustomMvtVectorSourceTileData(sourceId, tile, data) },
        fail = { map, tile, error ->
          map.setCustomMvtVectorSourceTileError(
            sourceId,
            tile,
            error.message ?: "Tile loading failed",
          )
        },
      )
    val callback =
      object : CustomMvtVectorSourceCallback {
        override fun fetchTile(tileId: CanonicalTileId) {
          coordinator.fetch(tileId)
        }

        override fun cancelTile(tileId: CanonicalTileId) {
          coordinator.cancel(tileId)
        }
      }
    return installCoordinator(sourceId, coordinator) { map ->
      map.addCustomMvtVectorSource(
        sourceId,
        CustomMvtVectorSourceOptions(callback).also {
          it.minZoom = options.minZoom.toDouble()
          it.maxZoom = options.maxZoom.toDouble()
        },
      )
    }
  }

  override fun invalidateCustomVectorSourceTile(sourceId: String, tile: TileCoordinate) {
    mutateMap { map ->
      map.invalidateCustomMvtVectorSourceTile(sourceId, tile.toMlnFfiTileId())
    }
  }

  /**
   * Stores [coordinator] before [add] runs, so a fetch fired during the add is answered. The
   * coordinator closes on remove, on unload, when the add fails, and at once on an unloaded style.
   */
  private fun installCoordinator(
    sourceId: String,
    coordinator: MlnFfiTileRequestCoordinator<*>,
    add: (MapHandle) -> Unit,
  ): Boolean {
    // Checked under the lock that invalidate() takes after unloading, so no coordinator outlives
    // the style.
    val replaced = tileLock.withLock {
      if (isLoaded) tileCoordinators.put(sourceId, coordinator) else coordinator
    }
    replaced?.close()
    val added =
      try {
        addSourceWith(sourceId, add)
      } catch (error: Throwable) {
        removeTileCoordinator(sourceId)
        throw error
      }
    if (!added) removeTileCoordinator(sourceId)
    return added
  }

  private fun removeTileCoordinator(sourceId: String) {
    tileLock.withLock { tileCoordinators.remove(sourceId) }?.close()
  }

  override fun sourceExists(sourceId: String): Boolean? = withMap { map ->
    map.styleSourceExists(sourceId)
  }

  override fun addImageSourceImage(
    sourceId: String,
    coordinates: List<Position>,
    image: PreparedImage,
  ): Boolean {
    val corners = coordinates.map { it.toLatLng() }
    return addSourceWith(sourceId) { map ->
      map.addImageSourceImage(sourceId, corners, image.pixels.ffi)
    }
  }

  override fun setImageSourceImage(sourceId: String, image: PreparedImage) {
    mutateMap { map -> map.setImageSourceImage(sourceId, image.pixels.ffi) }
  }

  override fun setImageSourceUrl(sourceId: String, url: String) {
    mutateMap { map -> map.setImageSourceUrl(sourceId, url) }
  }

  override fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>) {
    val corners = coordinates.map { it.toLatLng() }
    mutateMap { map -> map.setImageSourceCoordinates(sourceId, corners) }
  }

  override fun addGeoJsonSource(
    sourceId: String,
    data: GeoJsonData,
    options: GeoJsonOptions,
  ): Boolean {
    val ffiOptions = options.toFfiOptions()
    return addSourceWith(sourceId) { map ->
      if (data is GeoJsonData.Uri) {
        map.addGeoJsonSourceUrl(sourceId, data.uri, ffiOptions)
      } else {
        GeoJsonSourceDataHandle.create(EmptyFeatureCollection, ffiOptions).use { empty ->
          map.addGeoJsonSourceData(sourceId, empty)
        }
      }
      val coordinator = geoJsonCoordinator(sourceId, ffiOptions)
      // Register initial data before notifying source observers, which can submit newer data.
      if (data !is GeoJsonData.Uri) {
        coordinator.submit(data) { error("Expected inline data") }
      }
    }
  }

  override fun submitGeoJsonData(
    sourceId: String,
    data: GeoJsonData,
    fallbackOptions: GeoJsonOptions,
  ) {
    withMap { map ->
      val coordinator =
        geoJsonLock.withLock { geoJsonCoordinators[sourceId] }
          ?: geoJsonCoordinator(
            sourceId,
            loadedGeoJsonOptions(map, sourceId) ?: fallbackOptions.toFfiOptions(),
          )
      try {
        coordinator.submit(data) { url -> map.setGeoJsonSourceUrl(sourceId, url) }
      } catch (error: MaplibreException) {
        val failure = StyleMutationException(error.message, error)
        sourceDataFailed(identity, sourceId, failure)
        throw failure
      }
    }
  }

  private fun prepareGeoJson(
    data: GeoJsonData,
    options: GeoJsonSourceOptions,
  ): GeoJsonSourceDataHandle =
    try {
      GeoJsonSourceDataHandle.create(checkNotNull(data.toInlineUtf8()), options)
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      rethrowIfFatal(error)
      throw StyleMutationException(error.message, error)
    }

  /** Called on the owner thread. Each replacement gets a new coordinator and fixed options. */
  private fun geoJsonCoordinator(
    sourceId: String,
    options: GeoJsonSourceOptions,
  ): MlnFfiGeoJsonCoordinator<GeoJsonSourceDataHandle> {
    val coordinator =
      MlnFfiGeoJsonCoordinator(
        prepare = { data -> prepareGeoJson(data, options) },
        // Not cancellable: the prepared data is freed when install returns.
        install = { prepared, isCurrent ->
          if (isLoaded) {
            loop.await(cancellable = false) { map ->
              if (isLoaded && isCurrent()) map.setGeoJsonSourceData(sourceId, prepared)
            }
          }
        },
        reportFailure = { error, isCurrent ->
          if (isLoaded) {
            loop.await(cancellable = false) {
              if (isLoaded && isCurrent()) {
                logger?.w(error) { "Could not update GeoJSON source '$sourceId'" }
                sourceDataFailed(identity, sourceId, error)
              }
            }
          }
        },
      )
    geoJsonLock
      .withLock {
        if (!isLoaded) {
          coordinator.close()
          throw StyleHandleException("Style operation belongs to a stale loaded-style identity")
        }
        geoJsonCoordinators.put(sourceId, coordinator)
      }
      ?.close()
    return coordinator
  }

  /** Native still-image requests must include data submitted by the desired revision. */
  internal suspend fun awaitGeoJsonUpdates() {
    // The owner barrier includes accepted URL updates and newly created coordinators.
    val coordinators = awaitOwner { geoJsonLock.withLock { geoJsonCoordinators.values.toList() } }
    coordinators?.forEach { it.awaitLatest() }
    requireCurrent()
  }

  private fun loadedGeoJsonOptions(map: MapHandle, sourceId: String): GeoJsonSourceOptions? {
    val document = runCatching { map.loadedStyleJson().toJsonElement() }.getOrNull() as? JsonObject
    val sources = document?.get("sources") as? JsonObject
    val source = sources?.get(sourceId) as? JsonObject ?: return null
    if ((source["type"] as? JsonPrimitive)?.content != "geojson") return null
    val defaults = GeoJsonOptions()
    val minZoom = (source["minzoom"] as? JsonPrimitive)?.doubleOrNull ?: defaults.minZoom.toDouble()
    val maxZoom = (source["maxzoom"] as? JsonPrimitive)?.doubleOrNull ?: defaults.maxZoom.toDouble()
    return GeoJsonSourceOptions().also { options ->
      options.minZoom = minZoom
      options.maxZoom = maxZoom
      options.tolerance =
        (source["tolerance"] as? JsonPrimitive)?.doubleOrNull ?: defaults.tolerance.toDouble()
      options.buffer = (source["buffer"] as? JsonPrimitive)?.intOrNull ?: defaults.buffer
      options.cluster = (source["cluster"] as? JsonPrimitive)?.booleanOrNull ?: defaults.cluster
      options.clusterRadius =
        (source["clusterRadius"] as? JsonPrimitive)?.intOrNull ?: defaults.clusterRadius
      options.clusterMaxZoom =
        (source["clusterMaxZoom"] as? JsonPrimitive)?.doubleOrNull ?: maxZoom - 1.0
      options.clusterMinPoints =
        (source["clusterMinPoints"] as? JsonPrimitive)?.intOrNull ?: defaults.clusterMinPoints
      options.lineMetrics =
        (source["lineMetrics"] as? JsonPrimitive)?.booleanOrNull ?: defaults.lineMetrics
      options.synchronousTiling = defaults.synchronousTiling
      options.clusterProperties = (source["clusterProperties"] as? JsonObject)?.toJsonBytes()
    }
  }

  override suspend fun clusterExpansionZoom(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): Double? {
    val result = queryClusterExtension(sourceId, feature, ExpansionZoomField) ?: return null
    val zoom = result.decodeToString().toDoubleOrNull()
    if (zoom == null) reportClusterMiss(sourceId, ExpansionZoomField, result)
    return zoom
  }

  override suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>? =
    queryClusterFeatures(sourceId, feature, ChildrenField, null)

  override suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>? =
    queryClusterFeatures(
      sourceId,
      feature,
      LeavesField,
      // Both must be unsigned: MapLibre type-checks them exactly and silently falls back to its own
      // default of ten otherwise, and it ignores offset unless limit is present. A non-negative
      // integer literal parses as unsigned.
      // https://github.com/maplibre/maplibre-native-ffi/pull/340
      buildJsonObject {
        put("limit", limit.coerceAtLeast(0))
        put("offset", offset.coerceAtLeast(0))
      }
        .toJsonBytes(),
    )

  /**
   * Runs one supercluster query against the render session. Returns null when the feature carries
   * no cluster id, when no render session is attached yet, or when the engine reports no cluster.
   */
  private suspend fun queryClusterExtension(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray? = null,
  ): ByteArray? {
    val ffiFeature = feature.toFfiClusterFeature() ?: return null
    return try {
      awaitRenderSession { session ->
        session.queryFeatureExtension(
          sourceId,
          ffiFeature,
          SuperclusterExtension,
          field,
          arguments,
        )
      }
    } catch (error: NativeErrorException) {
      // Supercluster exposes no typed missing-cluster error through the C API.
      if (error.diagnostic != "No cluster with the specified id.") throw error
      logger?.w { "Cluster '$field' query matched no cluster in source '$sourceId'" }
      null
    }
  }

  private suspend fun queryClusterFeatures(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray?,
  ): FeatureCollection<Geometry, JsonObject?>? {
    val result = queryClusterExtension(sourceId, feature, field, arguments) ?: return null
    val collection =
      FeatureCollection.fromJsonOrNull<Geometry, JsonObject?>(result.decodeToString())
    if (collection == null) reportClusterMiss(sourceId, field, result)
    return collection
  }

  /** Reports a cluster query whose result does not contain the requested value. */
  private fun reportClusterMiss(sourceId: String, field: String, result: ByteArray) {
    logger?.w {
      "Cluster '$field' query matched no cluster in source '$sourceId'; the feature's cluster_id " +
        "is probably stale. MapLibre answered with ${result.decodeToString()}."
    }
  }

  override fun prepareFeatureStateUpdate(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ): () -> Unit {
    val bytes = state.toJsonBytes()
    val selector = featureStateSelector(sourceId, sourceLayerId, featureId)
    return { mutateMap { map -> map.setFeatureState(selector, bytes) } }
  }

  override fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject = withMap { map ->
    Json.parseToJsonElement(
        map
          .getFeatureState(featureStateSelector(sourceId, sourceLayerId, featureId))
          .decodeToString()
      )
      .jsonObject
  }

  override fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    mutateMap { map ->
      map.removeFeatureState(featureStateSelector(sourceId, sourceLayerId, featureId, stateKey))
    }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    mutateMap { map ->
      map.removeFeatureState(featureStateSelector(sourceId, sourceLayerId))
    }
  }

  /** Empty rather than an exception when no render session is attached. */
  override suspend fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> {
    if (sourceLayerIds.isEmpty()) return emptyList()
    val options =
      SourceFeatureQueryOptions().also {
        it.sourceLayerIds = sourceLayerIds.toList()
        it.filter = filter?.toJsonBytes()
      }
    return awaitRenderSession { session -> session.querySourceFeatures(sourceId, options) }
      ?.toGeoJsonFeatures()
      .orEmpty()
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean = mutateMap { map ->
    map.addStyleLayerJson(layer.toJsonBytes(), beforeLayerId)
    true
  }

  override fun removeLayer(layerId: String) {
    mutateMap { map -> map.removeStyleLayer(layerId) }
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    mutateMap { map -> map.moveStyleLayer(layerId, beforeLayerId) }
  }

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    mutateMap { map ->
      when {
        kind == LayerPropertyKind.Root && name == "filter" ->
          map.setLayerFilter(layerId, value.toJsonBytes())
        kind != LayerPropertyKind.Root -> map.setLayerProperty(layerId, name, value.toJsonBytes())
        name == "source" -> map.setLayerSourceId(layerId, value.requireRootString(layerId, name))
        name == "source-layer" ->
          map.setLayerSourceLayer(layerId, value.requireRootString(layerId, name))
        name == "minzoom" -> map.setLayerMinZoom(layerId, value.requireRootNumber(layerId, name))
        name == "maxzoom" -> map.setLayerMaxZoom(layerId, value.requireRootNumber(layerId, name))
        else -> map.setLayerProperty(layerId, name, value.toJsonBytes())
      }
    }
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    mutateMap { map ->
      map.setLayerFilter(layerId, filter.toJsonBytes())
    }
  }

  override fun layerProperty(layerId: String, name: String): JsonElement? = withMap { map ->
    when (name) {
      "id" -> JsonPrimitive(layerId)
      "type" -> map.styleLayerType(layerId)?.let(::JsonPrimitive)
      "source" -> map.layerSourceId(layerId).takeIf(String::isNotEmpty)?.let(::JsonPrimitive)
      "source-layer" ->
        map.layerSourceLayer(layerId).takeIf(String::isNotEmpty)?.let(::JsonPrimitive)
      "minzoom" -> map.layerMinZoom(layerId).takeIf(Double::isFinite)?.let(::JsonPrimitive)
      "maxzoom" -> map.layerMaxZoom(layerId).takeIf(Double::isFinite)?.let(::JsonPrimitive)
      "filter" -> map.layerFilter(layerId)?.toJsonElement()
      else -> map.layerProperty(layerId, name)?.toJsonElement()
    }
  }

  /** An unset native duration applies paint changes instantly, so it reads as zero. */
  override fun transition(): TransitionOptions? = withMap { map ->
    val options = map.styleTransitionOptions()
    TransitionOptions(
      duration = options.durationMs?.milliseconds ?: Duration.ZERO,
      delay = options.delayMs?.milliseconds ?: Duration.ZERO,
    )
  }

  /** Native replaces every field on write, so the placement flag is read back first. */
  override fun setTransition(options: TransitionOptions) {
    mutateMap { map ->
      map.setStyleTransitionOptions(
        map.styleTransitionOptions().copy {
          durationMs = options.duration.toDouble(DurationUnit.MILLISECONDS)
          delayMs = options.delay.toDouble(DurationUnit.MILLISECONDS)
        }
      )
    }
  }

  override val supportsPlacementTransitions: Boolean = true

  override fun placementTransitions(): Boolean? = withMap { map ->
    map.styleTransitionOptions().enablePlacementTransitions ?: true
  }

  override fun setPlacementTransitions(enabled: Boolean) {
    mutateMap { map ->
      map.setStyleTransitionOptions(
        map.styleTransitionOptions().copy { enablePlacementTransitions = enabled }
      )
    }
  }

  override fun globalState(): JsonObject? = withMap { map ->
    Json.parseToJsonElement(map.getGlobalState().decodeToString()).jsonObject
  }

  override fun setGlobalStateProperty(name: String, value: JsonElement) {
    val bytes = value.toJsonBytes()
    mutateMap { map -> map.setGlobalStateProperty(name, bytes) }
  }

  override fun lightProperty(name: String): JsonElement? = withMap { map ->
    map.styleLightProperty(name)?.toJsonElement()
  }

  override fun setLight(light: JsonObject) {
    val bytes = light.toJsonBytes()
    mutateMap { map -> map.setStyleLightJson(bytes) }
  }

  override val supportsSky: Boolean = false

  override fun skyProperty(name: String): JsonElement? {
    requireCurrent()
    return null
  }

  override fun setSky(sky: JsonObject?) {
    requireCurrent()
    if (sky != null) logger?.w { "MapLibre Native does not support the sky" }
  }

  override val supportsProjection: Boolean = false

  override fun projectionProperty(name: String): JsonElement? {
    requireCurrent()
    return null
  }

  override fun setProjection(projection: JsonObject) {
    requireCurrent()
    if (projection["type"] != JsonPrimitive("mercator")) {
      logger?.w { "MapLibre Native supports only the Mercator projection" }
    }
  }

  override fun layerExists(layerId: String): Boolean? = withMap { map ->
    map.styleLayerExists(layerId)
  }

  // A property's transition travels the same write path, and native refuses it as hard as the
  // property itself.
  override fun unsupportedLayerPropertyReason(layerType: String, name: String): String? =
    UnsupportedLayerProperties[layerType to name.removeSuffix(TransitionSuffix)]

  companion object {
    /** The only extension MapLibre answers for a GeoJSON source; anything else returns nothing. */
    private const val SuperclusterExtension = "supercluster"

    /** Delivered for a tile whose provider failed, so the map's load can finish. */
    private val EmptyFeatureCollection =
      """{"type":"FeatureCollection","features":[]}""".encodeToByteArray()

    private const val ExpansionZoomField = "expansion-zoom"
    private const val ChildrenField = "children"
    private const val LeavesField = "leaves"

    /**
     * Style-spec properties MapLibre Native does not implement; writing one makes it refuse the
     * entire layer. Revisit when bumping the maplibre-native-ffi pin.
     */
    private val UnsupportedLayerProperties: Map<Pair<String, String>, String> =
      mapOf(
        ("symbol" to "icon-overlap") to
          "MapLibre Native does not implement it. Use iconAllowOverlap instead; note that it " +
            "cannot express the 'cooperative' value.",
        ("symbol" to "text-overlap") to
          "MapLibre Native does not implement it. Use textAllowOverlap instead; note that it " +
            "cannot express the 'cooperative' value.",
        ("symbol" to "symbol-height-offset") to "MapLibre Native does not implement it.",
        ("symbol" to "symbol-height-anchor") to "MapLibre Native does not implement it.",
        ("fill" to "fill-layer-opacity") to "MapLibre Native does not implement it.",
        ("line" to "line-layer-opacity") to "MapLibre Native does not implement it.",
        ("hillshade" to "resampling") to "MapLibre Native does not implement it.",
        ("color-relief" to "resampling") to "MapLibre Native does not implement it.",
      )
  }
}

private fun JsonElement.requireRootString(layerId: String, name: String): String =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    ?: throw StyleMutationException("Layer '$layerId' property '$name' requires a string", null)

private fun JsonElement.requireRootNumber(layerId: String, name: String): Double =
  (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    ?: throw StyleMutationException("Layer '$layerId' property '$name' requires a number", null)

/** The same options that the definition writes into source JSON, as the typed adder takes them. */
private fun GeoJsonOptions.toFfiOptions(): GeoJsonSourceOptions =
  GeoJsonSourceOptions().also {
    it.minZoom = minZoom.toDouble()
    it.maxZoom = maxZoom.toDouble()
    it.tolerance = tolerance.toDouble()
    it.buffer = buffer
    it.cluster = cluster
    it.clusterRadius = clusterRadius
    it.clusterMaxZoom = clusterMaxZoom.toDouble()
    it.clusterMinPoints = clusterMinPoints
    it.lineMetrics = lineMetrics
    // Viewport tiles are sliced during the next render when true, or on a worker when false.
    it.synchronousTiling = synchronousTiling
    it.clusterProperties = clusterPropertiesBytes()
  }

private fun GeoJsonOptions.clusterPropertiesBytes(): ByteArray? {
  if (clusterProperties.isEmpty()) return null
  return buildJsonObject { putClusterProperties(clusterProperties) }.toJsonBytes()
}
