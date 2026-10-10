package org.maplibre.compose.style

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.maplibre.compose.mlnffi.CommandFailedException
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.awaitBlocking
import org.maplibre.compose.mlnffi.awaitCommittedBlocking
import org.maplibre.compose.mlnffi.failed
import org.maplibre.compose.mlnffi.failureMessage
import org.maplibre.compose.mlnffi.notFound
import org.maplibre.compose.mlnffi.reportFailure
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
import org.maplibre.nativeffi.error.MaplibreStatus
import org.maplibre.nativeffi.generated.CustomGeometrySourceOptions as FfiCustomGeometrySourceOptions
import org.maplibre.nativeffi.generated.CustomMvtVectorSourceOptions
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.GeojsonSourceDataHandle
import org.maplibre.nativeffi.generated.GeojsonSourceOptions
import org.maplibre.nativeffi.generated.ImageContent
import org.maplibre.nativeffi.generated.ImageStretch as FfiImageStretch
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.RenderSessionHandle
import org.maplibre.nativeffi.generated.SourceFeatureQueryOptions
import org.maplibre.nativeffi.generated.StyleImageOptions
import org.maplibre.nativeffi.generated.StyleSourceInfo
import org.maplibre.nativeffi.generated.StyleSourceTileInfo
import org.maplibre.nativeffi.generated.StyleSourceType
import org.maplibre.nativeffi.generated.StyleTileScheme
import org.maplibre.nativeffi.runtime.CommandCompletion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

/** Reaches the render session of a map, for feature queries from any thread. */
internal interface MlnFfiRenderSessions {
  /** The render session once it has drawn a frame, or null while there is none. */
  fun readySession(): RenderSessionHandle?

  object None : MlnFfiRenderSessions {
    override fun readySession(): RenderSessionHandle? = null
  }
}

/**
 * Where one map's style visits run, and the lock that orders style commands against a base-style
 * replacement.
 *
 * [StyleBinding] reads and writes synchronously inside a visit. Map commands and queries are
 * asynchronous, so a visit runs on [dispatcher], which serves one visit at a time in submission
 * order, and waits on that thread for the native results it needs.
 *
 * A command for a loaded style is checked and submitted under [lock], and so is the base-style
 * replacement that unloads it. Native runs submissions in order, so a command that passed the check
 * reaches the style it was written for.
 */
internal class MlnFfiStyleVisits {
  @OptIn(ExperimentalCoroutinesApi::class)
  val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
  val lock = MlnFfiLock()
  val scope = CoroutineScope(SupervisorJob() + dispatcher)
}

/** What a loaded style held when it finished loading, read once for its binding. */
internal class LoadedStyleContents(
  val layers: List<LayerSummary>,
  val sources: List<StyleSourceInfo>,
  val document: JsonObject?,
) {
  companion object {
    /** Reads [map]'s loaded style. Queries run after every command submitted before this call. */
    suspend fun read(map: MapHandle): LoadedStyleContents {
      val layers = map.listStyleLayers()
      val sources = map.listStyleSources()
      val document = map.getLoadedStyleJson()
      return LoadedStyleContents(
        layers = layers.await().map { LayerSummary(it.id, it.type, it.sourceId, it.sourceLayer) },
        sources = sources.await(),
        document = runCatching { document.await().toJsonElement() as? JsonObject }.getOrNull(),
      )
    }
  }
}

/**
 * [StyleBinding] for one loaded style in a MapLibre Native map.
 *
 * Reads and mutations run inside a visit on [visits], which waits for each native result it needs.
 * Layer property batches are submitted without waiting, and their rejections are reported when they
 * arrive. Feature queries go to the render session from any thread, and data preparation stays on
 * workers.
 */
