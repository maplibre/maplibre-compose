package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.MlnFfiFeatureStateStore
import org.maplibre.compose.sources.MlnFfiTileCoordinatorStore
import org.maplibre.compose.sources.MlnFfiTileRequestCoordinator
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.UnknownSource
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.featureStateSelector
import org.maplibre.compose.sources.forgetFeatureStates
import org.maplibre.compose.sources.liveFeatureStateStore
import org.maplibre.compose.sources.mutateLiveFeatureState
import org.maplibre.compose.sources.putClusterProperties
import org.maplibre.compose.sources.toInlineUtf8
import org.maplibre.compose.sources.toMlnFfiTileId
import org.maplibre.compose.sources.toStyleSpecEncoding
import org.maplibre.compose.sources.toStyleSpecType
import org.maplibre.compose.sources.toTileCoordinate
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toFfiClusterFeature
import org.maplibre.compose.util.toGeoJsonFeatures
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.compose.util.toJsonElement
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toPremultipliedRgba8
import org.maplibre.nativeffi.error.MaplibreException
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

/**
 * [StyleBinding] for one loaded style in a MapLibre Native map. The supplied access functions
 * marshal every engine call to the map's owner thread.
 */
internal open class MlnFfiStyleBinding(
  override val identity: StyleIdentity = StyleIdentity.create(),
  private val loggerProvider: () -> MapLog? = { null },
  private val sessionOpen: () -> Boolean = { false },
  private val accessMap: ((MapHandle) -> Unit) -> Boolean = { false },
  private val accessRenderSession: ((RenderSessionHandle) -> Unit) -> Boolean = { false },
  private val sourceChanged: (String) -> Unit = {},
  private val sourceDataFailed: (StyleIdentity, String, Throwable) -> Unit = { _, _, _ -> },
  private val getScale: () -> Float = { 1f },
  private val requestRepaint: (MapHandle) -> Unit = MapHandle::requestRepaint,
) : StyleBinding {
  @Volatile private var loaded = true
  private val unloadActions = mutableSetOf<() -> Unit>()
  private val unloadActionsLock = MlnFfiLock()
  private val geoJsonCoordinators =
    mutableMapOf<String, MlnFfiGeoJsonCoordinator<GeoJsonSourceDataHandle>>()
  private val geoJsonLock = MlnFfiLock()

  /** Feature state retained for this loaded style. */
  open val featureStateStore: MlnFfiFeatureStateStore? = MlnFfiFeatureStateStore()

  /** The tile coordinators serving this loaded style's custom sources; null when unloaded. */
  open val tileCoordinators: MlnFfiTileCoordinatorStore? = MlnFfiTileCoordinatorStore()

  override val isLoaded: Boolean
    get() = loaded && sessionOpen()

  override val animatorDurationScale: Float
    get() = systemAnimatorDurationScale()

  override val logger: MapLog?
    get() = loggerProvider()

  override fun addImage(definition: StyleImageDefinition) {
    val (id, snapshot, sdf, stretch) = definition
    val image = snapshot.toImageBitmap()
    val scale = getScale()
    val pixels = image.toPremultipliedRgba8()
    val stretchPx = stretch?.resolve(image.width, image.height, scale)
    mutateMap { map ->
      try {
        map.setStyleImage(
          imageId = id,
          image = pixels,
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
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  internal fun imageStretches(id: String): Pair<List<FfiImageStretch>, List<FfiImageStretch>>? =
    readMap {
      it.styleImageStretches(id)
    }

  override fun removeImage(id: String) {
    mutateMap {
      try {
        it.removeStyleImage(id)
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  override fun imageExists(id: String): Boolean? = readMap { it.styleImageInfo(id) != null }

  override fun getSource(id: String): Source? = readMap { map ->
    if (!isStyleSource(map, id)) null else reconstructSource(map, id)
  }

  override fun getSources(): List<Source> = readMap { map ->
    map.styleSourceIds().filter { isStyleSource(map, it) }.map { reconstructSource(map, it) }
  }
    .orEmpty()

  override fun getLayer(id: String): Layer? = readMap { map ->
    if (!map.styleLayerExists(id)) null else reconstructLayer(map, id)
  }

  override fun getLayers(): List<Layer> = readMap { map ->
    map.styleLayerIds().map { reconstructLayer(map, it) }
  }
    .orEmpty()

  override fun layerIds(): List<String> = readMap { it.styleLayerIds() }.orEmpty()

  private fun isStyleSource(map: MapHandle, id: String): Boolean =
    map.styleSourceExists(id) && map.styleSourceType(id) != SourceType.ANNOTATIONS

  private fun reconstructSource(map: MapHandle, id: String): Source =
    UnknownSource(id, sourceDefinition(map, id))

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

  private var declaredSources: JsonObject? = null

  private fun reconstructLayer(map: MapHandle, id: String): Layer {
    val definition =
      (map.styleLayerJson(id)?.toJsonElement() as? JsonObject)
        ?: buildJsonObject { map.styleLayerType(id)?.let { put("type", it) } }
    return UnknownLayer(id, definition)
  }

  override fun invalidate() {
    if (!loaded) return
    loaded = false
    val coordinators = geoJsonLock.withLock {
      geoJsonCoordinators.values.toList().also { geoJsonCoordinators.clear() }
    }
    coordinators.forEach { it.close() }
    val actions = unloadActionsLock.withLock {
      unloadActions.toList().also { unloadActions.clear() }
    }
    actions.forEach { it() }
  }

  private fun onUnload(action: () -> Unit): () -> Unit {
    if (!isLoaded) {
      action()
      return {}
    }
    var runImmediately = false
    unloadActionsLock.withLock {
      if (!isLoaded) runImmediately = true else unloadActions += action
    }
    if (runImmediately) {
      action()
      return {}
    }
    return { unloadActionsLock.withLock { unloadActions -= action } }
  }

  override fun reportSourceChanged(sourceId: String) {
    sourceChanged(sourceId)
  }

  private fun requireLoadedStyle() {
    check(isLoaded) { "Style operation belongs to a stale loaded-style identity" }
  }

  /** Returns null if owner access ends before [action] can run. */
  open fun <T> readMap(action: (MapHandle) -> T): T? {
    requireLoadedStyle()
    var result: Result<T>? = null
    if (!accessMap { map -> result = runCatching { action(map) } }) return null
    return checkNotNull(result).getOrThrow()
  }

  /** Requests a repaint after native accepts the mutation. */
  fun <T> mutateMap(action: (MapHandle) -> T): T? = mutateMap({}, action)

  /**
   * Requests a repaint after native accepts the mutation.
   *
   * Returns after [action] has run or been dropped. [abandon] runs when [action] will not run.
   */
  open fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T? {
    requireLoadedStyle()
    var result: Result<T>? = null
    if (
      !accessMap { map ->
        result = runCatching { action(map).also { requestRepaint(map) } }
      }
    ) {
      abandon()
      return null
    }
    return checkNotNull(result).getOrThrow()
  }

  /**
   * Returns null when no renderer is ready or owner access ends before [action] can run. The
   * renderer exists after the first successful frame and until teardown. The handle must not escape
   * [action].
   */
  open fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? {
    requireLoadedStyle()
    var result: Result<T>? = null
    if (!accessRenderSession { session -> result = runCatching { action(session) } }) return null
    return checkNotNull(result).getOrThrow()
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
   * rather than from source JSON. Reports the change and wraps a refusal the way [addSource] does.
   *
   * @return false if the style has unloaded, in which case [add] did not run.
   */
  fun addSourceWith(sourceId: String, add: (MapHandle) -> Unit): Boolean =
    mutateMap { map ->
      try {
        add(map)
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
      reportSourceChanged(sourceId)
    } != null

  override fun removeSource(sourceId: String) {
    mutateMap { map ->
      try {
        map.removeStyleSource(sourceId)
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
      geoJsonLock.withLock { geoJsonCoordinators.remove(sourceId) }?.close()
      forgetFeatureStates(sourceId)
      reportSourceChanged(sourceId)
    }
    tileCoordinators?.remove(sourceId)
  }

  override fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean {
    val coordinator =
      MlnFfiTileRequestCoordinator(
        name = "maplibre-custom-geometry-$sourceId",
        load = { tile -> provider.loadTile(tile).toJson().encodeToByteArray() },
        deliver = { map, tile, data -> map.setCustomGeometrySourceTileData(sourceId, tile, data) },
        fail = { map, tile, error ->
          logger?.e(error) {
            "Loading tile ${tile.toTileCoordinate()} of source '$sourceId' failed"
          }
          map.setCustomGeometrySourceTileData(sourceId, tile, EMPTY_FEATURE_COLLECTION)
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
    mutateMap { map -> map.invalidateCustomGeometrySourceRegion(sourceId, bounds.toLatLngBounds()) }
  }

  override fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate) {
    mutateMap { map -> map.invalidateCustomGeometrySourceTile(sourceId, tile.toMlnFfiTileId()) }
  }

  override fun addCustomVectorSource(
    sourceId: String,
    options: CustomVectorSourceOptions,
    provider: VectorTileProvider,
  ): Boolean {
    val coordinator =
      MlnFfiTileRequestCoordinator(
        name = "maplibre-custom-vector-$sourceId",
        load = provider::loadTile,
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
    mutateMap { map -> map.invalidateCustomMvtVectorSourceTile(sourceId, tile.toMlnFfiTileId()) }
  }

  /**
   * Attaches [coordinator] before [add] runs, so a fetch fired during the add is not dropped. The
   * store detaches it again on remove, on unload, and when the add fails.
   */
  private fun installCoordinator(
    sourceId: String,
    coordinator: MlnFfiTileRequestCoordinator<*>,
    add: (MapHandle) -> Unit,
  ): Boolean {
    val store = tileCoordinators ?: return false
    coordinator.attach(this)
    store.put(sourceId, coordinator, onUnload { tileCoordinators?.remove(sourceId) })
    val added =
      try {
        addSourceWith(sourceId, add)
      } catch (error: Throwable) {
        store.remove(sourceId)
        throw error
      }
    if (!added) store.remove(sourceId)
    return added
  }

  override fun sourceExists(sourceId: String): Boolean? = readMap { map ->
    map.styleSourceExists(sourceId)
  }

  /** The bitmap is converted on the caller so the owner-thread hop only uploads. */
  override fun addImageSourceImage(
    sourceId: String,
    coordinates: List<Position>,
    image: ImageBitmap,
  ): Boolean {
    val pixels = image.toPremultipliedRgba8()
    val corners = coordinates.map { it.toLatLng() }
    return addSourceWith(sourceId) { map -> map.addImageSourceImage(sourceId, corners, pixels) }
  }

  override fun setImageSourceImage(sourceId: String, image: ImageBitmap) {
    val pixels = image.toPremultipliedRgba8()
    mutateMap { map -> map.setImageSourceImage(sourceId, pixels) }
  }

  override fun setImageSourceUrl(sourceId: String, url: String) {
    mutateMap { map -> map.setImageSourceUrl(sourceId, url) }
  }

  override fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>) {
    val corners = coordinates.map { it.toLatLng() }
    mutateMap { map -> map.setImageSourceCoordinates(sourceId, corners) }
  }

  override fun imageSourceCoordinates(sourceId: String): List<Position>? = readMap { map ->
    map.imageSourceCoordinates(sourceId)?.map { it.toPosition() }
  }

  /** Adds an empty inline source immediately so layers can reference it while data prepares. */
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
        GeoJsonSourceDataHandle.create(EMPTY_FEATURE_COLLECTION, ffiOptions).use { empty ->
          map.addGeoJsonSourceData(sourceId, empty)
        }
      }
      val coordinator = geoJsonCoordinator(sourceId, ffiOptions)
      // Register initial data before notifying source observers, which can submit newer data.
      if (data !is GeoJsonData.Uri) coordinator.submit(data) { error("Expected inline data") }
    }
  }

  override fun submitGeoJsonData(
    sourceId: String,
    data: GeoJsonData,
    fallbackOptions: GeoJsonOptions,
  ) {
    mutateMap { map ->
      requireLoadedStyle()
      val coordinator =
        geoJsonLock.withLock { geoJsonCoordinators[sourceId] }
          ?: geoJsonCoordinator(
            sourceId,
            loadedGeoJsonOptions(map, sourceId) ?: fallbackOptions.toFfiOptions(),
          )
      try {
        coordinator.submit(data) { url -> map.setGeoJsonSourceUrl(sourceId, url) }
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  /** Called on the owner thread. Each replacement gets a new coordinator and fixed options. */
  private fun geoJsonCoordinator(
    sourceId: String,
    options: GeoJsonSourceOptions,
  ): MlnFfiGeoJsonCoordinator<GeoJsonSourceDataHandle> {
    val coordinator =
      MlnFfiGeoJsonCoordinator(
        prepare = { data ->
          GeoJsonSourceDataHandle.create(checkNotNull(data.toInlineUtf8()), options)
        },
        install = { prepared, isCurrent ->
          accessMap { map ->
            if (isLoaded && isCurrent()) {
              map.setGeoJsonSourceData(sourceId, prepared)
              requestRepaint(map)
            }
          }
        },
        reportFailure = { error, isCurrent ->
          accessMap {
            if (isLoaded && isCurrent()) {
              logger?.w(error) { "Could not update GeoJSON source '$sourceId'" }
              sourceDataFailed(identity, sourceId, error)
            }
          }
        },
      )
    geoJsonLock
      .withLock {
        if (!isLoaded) {
          coordinator.close()
          error("Style operation belongs to a stale loaded-style identity")
        }
        geoJsonCoordinators.put(sourceId, coordinator)
      }
      ?.close()
    return coordinator
  }

  /** Native still-image requests must include data submitted by the desired revision. */
  internal suspend fun awaitGeoJsonUpdates() {
    val coordinators = geoJsonLock.withLock { geoJsonCoordinators.values.toList() }
    coordinators.forEach { it.awaitLatest() }
    requireLoadedStyle()
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
      options.synchronousTiling = defaults.synchronousUpdate
      options.clusterProperties = (source["clusterProperties"] as? JsonObject)?.toJsonBytes()
    }
  }

  override suspend fun clusterExpansionZoom(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): Double? {
    val result = queryClusterExtension(sourceId, feature, EXPANSION_ZOOM_FIELD) ?: return null
    val zoom = result.decodeToString().toDoubleOrNull()
    if (zoom == null) reportClusterMiss(sourceId, EXPANSION_ZOOM_FIELD, result)
    return zoom
  }

  override suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>? =
    queryClusterFeatures(sourceId, feature, CHILDREN_FIELD, null)

  override suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>? =
    queryClusterFeatures(
      sourceId,
      feature,
      LEAVES_FIELD,
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
   * no cluster id, when no render session is attached yet, or when the query failed.
   */
  private fun queryClusterExtension(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray? = null,
  ): ByteArray? {
    val ffiFeature = feature.toFfiClusterFeature() ?: return null
    return withRenderSession { session ->
      session.queryFeatureExtension(sourceId, ffiFeature, SUPERCLUSTER_EXTENSION, field, arguments)
    }
  }

  private fun queryClusterFeatures(
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

  /**
   * Reports a lookup that found no cluster. MapLibre answers a successful query with a feature
   * collection, even an empty one, and a failed one with a null value.
   */
  private fun reportClusterMiss(sourceId: String, field: String, result: ByteArray) {
    logger?.w {
      "Cluster '$field' query matched no cluster in source '$sourceId'; the feature's cluster_id " +
        "is probably stale. MapLibre answered with ${result.decodeToString()}."
    }
  }

  override fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ) {
    val store = liveFeatureStateStore() ?: return
    store.set(sourceId, sourceLayerId, featureId, state)
    mutateLiveFeatureState { session ->
      session.setFeatureState(
        featureStateSelector(sourceId, sourceLayerId, featureId),
        state.toJsonBytes(),
      )
    }
  }

  override fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject =
    liveFeatureStateStore()?.get(sourceId, sourceLayerId, featureId) ?: JsonObject(emptyMap())

  override fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    val store = liveFeatureStateStore() ?: return
    store.remove(sourceId, sourceLayerId, featureId, stateKey)
    mutateLiveFeatureState { session ->
      session.removeFeatureState(featureStateSelector(sourceId, sourceLayerId, featureId, stateKey))
    }
  }

  override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    val store = liveFeatureStateStore() ?: return
    store.reset(sourceId, sourceLayerId)
    mutateLiveFeatureState { session ->
      session.removeFeatureState(featureStateSelector(sourceId, sourceLayerId))
    }
  }

  /** Empty rather than an exception when no render session is attached. */
  override fun querySourceFeatures(
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
    return withRenderSession { session -> session.querySourceFeatures(sourceId, options) }
      ?.toGeoJsonFeatures()
      .orEmpty()
  }

  override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean =
    mutateMap { map ->
      try {
        map.addStyleLayerJson(layer.toJsonBytes(), beforeLayerId)
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    } != null

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
      try {
        when {
          kind != LayerPropertyKind.ROOT -> map.setLayerProperty(layerId, name, value.toJsonBytes())
          name == "source" -> map.setLayerSourceId(layerId, value.requireRootString(layerId, name))
          name == "source-layer" ->
            map.setLayerSourceLayer(layerId, value.requireRootString(layerId, name))
          name == "minzoom" -> map.setLayerMinZoom(layerId, value.requireRootNumber(layerId, name))
          name == "maxzoom" -> map.setLayerMaxZoom(layerId, value.requireRootNumber(layerId, name))
          else -> map.setLayerProperty(layerId, name, value.toJsonBytes())
        }
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    mutateMap { map ->
      try {
        map.setLayerFilter(layerId, filter.toJsonBytes())
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  override fun layerProperty(layerId: String, name: String): JsonElement? = readMap { map ->
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
  override fun transition(): TransitionOptions? = readMap { map ->
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

  override fun placementTransitions(): Boolean? = readMap { map ->
    map.styleTransitionOptions().enablePlacementTransitions ?: true
  }

  override fun setPlacementTransitions(enabled: Boolean) {
    mutateMap { map ->
      map.setStyleTransitionOptions(
        map.styleTransitionOptions().copy { enablePlacementTransitions = enabled }
      )
    }
  }

  override fun lightProperty(name: String): JsonElement? = readMap { map ->
    map.styleLightProperty(name)?.toJsonElement()
  }

  override fun setLight(light: JsonObject) {
    mutateMap { map ->
      try {
        map.setStyleLightJson(light.toJsonBytes())
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  override val supportsSky: Boolean = false

  override fun skyProperty(name: String): JsonElement? {
    requireLoadedStyle()
    return null
  }

  override fun setSky(sky: JsonObject?) {
    requireLoadedStyle()
    if (sky != null) logger?.w { "MapLibre Native does not support the sky" }
  }

  override val supportsProjection: Boolean = false

  override fun projectionProperty(name: String): JsonElement? {
    requireLoadedStyle()
    return null
  }

  override fun setProjection(projection: JsonObject) {
    requireLoadedStyle()
    if (projection["type"] != JsonPrimitive("mercator")) {
      logger?.w { "MapLibre Native supports only the Mercator projection" }
    }
  }

  override fun layerExists(layerId: String): Boolean? = readMap { map ->
    map.styleLayerIds().contains(layerId)
  }

  // A property's transition travels the same write path, and native refuses it as hard as the
  // property itself.
  override fun unsupportedLayerPropertyReason(layerType: String, name: String): String? =
    UNSUPPORTED_LAYER_PROPERTIES[layerType to name.removeSuffix(TRANSITION_SUFFIX)]

  companion object {
    /** The only extension MapLibre answers for a GeoJSON source; anything else returns nothing. */
    private const val SUPERCLUSTER_EXTENSION = "supercluster"

    /** Delivered for a tile whose provider failed, so the map's load can finish. */
    private val EMPTY_FEATURE_COLLECTION =
      """{"type":"FeatureCollection","features":[]}""".encodeToByteArray()

    private const val EXPANSION_ZOOM_FIELD = "expansion-zoom"
    private const val CHILDREN_FIELD = "children"
    private const val LEAVES_FIELD = "leaves"

    /**
     * Style-spec properties MapLibre Native does not implement; writing one makes it refuse the
     * entire layer. Revisit when bumping the maplibre-native-ffi pin.
     */
    private val UNSUPPORTED_LAYER_PROPERTIES: Map<Pair<String, String>, String> =
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
    it.synchronousTiling = synchronousUpdate
    it.clusterProperties = clusterPropertiesBytes()
  }

private fun GeoJsonOptions.clusterPropertiesBytes(): ByteArray? {
  if (clusterProperties.isEmpty()) return null
  return buildJsonObject { putClusterProperties(clusterProperties) }.toJsonBytes()
}
