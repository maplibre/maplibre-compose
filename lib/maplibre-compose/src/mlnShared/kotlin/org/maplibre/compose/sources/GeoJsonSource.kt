@file:kotlin.jvm.JvmName("MlnFfiGeoJsonSourceKt")

package org.maplibre.compose.sources

import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.util.CLUSTER_ID_PROPERTY
import org.maplibre.compose.util.toFfiClusterFeature
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.GeoJsonSourceDataHandle
import org.maplibre.nativeffi.style.GeoJsonSourceOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

public actual class GeoJsonSource : Source {

  private val options: GeoJsonOptions
  private val ffiOptions: GeoJsonSourceOptions

  /** UTF-8 GeoJSON for inline data. Null when [dataUrl] is set. */
  @Volatile private var inlineUtf8: ByteArray?

  /** The URI form of the data, when it is one. */
  @Volatile private var dataUrl: String?

  /** Bumped at the start of every [setData] and [publishPreparedData]; orders data by call. */
  @Volatile private var dataGeneration = 0L

  /** The generation of the data last installed. Guarded by [installLock]. */
  @Volatile private var installedGeneration = 0L

  private val installLock = MlnFfiLock()

  /** The newest published data not yet claimed by a parse. Guarded by [installLock]. */
  private var pendingPublish: PendingPublish? = null

  private class PendingPublish(val generation: Long, val data: GeoJsonData)

  /** Serializes parses, so a burst of publications conflates into parses of the newest data. */
  private val publishMutex = Mutex()

  public actual constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super(id) {
    this.options = options
    this.ffiOptions = options.toFfiOptions()
    this.inlineUtf8 = data.toInlineUtf8()
    this.dataUrl = (data as? GeoJsonData.Uri)?.uri
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", dataJson())
    putGeoJsonOptions(options)
    // Neither is in the style spec's GeoJSON source, but MapLibre Native reads both straight off
    // the source JSON.
    put("minzoom", options.minZoom)
    put("synchronousUpdate", options.synchronousUpdate)
  }

  override fun prepareForAttach(): AutoCloseable? {
    if (dataUrl != null) return null
    return prepareData()
  }

  override fun addTo(map: MapHandle, prepared: AutoCloseable?) {
    val url = dataUrl
    if (url != null) {
      prepared?.close()
      map.addGeoJsonSourceUrl(id, url, ffiOptions)
    } else {
      val handle =
        (prepared as? GeoJsonSourceDataHandle)
          ?: run {
            prepared?.close()
            prepareData()
          }
      handle.use { map.addGeoJsonSourceData(id, it) }
    }
  }

  public actual fun setData(data: GeoJsonData) {
    applyData(data, nextDataGeneration(discardPending = true))
  }

  private fun nextDataGeneration(discardPending: Boolean = false): Long = installLock.withLock {
    // A synchronous setData supersedes a publication that no parse has claimed yet.
    if (discardPending) pendingPublish = null
    ++dataGeneration
  }

  /**
   * Parses the [data] argument, then installs it when no newer data has installed. mutateMap waits
   * until the owner thread has used the handle, so closing it afterward is safe.
   */
  private fun applyData(data: GeoJsonData, generation: Long) {
    if (data is GeoJsonData.Uri) {
      installIfNewest(generation) {
        inlineUtf8 = null
        dataUrl = data.uri
        mutate { map -> map.setGeoJsonSourceUrl(id, data.uri) }
      }
      return
    }
    val utf8 = data.toInlineUtf8()!!
    if (generation <= installedGeneration) return
    GeoJsonSourceDataHandle.create(utf8, ffiOptions).use { prepared ->
      installIfNewest(generation) {
        inlineUtf8 = utf8
        dataUrl = null
        mutate { map -> map.setGeoJsonSourceData(id, prepared) }
      }
    }
  }

  /**
   * Installs [generation]'s data unless newer data has already installed. A newer publication that
   * has not parsed yet does not block this install: its own parse follows and overwrites this one.
   */
  private inline fun installIfNewest(generation: Long, install: () -> Unit) {
    installLock.withLock {
      if (generation <= installedGeneration) return
      install()
      installedGeneration = generation
    }
  }

  /**
   * Claims the newest pending publication and parses it on Default. Publications that arrive faster
   * than a parse conflate: each parse works on the newest data rather than every publication paying
   * for a parse of its own.
   *
   * A URI has no parse, so it installs without waiting for [publishMutex]. When an in-flight inline
   * parse finishes, [installIfNewest] keeps the URI.
   */
  internal suspend fun publishPreparedData(data: GeoJsonData) {
    if (data is GeoJsonData.Uri) {
      val generation = installLock.withLock {
        pendingPublish = null
        ++dataGeneration
      }
      withContext(NonCancellable) { applyData(data, generation) }
      return
    }
    installLock.withLock { pendingPublish = PendingPublish(++dataGeneration, data) }
    publishMutex.withLock {
      val pending = installLock.withLock { pendingPublish.also { pendingPublish = null } }
      // Null when a sibling's parse already claimed this publication's data.
      if (pending != null) {
        // The effect that published this data may already be cancelled by a newer publication,
        // but this coroutine claimed the pending data; abandoning the parse here would lose it.
        withContext(NonCancellable + Dispatchers.Default) {
          applyData(pending.data, pending.generation)
        }
      }
    }
  }

  /**
   * Style JSON for reads and error messages. Native install uses [inlineUtf8] directly, so this
   * parse runs only when something asks for the descriptor.
   */
  private fun dataJson() =
    dataUrl?.let { JsonPrimitive(it) } ?: Json.parseToJsonElement(inlineUtf8!!.decodeToString())

  /** Prepared with the options the source was added with; a mismatch is rejected at install. */
  private fun prepareData(): GeoJsonSourceDataHandle =
    GeoJsonSourceDataHandle.create(inlineUtf8!!, ffiOptions)

  public actual fun isCluster(feature: Feature<*, JsonObject?>): Boolean {
    return CLUSTER_ID_PROPERTY in feature.properties.orEmpty()
  }

  public actual suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double {
    val result = queryClusterExtension(feature, EXPANSION_ZOOM_FIELD)
    val zoom = result?.decodeToString()?.toDoubleOrNull()
    if (zoom == null) {
      reportMiss(EXPANSION_ZOOM_FIELD, result)
      return NO_EXPANSION_ZOOM
    }
    return zoom
  }

  public actual suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> = queryClusterFeatures(feature, CHILDREN_FIELD, null)

  public actual fun setFeatureState(featureId: String, state: JsonObject) {
    binding.setFeatureState(id, featureId, state)
  }

  public actual fun getFeatureState(featureId: String): JsonObject =
    binding.getFeatureState(id, featureId)

  public actual fun removeFeatureState(featureId: String, stateKey: String?) {
    binding.removeFeatureState(id, featureId, stateKey)
  }

  public actual fun resetFeatureStates() {
    binding.resetFeatureStates(id)
  }

  public actual suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> =
    queryClusterFeatures(
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
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray? = null,
  ): ByteArray? {
    val ffiFeature = feature.toFfiClusterFeature() ?: return null
    return binding.withRenderSession { session ->
      session.queryFeatureExtension(id, ffiFeature, SUPERCLUSTER_EXTENSION, field, arguments)
    }
  }

  private fun queryClusterFeatures(
    feature: Feature<*, JsonObject?>,
    field: String,
    arguments: ByteArray?,
  ): FeatureCollection<*, JsonObject?> {
    val result = queryClusterExtension(feature, field, arguments)
    val collection =
      result?.decodeToString()?.let { FeatureCollection.fromJsonOrNull<Geometry, JsonObject?>(it) }
    if (collection == null) {
      reportMiss(field, result)
      return FeatureCollection<Geometry, JsonObject?>(emptyList())
    }
    return collection
  }

  /**
   * Reports a lookup that found no cluster. MapLibre answers a successful query with a feature
   * collection, even an empty one, and a failed one with a null value.
   */
  private fun reportMiss(field: String, result: ByteArray?) {
    if (result == null) return
    binding.logger?.w {
      "Cluster '$field' query matched no cluster in source '$id'; the feature's cluster_id is " +
        "probably stale. MapLibre answered with ${result.decodeToString()}."
    }
  }

  private companion object {
    /** The only extension MapLibre answers for a GeoJSON source; anything else returns nothing. */
    const val SUPERCLUSTER_EXTENSION = "supercluster"

    const val EXPANSION_ZOOM_FIELD = "expansion-zoom"
    const val CHILDREN_FIELD = "children"
    const val LEAVES_FIELD = "leaves"

    /** Reported when the cluster has no expansion zoom to give; matches Android. */
    const val NO_EXPANSION_ZOOM = 0.0
  }
}

/** The same options [putGeoJsonOptions] writes into source JSON, as the typed adder takes them. */
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

internal actual suspend fun GeoJsonSource.publishData(data: GeoJsonData) {
  publishPreparedData(data)
}
