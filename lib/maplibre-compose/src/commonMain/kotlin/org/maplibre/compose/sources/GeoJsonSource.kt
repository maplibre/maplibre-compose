@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleBinding
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.GeoJsonObject
import org.maplibre.spatialk.geojson.Geometry

/** The style-spec property MapLibre stamps on a feature that stands in for a cluster. */
internal const val CLUSTER_ID_PROPERTY = "cluster_id"

/** A map data source consisting of geojson data. */
public class GeoJsonSource : Source {

  private val options: GeoJsonOptions

  /** The newest data a claim has installed; [toJson] and a later attach read it. */
  private val installed: AtomicReference<Installed>

  /** Bumped at the start of every [setData] and [publishData]; orders data by call. */
  private val dataGeneration = AtomicLong(0L)

  /** The newest published data not yet claimed by a parse. */
  private val pendingPublish = AtomicReference<PendingPublish?>(null)

  /** Serializes parses, so a burst of publications conflates into parses of the newest data. */
  private val publishMutex = Mutex()

  private class Installed(val generation: Long, val data: GeoJsonData)

  private class PendingPublish(val generation: Long, val data: GeoJsonData)

  /**
   * @param id Unique identifier for this source
   * @param data The GeoJSON data in this source
   * @param options see [GeoJsonOptions]
   */
  public constructor(id: String, data: GeoJsonData, options: GeoJsonOptions) : super(id) {
    this.options = options
    this.installed = AtomicReference(Installed(0L, data))
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", installed.load().data.toDataJson())
    putGeoJsonOptions(options)
  }

  override fun addTo(binding: StyleBinding): Boolean =
    binding.addGeoJsonSource(id, installed.load().data, options)

  public fun setData(data: GeoJsonData) {
    // A synchronous setData supersedes a publication that no parse has claimed yet.
    pendingPublish.store(null)
    applyData(data, dataGeneration.incrementAndFetch())
  }

  /**
   * Replaces the source's data. Engines that parse and index inline GeoJSON on the caller do that
   * work here, off the map's owner thread. Publications that outpace the parse conflate to the
   * newest data, and when two publications overlap, the later call is the data the source keeps.
   *
   * A URI has no parse, so it installs without waiting for [publishMutex]. When an in-flight inline
   * parse finishes, the claim keeps the URI.
   */
  internal suspend fun publishData(data: GeoJsonData) {
    if (data is GeoJsonData.Uri) {
      pendingPublish.store(null)
      val generation = dataGeneration.incrementAndFetch()
      withContext(NonCancellable) { applyData(data, generation) }
      return
    }
    storePendingIfNewer(PendingPublish(dataGeneration.incrementAndFetch(), data))
    publishMutex.withLock {
      // Null when a sibling's parse already claimed this publication's data.
      val pending = pendingPublish.exchange(null) ?: return
      // The effect that published this data may already be cancelled by a newer publication, but
      // this coroutine claimed the pending data; abandoning the parse here would lose it.
      withContext(NonCancellable + Dispatchers.Default) {
        applyData(pending.data, pending.generation)
      }
    }
  }

  /**
   * Prepares [data] on the caller, then installs it when no newer data has installed. The binding
   * returns after the install has run or been dropped, so closing the prepared form is safe.
   */
  private fun applyData(data: GeoJsonData, generation: Long) {
    val binding = binding
    if (data is GeoJsonData.Uri) {
      binding.setGeoJsonSourceUrl(id, data.uri) { claimInstall(generation, data) }
      return
    }
    if (generation <= installed.load().generation) return
    binding.prepareGeoJson(data, options).use { prepared ->
      binding.setGeoJsonSourceData(id, prepared) { claimInstall(generation, data) }
    }
  }

  /**
   * Claims the install of [generation]'s data unless newer data has already claimed. The engine
   * runs it where it serializes installs, so the claimed order is the applied order. A newer
   * publication that has not parsed yet does not block a claim: its own parse follows and
   * overwrites this one.
   */
  private fun claimInstall(generation: Long, data: GeoJsonData): Boolean {
    while (true) {
      val current = installed.load()
      if (generation <= current.generation) return false
      if (installed.compareAndSet(current, Installed(generation, data))) return true
    }
  }

  /** Keeps [pendingPublish] the newest publication when stores race. */
  private fun storePendingIfNewer(next: PendingPublish) {
    while (true) {
      val current = pendingPublish.load()
      if (current != null && current.generation >= next.generation) return
      if (pendingPublish.compareAndSet(current, next)) return
    }
  }

  public fun isCluster(feature: Feature<*, JsonObject?>): Boolean =
    CLUSTER_ID_PROPERTY in feature.properties.orEmpty()

  /** The zoom at which [feature]'s cluster breaks apart. */
  public suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double =
    binding.clusterExpansionZoom(id, feature) ?: NO_EXPANSION_ZOOM

  /** The features one level down from [feature]'s cluster. See [getClusterExpansionZoom]. */
  public suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<*, JsonObject?> =
    binding.clusterChildren(id, feature) ?: FeatureCollection<Geometry, JsonObject?>(emptyList())

  /** The original points under [feature]'s cluster. See [getClusterExpansionZoom]. */
  public suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<*, JsonObject?> =
    binding.clusterLeaves(id, feature, limit, offset)
      ?: FeatureCollection<Geometry, JsonObject?>(emptyList())

  /**
   * Merges [state] into the runtime state of the feature identified by [featureId].
   *
   * Style expressions read these values with
   * [feature.state][org.maplibre.compose.expressions.dsl.Feature.state]. Keys already on the
   * feature and absent from [state] stay as they are. [featureId] is matched as text: a GeoJSON
   * `id` of `7` is `"7"`.
   */
  public fun setFeatureState(featureId: String, state: JsonObject) {
    binding.setFeatureState(id, sourceLayerId = null, featureId = featureId, state = state)
  }

