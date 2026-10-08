package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.map.MapOptionsDsl
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.util.formatToString
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.GeoJsonObject

/** Names the style-spec property that identifies a cluster feature. */
internal const val ClusterIdProperty = "cluster_id"

/**
 * Defines a map data source that contains GeoJSON data.
 *
 * Native engines add an empty source before preparing inline data in the background. Preparation or
 * installation failures emit [org.maplibre.compose.map.MapEvent.SourceDataFailed]. Failed updates
 * retain the previously installed data; the source remains empty if its initial data fails.
 */
public class GeoJsonSource : VectorSource {

  private val content: Content

  /**
   * @param id Unique identifier for this source
   * @param data The GeoJSON data in this source
   * @param from Initial source settings.
   * @param block Edits [from].
   */
  public constructor(
    id: String,
    data: GeoJsonData,
    from: GeoJsonOptions = GeoJsonOptions.Standard,
    block: GeoJsonOptions.Builder.() -> Unit = {},
  ) : super(id) {
    content = Declared(data, GeoJsonOptions(from, block))
  }

  internal constructor(id: String, definition: JsonObject) : super(id) {
    content = FromStyle(definition)
  }

  override fun toJson(): JsonObject =
    when (val content = content) {
      is Declared ->
        buildJsonObject {
          put("type", "geojson")
          put("data", content.data.toDataJson())
          putGeoJsonOptions(content.options)
        }
      is FromStyle -> content.json
    }

  override fun definition(): SourceDefinition =
    when (val content = content) {
      is Declared ->
        SourceDefinition.GeoJson(
          id,
          content.data,
          GeoJsonOptions(from = content.options) {
            clusterProperties = content.options.clusterProperties.toMap()
          },
        )
      is FromStyle -> super.definition()
    }

  private sealed interface Content

  private class Declared(val data: GeoJsonData, val options: GeoJsonOptions) : Content

  /** What MapLibre reports about a base-style source; the composition never rebuilds it. */
  private class FromStyle(val json: JsonObject) : Content

  public fun isCluster(feature: Feature<*, JsonObject?>): Boolean =
    ClusterIdProperty in feature.properties.orEmpty()
}

/**
 * Supplies a URL, JSON document, or immutable GeoJSON object to a source.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 *
 * [Features] retains the supplied object without copying it. Treat the object and every nested
 * collection and property as immutable after submission. Create a new value for each update. Native
 * engines serialize and prepare inline data on a background thread.
 */
@Immutable
public sealed interface GeoJsonData {
  public data class Uri(val uri: String) : GeoJsonData

  public data class JsonString(val json: String) : GeoJsonData

  public data class Features(val geoJson: GeoJsonObject) : GeoJsonData
}

/**
 * Controls how MapLibre creates tiles and clusters from GeoJSON data.
 *
 * @property minZoom Minimum zoom level at which to create vector tiles (lower means more field of
 *   view detail at low zoom levels). Defaults to 0. Web ignores it.
 * @property maxZoom Maximum zoom level at which to create vector tiles (higher means greater detail
 *   at high zoom levels). Defaults to 18, the style spec default for a GeoJSON source.
 * @property buffer Size of the tile buffer on each side. A value of 0 produces no buffer. A value
 *   of 512 produces a buffer as wide as the tile itself. Larger values produce fewer rendering
 *   artifacts near tile edges at the cost of slower performance.
 * @property tolerance Douglas-Peucker simplification tolerance (higher means simpler geometries and
 *   faster performance).
 * @property cluster If the data is a collection of point features, setting this to `true` clusters
 *   the points by radius into groups. Cluster groups become new `Point` features in the source with
 *   additional properties: `cluster`, `cluster_id`, `point_count`, and `point_count_abbreviated`.
 *
 *   See the [MapLibre Style Spec](https://maplibre.org/maplibre-style-spec/sources/#cluster) for
 *   details.
 *
 * @property clusterRadius Radius of each cluster when clustering points, measured in 1/512ths of a
 *   tile. I.e. a value of 512 indicates a radius equal to the width of a tile.
 * @property clusterMinPoints Minimum number of points necessary to form a cluster if clustering is
 *   enabled.
 * @property clusterMaxZoom Max zoom to cluster points on. Clusters are re-evaluated at integer zoom
 *   levels. So, setting the max zoom to 14 means that the clusters will still be displayed on zoom
 *   14.9. Defaults to [maxZoom] minus 1 unless explicitly set, including when editing previous
 *   options.
 * @property clusterProperties A map defining custom properties on the generated clusters if
 *   clustering is enabled, aggregating values from clustered points. The keys are the property
 *   names, the values are an aggregation mapper and reducer.
 *
 *   See [ClusterPropertyAggregator.reducer] for an example.
 *
 * @property lineMetrics Whether to calculate line distance metrics. This is required for
 *   [LineLayer][org.maplibre.compose.layers.LineLayer]s that specify a `gradient`.
 * @property synchronousTiling Whether native engines generate requested tiles during the update
 *   pass instead of scheduling separate tile work. This can make small, frequently updated sources
 *   appear sooner, at the cost of more work during the update. Data preparation still runs on a
 *   worker and source updates return without waiting for native work. Android, iOS, and desktop
 *   honor this option. The browser ignores it.
 */