internal open class MlnFfiStyleBinding(
  private val map: MapHandle,
  /** Where this binding's visits run. Internal for tests. */
  internal val visits: MlnFfiStyleVisits,
  contents: LoadedStyleContents,
  override val identity: StyleIdentity = StyleIdentity.create(),
  private val loggerProvider: () -> MapLog? = { null },
  private val sessionOpen: () -> Boolean = { false },
  private val renderSessions: MlnFfiRenderSessions = MlnFfiRenderSessions.None,
  private val sourceChanged: (String) -> Unit = {},
  private val sourceDataFailed: (StyleIdentity, String, Throwable) -> Unit = { _, _, _ -> },
  private val getScale: () -> Float = { 1f },
  /** Receives the exception of a failed custom source provider call. */
  private val customTileFailed: (Throwable) -> Unit = {},
) : StyleBinding {
  @Volatile private var loaded = true
  private val geoJsonCoordinators =
    mutableMapOf<String, MlnFfiGeoJsonCoordinator<GeojsonSourceDataHandle>>()
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

  private val declaredSources: JsonObject =
    contents.document?.get("sources") as? JsonObject ?: JsonObject(emptyMap())

  override val baseLayers: List<LayerSummary> = contents.layers

  override val baseSources: Map<String, Source> =
    contents.sources.associate { it.id to reconstructedSource(it.id, sourceDefinition(it)) }

  /** Runs [action] in a visit on [visits], and suspends until it has run. */
  override suspend fun <T> awaitOwner(action: () -> T): T? {
    if (!isLoaded) return null
    return withContext(visits.dispatcher) {
      if (!isLoaded) return@withContext null
      try {
        action()
      } catch (error: Exception) {
        // A style unloaded during this visit has nothing left to read or update.
        if (isLoaded) throw error
        null
      }
    }
  }

  override fun postOwner(onDropped: () -> Unit, action: () -> Unit) {
    if (!isLoaded) return onDropped()
    visits.scope.launch {
      if (!isLoaded) return@launch onDropped()
      try {
        action()
      } catch (error: Exception) {
        // A style unloaded during this accepted visit has nothing left to update.
        if (isLoaded) throw error
        onDropped()
      }
    }
  }

  /**
   * Submits [command] if this style is still loaded, and returns at once. [onDropped] runs instead
   * when the style has unloaded, and after [command] when it throws. Callable from any thread.
   */
  open fun submit(onDropped: () -> Unit = {}, command: (MapHandle) -> Unit) {
    val submitted =
      try {
        visits.lock.withLock {
          if (!isLoaded) false
          else {
            command(map)
            true
          }
        }
      } catch (error: Throwable) {
        onDropped()
        throw error
      }
    if (!submitted) onDropped()
  }

  /**
   * Submits a command for this loaded style and returns its completion. Throws
   * [IllegalStateException] when the style has unloaded, and [StyleMutationException] when native
   * rejects the submission.
   */
  private fun <T> command(start: (MapHandle) -> Deferred<T>): Deferred<T> =
    visits.lock.withLock {
      requireCurrent()
      try {
        start(map)
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }

  /** Submits a command and waits for it, reporting a refusal as a [StyleMutationException]. */
  private fun mutate(start: (MapHandle) -> Deferred<CommandCompletion>): CommandCompletion {
    val completion = command(start)
    return try {
      completion.awaitCommittedBlocking()
    } catch (error: CommandFailedException) {
      throw StyleMutationException(error.message, error)
    } catch (error: MaplibreException) {
      throw StyleMutationException(error.message, error)
    }
  }

  /** Runs one ordered query for this loaded style and waits for its result. */
  private fun <T> read(start: (MapHandle) -> Deferred<T>): T {
    requireCurrent()
    return start(map).awaitBlocking()
  }

  override fun setImage(definition: StyleImageDefinition) {
    val (id, image, sdf, stretch) = definition
    val scale = getScale()
    val stretchPx = stretch?.resolve(image.width, image.height, scale)
    val options =
      StyleImageOptions(
        sdf = sdf,
        pixelRatio = scale,
        stretchX =
          stretchPx
            ?.stretchX
            ?.takeIf { it.isNotEmpty() }
            ?.map { (start, end) ->
              FfiImageStretch(start, end)
            },
        stretchY =
          stretchPx
            ?.stretchY
            ?.takeIf { it.isNotEmpty() }
            ?.map { (start, end) ->
              FfiImageStretch(start, end)
            },
        content = stretchPx?.content?.let { ImageContent(it.left, it.top, it.right, it.bottom) },
      )
    // The engine replaces an existing image in place, so no existence read is needed.
    mutate { it.setStyleImage(id, image.pixels.ffi, options) }
  }

  override fun removeImage(id: String): Boolean {
    val completion = command { it.removeStyleImage(id) }.awaitBlocking()
    if (completion.notFound) return false
    if (completion.failed) throw StyleMutationException(completion.failureMessage(), null)
    return true
  }

  override fun imageExists(id: String): Boolean? = read { it.getStyleImage(id) } != null

  override fun getSource(id: String): Source? = read {
    it.getStyleSource(id)
  }
    ?.let { reconstructedSource(id, sourceDefinition(it)) }

  override fun sourceIds(): List<String> = read { it.listStyleSources() }.map { it.id }

  override fun getLayer(id: String): LayerDefinition? {
    val json = read { it.getStyleLayerJson(id) } ?: return null
    val definition =
      json.toJsonElement() as? JsonObject
        ?: buildJsonObject { read { it.getStyleLayer(id) }?.type?.let { put("type", it) } }
    return layerDefinitionFromJson(id, definition)
  }

  /** The full engine order: insertions and moves are relative to it. */
  override fun layerIds(): List<String> = read { it.listStyleLayers() }.map { it.id }

  override fun layerSummaries(): List<LayerSummary> = read {
    it.listStyleLayers()
  }
    .map { LayerSummary(it.id, it.type, it.sourceId, it.sourceLayer) }

  private fun sourceDefinition(info: StyleSourceInfo): JsonObject = buildJsonObject {
    info.type.toStyleSpecType()?.let { put("type", it) }
    val attribution = info.attribution?.takeIf { it.isNotEmpty() } ?: declaredAttribution(info.id)
    attribution?.let { put("attribution", it) }
    info.tileSize?.takeIf { it > 0u }?.let { put("tileSize", it.toInt()) }
    if (info.isVolatile) put("volatile", true)
    if (info.type == StyleSourceType.VECTOR)
      info.vectorEncoding?.toStyleSpecEncoding()?.let { put("encoding", it) }
    if (info.type == StyleSourceType.RASTER_DEM)
      info.rasterEncoding?.toStyleSpecEncoding()?.let { put("encoding", it) }
    val url = info.url?.takeIf { it.isNotEmpty() }
    if (url != null) put("url", url) else info.tilejson?.let { putTileJson(it, info) }
  }

  private fun JsonObjectBuilder.putTileJson(tileJson: StyleSourceTileInfo, info: StyleSourceInfo) {
    if (tileJson.tileUrls.isNotEmpty())
      putJsonArray("tiles") { tileJson.tileUrls.forEach { add(it) } }
    put("minzoom", tileJson.minZoom)
    put("maxzoom", tileJson.maxZoom)
    when (tileJson.scheme) {
      StyleTileScheme.XYZ -> put("scheme", "xyz")
      StyleTileScheme.TMS -> put("scheme", "tms")
      else -> Unit
    }
    info.bounds?.toBoundingBox()?.let { box ->
      putJsonArray("bounds") {
        add(box.west)
        add(box.south)
        add(box.east)
        add(box.north)
      }
    }
  }

  private fun declaredAttribution(id: String): String? =
    ((declaredSources[id] as? JsonObject)?.get("attribution") as? JsonPrimitive)?.contentOrNull

  override fun invalidate() {
    if (!loaded) return
    visits.lock.withLock { loaded = false }
    val coordinators = geoJsonLock.withLock {
      geoJsonCoordinators.values.toList().also { geoJsonCoordinators.clear() }
    }
    coordinators.forEach { it.close() }
    val tiles = tileLock.withLock {
      tileCoordinators.values.toList().also { tileCoordinators.clear() }
    }
    tiles.forEach { it.close() }
  }

  fun setSourceVolatile(sourceId: String, value: Boolean) {
    mutate { it.setStyleSourceVolatile(sourceId, value) }
  }

  /** Whether the source skips persistent storage writes, or null when it does not exist. */
  fun sourceVolatile(sourceId: String): Boolean? = read { it.getStyleSource(sourceId) }?.isVolatile

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
   * Adds a source, for the types MapLibre Native creates from a typed adder rather than from source
   * JSON. Wraps a refusal the way [addSource] does.
   */
  fun addSourceWith(sourceId: String, add: (MapHandle) -> Deferred<CommandCompletion>): Boolean {
    mutate(add)
    sourceChanged(sourceId)
    return true
  }

  override fun removeSource(sourceId: String) {
    mutate { it.removeStyleSource(sourceId) }
    identity.sources.remove(sourceId)
    geoJsonLock.withLock { geoJsonCoordinators.remove(sourceId) }?.close()
    sourceChanged(sourceId)
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
          customTileFailed(error)
          map.setCustomGeometrySourceTileData(sourceId, tile, EmptyFeatureCollection)
        },
      )
    return installCoordinator(sourceId, coordinator) { map ->
      map.addCustomGeometrySource(
        sourceId,
        FfiCustomGeometrySourceOptions(
          fetchTile = coordinator::fetch,
          cancelTile = coordinator::cancel,
          minZoom = options.minZoom.toDouble(),
          maxZoom = options.maxZoom.toDouble(),
          buffer = options.buffer.toUInt(),
          tolerance = options.tolerance.toDouble(),
          clip = options.clip,
          wrap = options.wrap,
        ),
      )
    }
  }

  override fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox) {
    mutate { it.invalidateCustomGeometrySourceRegion(sourceId, bounds.toLatLngBounds()) }
  }

  override fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate) {
    mutate { it.invalidateCustomGeometrySourceTile(sourceId, tile.toMlnFfiTileId()) }
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
          customTileFailed(error)
          map.setCustomMvtVectorSourceTileError(
            sourceId,
            tile,
            error.message ?: "Tile loading failed",
          )
        },
      )
    return installCoordinator(sourceId, coordinator) { map ->
      map.addCustomMvtVectorSource(
        sourceId,
        CustomMvtVectorSourceOptions(
          fetchTile = coordinator::fetch,
          cancelTile = coordinator::cancel,
          minZoom = options.minZoom.toDouble(),
          maxZoom = options.maxZoom.toDouble(),
        ),
      )
    }
  }

  override fun invalidateCustomVectorSourceTile(sourceId: String, tile: TileCoordinate) {
    mutate { it.invalidateCustomMvtVectorSourceTile(sourceId, tile.toMlnFfiTileId()) }
  }

  /**
   * Stores [coordinator] before [add] runs, so a fetch fired during the add is answered. The
   * coordinator closes on remove, on unload, when the add fails, and at once on an unloaded style.
   */
  private fun installCoordinator(
    sourceId: String,
    coordinator: MlnFfiTileRequestCoordinator<*>,
    add: (MapHandle) -> Deferred<CommandCompletion>,
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

  override fun sourceExists(sourceId: String): Boolean? =
    read { it.getStyleSource(sourceId) } != null

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
    mutate { it.setImageSourceImage(sourceId, image.pixels.ffi) }
  }

  override fun setImageSourceUrl(sourceId: String, url: String) {
    mutate { it.setImageSourceUrl(sourceId, url) }
  }

  override fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>) {
    val corners = coordinates.map { it.toLatLng() }
    mutate { it.setImageSourceCoordinates(sourceId, corners) }
  }

  override fun addGeoJsonSource(
    sourceId: String,
    data: GeoJsonData,
    options: GeoJsonOptions,
  ): Boolean {
    val ffiOptions = options.toFfiOptions()
    addSourceWith(sourceId) { map ->
      if (data is GeoJsonData.Uri) {
        map.addGeojsonSourceUrl(sourceId, data.uri, ffiOptions)
      } else {
        // Native leases the data at submission, so the handle closes as soon as this returns.
        GeneratedApi.geojsonSourceDataCreate(EmptyFeatureCollection, ffiOptions).use { empty ->
          map.addGeojsonSourceData(sourceId, empty)
        }
      }
    }
    val coordinator = geoJsonCoordinator(sourceId, ffiOptions)
    // Register initial data before notifying source observers, which can submit newer data.
    if (data !is GeoJsonData.Uri) {
      coordinator.submit(data) { error("Expected inline data") }
    }
    return true
  }

  override fun submitGeoJsonData(
    sourceId: String,
    data: GeoJsonData,
    fallbackOptions: GeoJsonOptions,
  ) {
    requireCurrent()
    val coordinator =
      geoJsonLock.withLock { geoJsonCoordinators[sourceId] }
        ?: geoJsonCoordinator(
          sourceId,
          loadedGeoJsonOptions(sourceId) ?: fallbackOptions.toFfiOptions(),
        )
    try {
      coordinator.submit(data) { url -> mutate { it.setGeojsonSourceUrl(sourceId, url) } }
    } catch (error: StyleMutationException) {
      sourceDataFailed(identity, sourceId, error)
      throw error
    }
  }

  private fun prepareGeoJson(
    data: GeoJsonData,
    options: GeojsonSourceOptions,
  ): GeojsonSourceDataHandle =
    try {
      GeneratedApi.geojsonSourceDataCreate(checkNotNull(data.toInlineUtf8()), options)
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      rethrowIfFatal(error)
      throw StyleMutationException(error.message, error)
    }

  /** Each replacement gets a new coordinator and fixed options. */
  private fun geoJsonCoordinator(
    sourceId: String,
    options: GeojsonSourceOptions,
  ): MlnFfiGeoJsonCoordinator<GeojsonSourceDataHandle> {
    val coordinator =
      MlnFfiGeoJsonCoordinator(
        prepare = { data -> prepareGeoJson(data, options) },
        // Native leases the prepared data at submission; the wait reports its outcome.
        install = install@{ prepared, isCurrent ->
            var installed: Deferred<CommandCompletion>? = null
            submit {
              if (isCurrent()) installed = it.setGeojsonSourceData(sourceId, prepared)
            }
            val completion = installed?.await() ?: return@install
            if (completion.failed) throw StyleMutationException(completion.failureMessage(), null)
          },
        reportFailure = { error, isCurrent ->
          if (isLoaded && isCurrent()) {
            logger?.w(error) { "Could not update GeoJSON source '$sourceId'" }
            sourceDataFailed(identity, sourceId, error)
          }
        },
      )
    geoJsonLock
      .withLock {
        if (!isLoaded) {
          coordinator.close()
          requireCurrent()
        }
        geoJsonCoordinators.put(sourceId, coordinator)
      }
      ?.close()
    return coordinator
  }

  /** Native still-image requests must include data submitted by the desired revision. */
  internal suspend fun awaitGeoJsonUpdates() {
    // The visit barrier includes accepted URL updates and newly created coordinators.
    val coordinators = awaitOwner { geoJsonLock.withLock { geoJsonCoordinators.values.toList() } }
    coordinators?.forEach { it.awaitLatest() }
    requireCurrent()
  }

  private fun loadedGeoJsonOptions(sourceId: String): GeojsonSourceOptions? {
    val document =
      runCatching { read { it.getLoadedStyleJson() }.toJsonElement() }.getOrNull() as? JsonObject
    val sources = document?.get("sources") as? JsonObject
    val source = sources?.get(sourceId) as? JsonObject ?: return null
    if ((source["type"] as? JsonPrimitive)?.content != "geojson") return null
    val defaults = GeoJsonOptions.Standard
    val minZoom = (source["minzoom"] as? JsonPrimitive)?.doubleOrNull ?: defaults.minZoom.toDouble()
    val maxZoom = (source["maxzoom"] as? JsonPrimitive)?.doubleOrNull ?: defaults.maxZoom.toDouble()
    return GeojsonSourceOptions(
      minZoom = minZoom,
      maxZoom = maxZoom,
      tolerance =
        (source["tolerance"] as? JsonPrimitive)?.doubleOrNull ?: defaults.tolerance.toDouble(),
      buffer = ((source["buffer"] as? JsonPrimitive)?.intOrNull ?: defaults.buffer).toUInt(),
      cluster = (source["cluster"] as? JsonPrimitive)?.booleanOrNull ?: defaults.cluster,
      clusterRadius =
        ((source["clusterRadius"] as? JsonPrimitive)?.intOrNull ?: defaults.clusterRadius).toUInt(),
      clusterMaxZoom = (source["clusterMaxZoom"] as? JsonPrimitive)?.doubleOrNull ?: maxZoom - 1.0,
      clusterMinPoints =
        ((source["clusterMinPoints"] as? JsonPrimitive)?.intOrNull ?: defaults.clusterMinPoints)
          .toUInt(),
      lineMetrics =
        (source["lineMetrics"] as? JsonPrimitive)?.booleanOrNull ?: defaults.lineMetrics,
      synchronousTiling = defaults.synchronousTiling,
      clusterProperties = (source["clusterProperties"] as? JsonObject)?.toJsonBytes(),
    )
  }

  override suspend fun clusterExpansionZoom(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): Double? {
    val result = queryClusterExtension(sourceId, feature, ExpansionZoomField) ?: return null
    val zoom = result.decodeToString().toDoubleOrNull()
    if (zoom == null) reportClusterMiss(sourceId, ExpansionZoomField, result)
    // A source that does not cluster answers 0. A cluster always expands at zoom 1 or higher.
    return zoom?.takeIf { it > 0.0 }
  }

  override suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>? =
    // A source that does not cluster answers an empty collection. A cluster always has children.
    queryClusterFeatures(sourceId, feature, ChildrenField, null)?.takeIf {
      it.features.isNotEmpty()
    }

  override suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Int,
    offset: Int,
  ): FeatureCollection<Geometry, JsonObject?>? {
    val leaves =
      queryClusterFeatures(
        sourceId,
        feature,
        LeavesField,
        // Both must be unsigned: MapLibre type-checks them exactly and silently falls back to its
        // own default of ten otherwise, and it ignores offset unless limit is present. A
        // non-negative integer literal parses as unsigned.
        // https://github.com/maplibre/maplibre-native-ffi/pull/340
        buildJsonObject {
          put("limit", limit)
          put("offset", offset)
        }
          .toJsonBytes(),
      ) ?: return null
    // A source that does not cluster also answers an empty page; only a cluster has children.
    if (leaves.features.isEmpty() && clusterChildren(sourceId, feature) == null) return null
    return leaves
  }

  /** The render session for a feature query, or null when none has drawn a frame yet. */
  private fun readySession(): RenderSessionHandle? {
    requireCurrent()
    return renderSessions.readySession().also {
      if (it == null) logger?.d { "Ignoring a render session call: no session is ready yet" }
    }
  }

  /**
   * Runs one supercluster query against the render session. Returns null when the feature carries
   * no cluster id, when no render session is attached yet, or when the engine reports no cluster.
   *
   * @throws StyleHandleException wrapping any other engine failure.
   */
  private suspend fun queryClusterExtension(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray? = null,
  ): ByteArray? {
    val ffiFeature = feature.toFfiClusterFeature() ?: return null
    val session = readySession() ?: return null
    return try {
      session
        .queryFeatureExtensions(sourceId, ffiFeature, SuperclusterExtension, field, arguments)
        .await()
    } catch (error: MaplibreException) {
      // Supercluster's missing-cluster error reaches the C API only as this diagnostic text.
      if (
        error.status == MaplibreStatus.NATIVE_ERROR && error.diagnostic == MissingClusterDiagnostic
      ) {
        logger?.w { "Cluster '$field' query matched no cluster in source '$sourceId'" }
        return null
      }
      // A session that detached during the query has nothing to answer with.
      if (
        error.status == MaplibreStatus.INVALID_STATE || error.status == MaplibreStatus.TARGET_LOST
      )
        return null
      throw StyleHandleException(
        "Cluster '$field' query failed in source '$sourceId': ${error.message}",
        error,
      )
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
      withContext(Dispatchers.Default) {
        FeatureCollection.fromJsonOrNull<Geometry, JsonObject?>(result.decodeToString())
      }
    if (collection == null) reportClusterMiss(sourceId, field, result)
    return collection
  }

  /** Reports a cluster query whose result does not contain the requested value. */
  private fun reportClusterMiss(sourceId: String, field: String, result: ByteArray) {
    logger?.w {
      "Cluster '$field' query in source '$sourceId' returned no result. MapLibre answered with " +
        "${result.decodeToString()}."
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
    return { mutate { it.setFeatureState(selector, bytes) } }
  }

  override fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject =
    Json.parseToJsonElement(
        read { it.getFeatureState(featureStateSelector(sourceId, sourceLayerId, featureId)) }
          .decodeToString()
      )
      .jsonObject

  override fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    mutate {
      it.removeFeatureState(featureStateSelector(sourceId, sourceLayerId, featureId, stateKey))
    }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    mutate { it.removeFeatureState(featureStateSelector(sourceId, sourceLayerId)) }
  }

  /** Empty rather than an exception when no render session is attached. */
  override suspend fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> {
    if (sourceLayerIds.isEmpty()) return emptyList()
    val options =
      SourceFeatureQueryOptions(
        sourceLayerIds = sourceLayerIds.toList(),
        filter = filter?.toJsonBytes(),
      )
    val session = readySession() ?: return emptyList()
    val result =
      try {
        session.querySourceFeatures(sourceId, options).await()
      } catch (error: MaplibreException) {
        // A session that detached during the query has nothing to answer with.
        if (error.status == MaplibreStatus.INVALID_STATE) return emptyList()
        throw error
      }
    return withContext(Dispatchers.Default) { result.toGeoJsonFeatures() }
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean {
    mutate { it.addStyleLayerJson(layer.toJsonBytes(), beforeLayerId.ifEmpty { null }) }
    return true
  }

  override fun removeLayer(layerId: String) {
    mutate { it.removeStyleLayer(layerId) }
  }

  override fun moveLayer(layerId: String, beforeLayerId: String) {
    mutate { it.moveStyleLayer(layerId, beforeLayerId.ifEmpty { null }) }
  }

  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    val start = layerPropertyCommand(layerId, name, value, kind)
    mutate(start)
  }

  /**
   * Submits every write without waiting between them, so a revision costs one round trip rather
   * than one per write. A rejection is reported when it arrives, which the batch contract allows.
   */
  override fun setLayerProperties(writes: List<LayerPropertyWrite>) {
    writes.forEach { write ->
      try {
        val start =
          if (write.kind == LayerPropertyKind.Root && write.name == "filter")
            { map: MapHandle -> map.setStyleLayerFilter(write.layerId, write.value.toJsonBytes()) }
          else layerPropertyCommand(write.layerId, write.name, write.value, write.kind)
        val completion = command(start)
        completion.invokeOnCompletion { error ->
          @OptIn(ExperimentalCoroutinesApi::class)
          val failure =
            when {
              error is CancellationException -> null
              error != null -> StyleMutationException(error.message, error)
              else ->
                completion
                  .getCompleted()
                  .takeIf { it.failed }
                  ?.let {
                    StyleMutationException(it.failureMessage(), null)
                  }
            }
          failure?.let { reportRejectedWrite(write, it) }
        }
      } catch (error: StyleMutationException) {
        reportRejectedWrite(write, error)
      }
    }
  }

  private fun layerPropertyCommand(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ): (MapHandle) -> Deferred<CommandCompletion> {
    if (kind == LayerPropertyKind.Root && name == "filter") {
      val filter = value.toJsonBytes()
      return { map -> map.setStyleLayerFilter(layerId, filter) }
    }
    if (kind == LayerPropertyKind.Root) {
      when (name) {
        "source" -> {
          val sourceId = value.requireRootString(layerId, name)
          return { map -> map.setStyleLayerSourceId(layerId, sourceId) }
        }
        "source-layer" -> {
          val sourceLayer = value.requireRootString(layerId, name)
          return { map -> map.setStyleLayerSourceLayer(layerId, sourceLayer) }
        }
        "minzoom" -> {
          val zoom = value.requireRootNumber(layerId, name)
          return { map -> map.setStyleLayerMinZoom(layerId, zoom) }
        }
        "maxzoom" -> {
          val zoom = value.requireRootNumber(layerId, name)
          return { map -> map.setStyleLayerMaxZoom(layerId, zoom) }
        }
      }
    }
    val bytes = value.toJsonBytes()
    return { map -> map.setStyleLayerProperty(layerId, name, bytes) }
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    mutate { it.setStyleLayerFilter(layerId, filter.toJsonBytes()) }
  }

  override fun layerProperty(layerId: String, name: String): JsonElement? =
    when (name) {
      "id" -> JsonPrimitive(layerId)
      "type" -> read { it.getStyleLayer(layerId) }?.type?.let(::JsonPrimitive)
      "source" ->
        read { it.getStyleLayer(layerId) }
          ?.sourceId
          ?.takeIf(String::isNotEmpty)
          ?.let(::JsonPrimitive)
      "source-layer" ->
        read { it.getStyleLayer(layerId) }
          ?.sourceLayer
          ?.takeIf(String::isNotEmpty)
          ?.let(::JsonPrimitive)
      "minzoom" ->
        read { it.getStyleLayer(layerId) }?.minZoom?.takeIf(Double::isFinite)?.let(::JsonPrimitive)
      "maxzoom" ->
        read { it.getStyleLayer(layerId) }?.maxZoom?.takeIf(Double::isFinite)?.let(::JsonPrimitive)
      "filter" -> read { it.getStyleLayerFilter(layerId) }?.toJsonElement()
      else -> read { it.getStyleLayerProperty(layerId, name) }?.toJsonElement()
    }

  /** An unset native duration applies paint changes instantly, so it reads as zero. */
  override fun transition(): TransitionOptions? {
    val options = read { it.getStyleTransitionOptions() }
    return TransitionOptions(
      duration = options.durationMs?.milliseconds ?: Duration.ZERO,
      delay = options.delayMs?.milliseconds ?: Duration.ZERO,
    )
  }

  /** Native replaces every field on write, so the placement flag is read back first. */
  override fun setTransition(options: TransitionOptions) {
    val current = read { it.getStyleTransitionOptions() }
    mutate {
      it.setStyleTransitionOptions(
        current.copy(
          durationMs = options.duration.toDouble(DurationUnit.MILLISECONDS),
          delayMs = options.delay.toDouble(DurationUnit.MILLISECONDS),
        )
      )
    }
  }

  override val supportsPlacementTransitions: Boolean = true

  override fun placementTransitions(): Boolean? =
    read { it.getStyleTransitionOptions() }.enablePlacementTransitions ?: true

  override fun setPlacementTransitions(enabled: Boolean) {
    val current = read { it.getStyleTransitionOptions() }
    mutate { it.setStyleTransitionOptions(current.copy(enablePlacementTransitions = enabled)) }
  }

  override fun globalState(): JsonObject? =
    Json.parseToJsonElement(read { it.getGlobalState() }.decodeToString()).jsonObject

  override fun setGlobalStateProperty(name: String, value: JsonElement) {
    val bytes = value.toJsonBytes()
    mutate { it.setGlobalStateProperty(name, bytes) }
  }

  override fun lightProperty(name: String): JsonElement? = read {
    it.getStyleLightProperty(name)
  }
    ?.toJsonElement()

  override fun setLight(light: JsonObject) {
    val bytes = light.toJsonBytes()
    mutate { it.setStyleLightJson(bytes) }
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

  override fun layerExists(layerId: String): Boolean? = read { it.getStyleLayer(layerId) } != null

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
    private const val MissingClusterDiagnostic = "No cluster with the specified id."

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
private fun GeoJsonOptions.toFfiOptions(): GeojsonSourceOptions =
  GeojsonSourceOptions(
    minZoom = minZoom.toDouble(),
    maxZoom = maxZoom.toDouble(),
    tolerance = tolerance.toDouble(),
    buffer = buffer.toUInt(),
    cluster = cluster,
    clusterRadius = clusterRadius.toUInt(),
    clusterMaxZoom = clusterMaxZoom.toDouble(),
    clusterMinPoints = clusterMinPoints.toUInt(),
    lineMetrics = lineMetrics,
    // Viewport tiles are sliced during the next render when true, or on a worker when false.
    synchronousTiling = synchronousTiling,
    clusterProperties = clusterPropertiesBytes(),
  )

private fun GeoJsonOptions.clusterPropertiesBytes(): ByteArray? {
  if (clusterProperties.isEmpty()) return null
  return buildJsonObject { putClusterProperties(clusterProperties) }.toJsonBytes()
}
