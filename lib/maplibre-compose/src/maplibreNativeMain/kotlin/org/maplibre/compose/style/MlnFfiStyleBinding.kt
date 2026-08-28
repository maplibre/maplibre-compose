package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.MlnFfiFeatureStateStore
import org.maplibre.compose.sources.MlnFfiTileCoordinatorStore
import org.maplibre.compose.sources.MlnFfiTileRequestCoordinator
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.featureStateSelector
import org.maplibre.compose.sources.forgetFeatureStates
import org.maplibre.compose.sources.liveFeatureStateStore
import org.maplibre.compose.sources.mutateLiveFeatureState
import org.maplibre.compose.sources.putClusterProperties
import org.maplibre.compose.sources.toInlineUtf8
import org.maplibre.compose.sources.toMlnFfiTileId
import org.maplibre.compose.sources.toTileCoordinate
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
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

/**
 * [StyleBinding] over a MapLibre Native map. Every `MapHandle` call has to run on the owner thread;
 * the session supplies that hop.
 */
internal interface MlnFfiStyleBinding : StyleBinding {
  /** Feature state retained for this loaded style. */
  val featureStateStore: MlnFfiFeatureStateStore?

  /** The tile coordinators serving this loaded style's custom sources; null when unloaded. */
  val tileCoordinators: MlnFfiTileCoordinatorStore?

  /** Null if the style has unloaded; reads should then fall back to the descriptor. */
  fun <T> readMap(action: (MapHandle) -> T): T?

  /** Requests a repaint after native accepts the mutation. */
  fun <T> mutateMap(action: (MapHandle) -> T): T? = mutateMap({}, action)

  /**
   * Requests a repaint after native accepts the mutation.
   *
   * Returns after [action] has run or been dropped. [abandon] runs when [action] will not run.
   */
  fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T?

  /**
   * Null when the style has unloaded or no renderer is ready. The renderer exists after the first
   * successful frame and until teardown. The handle must not escape [action].
   */
  fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T?

  /**
   * MapLibre Native implements no encoding but mapbox and terrarium.
   * [#2783](https://github.com/maplibre/maplibre-native/issues/2783)
   */
  override val supportsCustomDemEncoding: Boolean
    get() = false

  override val supportsRasterDemScheme: Boolean
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
      map.removeStyleSource(sourceId)
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

  /**
   * The parse and index run here, on the caller, because they must not run on the map's owner
   * thread. `addSourceWith` returns once its hop has run or been dropped, so the handle outlives
   * every use of it.
   */
  override fun addGeoJsonSource(
    sourceId: String,
    data: GeoJsonData,
    options: GeoJsonOptions,
  ): Boolean {
    val ffiOptions = options.toFfiOptions()
    if (data is GeoJsonData.Uri) {
      return addSourceWith(sourceId) { map ->
        map.addGeoJsonSourceUrl(sourceId, data.uri, ffiOptions)
      }
    }
    // The parse reports a bad document the same way the add reports a bad source.
    val prepared =
      try {
        GeoJsonSourceDataHandle.create(data.toInlineUtf8()!!, ffiOptions)
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    return prepared.use { handle ->
      addSourceWith(sourceId) { map -> map.addGeoJsonSourceData(sourceId, handle) }
    }
  }

  /** Prepared with the options the source was added with; a mismatch is rejected at install. */
  override fun prepareGeoJson(data: GeoJsonData, options: GeoJsonOptions): PreparedGeoJson =
    MlnFfiPreparedGeoJson(
      GeoJsonSourceDataHandle.create(data.toInlineUtf8()!!, options.toFfiOptions())
    )

  /**
   * [claim] runs on the owner thread, which serializes installs. mutateMap waits until the owner
   * thread has used the handle, so the caller may close it afterward.
   */
  override fun setGeoJsonSourceData(
    sourceId: String,
    prepared: PreparedGeoJson,
    claim: () -> Boolean,
  ) {
    val handle = (prepared as MlnFfiPreparedGeoJson).handle
    mutateMap(abandon = { claim() }) { map ->
      if (claim()) map.setGeoJsonSourceData(sourceId, handle)
    }
  }

  override fun setGeoJsonSourceUrl(sourceId: String, url: String, claim: () -> Boolean) {
    mutateMap(abandon = { claim() }) { map -> if (claim()) map.setGeoJsonSourceUrl(sourceId, url) }
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

  /** [kind] is unused: mbgl's `Layer::setProperty` takes layout, paint, and root keys alike. */
  override fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    mutateMap { map ->
      try {
        map.setLayerProperty(layerId, name, value.toJsonBytes())
      } catch (error: MaplibreException) {
        throw StyleMutationException(error.message, error)
      }
    }
  }

  override fun setLayerFilter(layerId: String, filter: JsonElement) {
    mutateMap { map -> map.setLayerFilter(layerId, filter.toJsonBytes()) }
  }

  override fun layerProperty(layerId: String, name: String): JsonElement? = readMap { map ->
    map.layerProperty(layerId, name)?.toJsonElement()
  }

  override fun layerExists(layerId: String): Boolean? = readMap { map ->
    map.styleLayerIds().contains(layerId)
  }

  override fun unsupportedLayerPropertyReason(layerType: String, name: String): String? =
    UNSUPPORTED_LAYER_PROPERTIES[layerType to name]

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

    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: MlnFfiStyleBinding =
      object : MlnFfiStyleBinding {
        override val featureStateStore: MlnFfiFeatureStateStore? = null

        override val tileCoordinators: MlnFfiTileCoordinatorStore? = null

        override val isLoaded: Boolean = false

        override val logger: Logger? = null

        override fun onUnload(action: () -> Unit): () -> Unit {
          action()
          return {}
        }

        override fun <T> readMap(action: (MapHandle) -> T): T? = null

        override fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T? {
          abandon()
          return null
        }

        override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? = null
      }
  }
}

/** A parsed and indexed GeoJSON document, ready to install on the owner thread. */
private class MlnFfiPreparedGeoJson(val handle: GeoJsonSourceDataHandle) : PreparedGeoJson {
  override fun close() {
    handle.close()
  }
}

/** The same options the descriptor writes into source JSON, as the typed adder takes them. */
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
