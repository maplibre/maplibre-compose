package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.spatialk.geojson.GeoJsonObject

/** A map data source consisting of geojson data. */
public expect class GeoJsonSource : Source {
  /**
   * @param id Unique identifier for this source
   * @param data The GeoJSON data in this source
   * @param options see [GeoJsonOptions]
   */
  public constructor(id: String, data: GeoJsonData, options: GeoJsonOptions)

  public fun setData(data: GeoJsonData)
}

public sealed interface GeoJsonData {
  public data class Uri(val uri: String) : GeoJsonData

  public data class JsonString(val json: String) : GeoJsonData

  public data class Features(val geoJson: GeoJsonObject) : GeoJsonData
}

/**
 * @param minZoom Minimum zoom level at which to create vector tiles (lower means more field of view
 *   detail at low zoom levels).
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
    rememberUserSource(
      factory = { GeoJsonSource(id = it, data = data, options = options) },
      update = { setData(data) },
    )
  }