  /**
   * The feature's current runtime state, or an empty object when it has none or the source is not
   * on a live map.
   */
  public fun getFeatureState(featureId: String): JsonObject =
    binding.featureState(id, sourceLayerId = null, featureId = featureId)

  /**
   * Removes [stateKey] from the feature identified by [featureId], or every key when [stateKey] is
   * `null`.
   */
  public fun removeFeatureState(featureId: String, stateKey: String? = null) {
    binding.removeFeatureState(id, sourceLayerId = null, featureId = featureId, stateKey = stateKey)
  }

  /** Removes runtime state from every feature in this source. */
  public fun resetFeatureStates() {
    binding.resetFeatureStates(id, sourceLayerId = null)
  }

  private companion object {
    /** Reported when the cluster has no expansion zoom to give. */
    const val NO_EXPANSION_ZOOM = 0.0
  }
}

public sealed interface GeoJsonData {
  public data class Uri(val uri: String) : GeoJsonData

  public data class JsonString(val json: String) : GeoJsonData

  public data class Features(val geoJson: GeoJsonObject) : GeoJsonData
}

/**
 * @param minZoom Minimum zoom level at which to create vector tiles (lower means more field of view
 *   detail at low zoom levels). Web ignores it.
 * @param maxZoom Maximum zoom level at which to create vector tiles (higher means greater detail at
 *   high zoom levels).
 * @param buffer Size of the tile buffer on each side. A value of 0 produces no buffer. A value of
 *   512 produces a buffer as wide as the tile itself. Larger values produce fewer rendering
 *   artifacts near tile edges at the cost of slower performance.
 * @param tolerance Douglas-Peucker simplification tolerance (higher means simpler geometries and
 *   faster performance).
 * @param cluster If the data is a collection of point features, setting this to `true` clusters the
 *   points by radius into groups. Cluster groups become new `Point` features in the source with
 *   additional properties: `cluster`, `cluster_id`, `point_count`, and `point_count_abbreviated`.
 *
 *   See the [MapLibre Style Spec](https://maplibre.org/maplibre-style-spec/sources/#cluster) for
 *   details.
 *
 * @param clusterRadius Radius of each cluster when clustering points, measured in 1/512ths of a
 *   tile. I.e. a value of 512 indicates a radius equal to the width of a tile.
 * @param clusterMaxZoom Max zoom to cluster points on. Clusters are re-evaluated at integer zoom
 *   levels. So, setting the max zoom to 14 means that the clusters will still be displayed on zoom
 *   14.9.
 * @param clusterMinPoints Minimum number of points necessary to form a cluster if clustering is
 *   enabled.
 * @param clusterProperties A map defining custom properties on the generated clusters if clustering
 *   is enabled, aggregating values from clustered points. The keys are the property names, the
 *   values are an aggregation mapper and reducer.
 *
 *   See [ClusterPropertyAggregator.reducer] for an example.
 *
 * @param lineMetrics Whether to calculate line distance metrics. This is required for
 *   [LineLayer][org.maplibre.compose.layers.LineLayer]s that specify a `gradient`.
 * @param synchronousUpdate Whether in-memory GeoJSON updates should be applied synchronously,
 *   reducing update latency at the possible cost of frame rate. Android, iOS, and desktop honor
 *   this; the browser ignores it.
 */
@Immutable
public data class GeoJsonOptions(
  val minZoom: Int = SourceDefaults.MIN_ZOOM,
  val maxZoom: Int = SourceDefaults.MAX_ZOOM,
  val buffer: Int = 128,
  val tolerance: Float = 0.375f,
  val cluster: Boolean = false,
  val clusterRadius: Int = 50,
  val clusterMinPoints: Int = 2,
  val clusterMaxZoom: Int = maxZoom - 1,
  val clusterProperties: Map<String, ClusterPropertyAggregator<*>> = emptyMap(),
  val lineMetrics: Boolean = false,
  val synchronousUpdate: Boolean = false,
) {
  public data class ClusterPropertyAggregator<T : ExpressionValue>(
    /** Produces the value of a single point, passed to the accumulation operator. */
    val mapper: Expression<T>,

    /**
     * An expression that aggregates values produced by the [mapper]. The special function
     * [org.maplibre.compose.expressions.dsl.Feature.accumulated] will return the value accumulated
     * so far, and the feature property with the name of the property will return the next value to
     * aggregate.
     *
     * Example:
     * ```kt
     * GeoJsonOptions.ClusterPropertyAggregator(
     *   mapper = feature["current_range_meters"].asNumber(),
     *   reducer = feature["total_range"].asNumber() + feature.accumulated().asNumber(),
     * )
     * ```
     */
    val reducer: Expression<T>,
  )
}

/** Remember a new [GeoJsonSource] with the given [options] from the given [GeoJsonData]. */
@Composable
public fun rememberGeoJsonSource(
  data: GeoJsonData,
  options: GeoJsonOptions = GeoJsonOptions(),
): GeoJsonSource =
  key(options) {
    val node = LocalStyleNode.current
    val source =
      rememberUserSource(
        factory = { GeoJsonSource(id = it, data = EmptyInlineGeoJson, options = options) },
        update = {},
      )
    LaunchedEffect(source, data, node.style.isUnloaded) {
      if (!node.style.isUnloaded) source.publishData(data)
    }
    source
  }

private val EmptyInlineGeoJson: GeoJsonData =
  GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>(emptyList()))
