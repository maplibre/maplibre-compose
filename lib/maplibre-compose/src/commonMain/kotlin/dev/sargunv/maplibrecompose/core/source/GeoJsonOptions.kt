package dev.sargunv.maplibrecompose.core.source

import androidx.compose.runtime.Immutable

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
 *   additional properties:
 *
 *   - `cluster`: Is `true` if the point is a cluster
 *   - `cluster_id`: A unique id for the cluster to be used in conjunction with the cluster
 *     inspection methods.
 *   - `point_count`: Number of original points grouped into this cluster
 *   - `point_count_abbreviated`: An abbreviated point count
 *
 * @param clusterRadius Radius of each cluster when clustering points, measured in 1/512ths of a
 *   tile. I.e. a value of 512 indicates a radius equal to the width of a tile.
 * @param clusterMaxZoom Max zoom to cluster points on. Clusters are re-evaluated at integer zoom
 *   levels. So, setting the max zoom to 14 means that the clusters will still be displayed on zoom
 *   14.9.
 * @param clusterMinPoints **Note: Not supported on native platforms, see
 *   [maplibre-native#261](https://github.com/maplibre/maplibre-native/issues/261)**
 *
 *   Minimum number of points necessary to form a cluster if clustering is enabled.
 *
 * @param clusterProperties A map defining custom properties on the generated clusters if clustering
 *   is enabled, aggregating values from clustered features. The keys are the property names, the
 *   values define how the value should be aggregated from the features within one cluster.
 *
 * Example:
 * ```kt
 * mapOf(
 *   "totalCapacity" to ClusterAggregation("+", "capacity")
 * )
 * ```
 *
 * Let's assume a geojson source consisting of points for parking lots in which each parking lot has
 * a `capacity` property. The code above adds the `capacity` values of all parking lots within one
 * cluster together into a property named `totalCapacity` of the cluster point.
 *
 * @param lineMetrics Whether to calculate line distance metrics. This is required for
 *   [LineLayer][dev.sargunv.maplibrecompose.compose.layer.LineLayer]s that specify a `gradient`.
 */
@Immutable
public data class GeoJsonOptions(
  val minZoom: Int = Defaults.MIN_ZOOM,
  val maxZoom: Int = Defaults.MAX_ZOOM,
  val buffer: Int = 128,
  val tolerance: Float = 0.375f,
  val cluster: Boolean = false,
  val clusterRadius: Int = 50,
  val clusterMinPoints: Int = 2,
  val clusterMaxZoom: Int = maxZoom - 1,
  val clusterProperties: Map<String, ClusterProperty> = emptyMap(),
  val lineMetrics: Boolean = false,
) {
  /**
   * Defines how the values from the features within a cluster should be aggregated into a cluster
   * property.
   *
   * @param operator Expression name to aggregate the values from the feature properties. This can
   *   be any expression function that accepts at least 2 operands but typically is a math function
   *   like `"+"` or `"max"`. See
   *   [MapLibre Style Spec][https://maplibre.org/maplibre-style-spec/expressions/#math] for valid
   *   names for the operator.
   * @param property name of the feature property whose value should be aggregated.
   */
  public data class ClusterProperty(val operator: String, val property: String)
}