@Immutable
public data class GeoJsonOptions
private constructor(
  public val minZoom: Int,
  public val maxZoom: Int,
  public val buffer: Int,
  public val tolerance: Float,
  public val cluster: Boolean,
  public val clusterRadius: Int,
  public val clusterMinPoints: Int,
  public val clusterMaxZoom: Int,
  public val clusterProperties: Map<String, ClusterPropertyAggregator<*>>,
  public val lineMetrics: Boolean,
  public val synchronousTiling: Boolean,
  private val clusterMaxZoomOverride: Int?,
) {
  private constructor(
    builder: Builder
  ) : this(
    builder.minZoom,
    builder.maxZoom,
    builder.buffer,
    builder.tolerance,
    builder.cluster,
    builder.clusterRadius,
    builder.clusterMinPoints,
    builder.clusterMaxZoom,
    builder.clusterProperties,
    builder.lineMetrics,
    builder.synchronousTiling,
    builder.clusterMaxZoomOverride,
  )

  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: GeoJsonOptions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  override fun toString(): String =
    formatToString(
      "GeoJsonOptions",
      "minZoom" to minZoom,
      "maxZoom" to maxZoom,
      "buffer" to buffer,
      "tolerance" to tolerance,
      "cluster" to cluster,
      "clusterRadius" to clusterRadius,
      "clusterMinPoints" to clusterMinPoints,
      "clusterMaxZoom" to clusterMaxZoom,
      "clusterProperties" to clusterProperties,
      "lineMetrics" to lineMetrics,
      "synchronousTiling" to synchronousTiling,
    )

  @MapOptionsDsl
  public class Builder internal constructor(from: GeoJsonOptions?) {
    /** See [GeoJsonOptions.minZoom]. */
    public var minZoom: Int = from?.minZoom ?: 0
    /** See [GeoJsonOptions.maxZoom]. */
    public var maxZoom: Int = from?.maxZoom ?: 18
    /** See [GeoJsonOptions.buffer]. */
    public var buffer: Int = from?.buffer ?: 128
    /** See [GeoJsonOptions.tolerance]. */
    public var tolerance: Float = from?.tolerance ?: 0.375f
    /** See [GeoJsonOptions.cluster]. */
    public var cluster: Boolean = from?.cluster ?: false
    /** See [GeoJsonOptions.clusterRadius]. */
    public var clusterRadius: Int = from?.clusterRadius ?: 50
    /** See [GeoJsonOptions.clusterMinPoints]. */
    public var clusterMinPoints: Int = from?.clusterMinPoints ?: 2
    /** See [GeoJsonOptions.clusterMaxZoom]. */
    public var clusterMaxZoom: Int
      get() = clusterMaxZoomOverride ?: (maxZoom - 1)
      set(value) {
        clusterMaxZoomOverride = value
      }

    /** See [GeoJsonOptions.clusterProperties]. */
    public var clusterProperties: Map<String, ClusterPropertyAggregator<*>> =
      from?.clusterProperties ?: emptyMap()
    /** See [GeoJsonOptions.lineMetrics]. */
    public var lineMetrics: Boolean = from?.lineMetrics ?: false
    /** See [GeoJsonOptions.synchronousTiling]. */
    public var synchronousTiling: Boolean = from?.synchronousTiling ?: false

    internal var clusterMaxZoomOverride: Int? = from?.clusterMaxZoomOverride
  }

  public companion object {
    /** The default source settings. */
    public val Standard: GeoJsonOptions = GeoJsonOptions(Builder(from = null))
  }

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

/** Remembers a [GeoJsonSource] from [data], with settings edited from [from]. */
@Composable
public fun rememberGeoJsonSource(
  data: GeoJsonData,
  from: GeoJsonOptions = GeoJsonOptions.Standard,
  block: GeoJsonOptions.Builder.() -> Unit = {},
): GeoJsonSource {
  val options = GeoJsonOptions(from, block)
  return key(options) {
    rememberUserSource { GeoJsonSource(id = it, data = data, from = options) }
  }
}
