@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.stylespec.sources

import kotlin.js.Json
import org.maplibre.kmp.js.stylespec.Expression

/**
 * A GeoJSON source. Data must be provided via a "data" property, whose value can be a URL or inline
 * GeoJSON. When using in a browser, the GeoJSON data must be on the same domain as the map or
 * served with CORS headers.
 *
 * [See MapLibre Style Spec](https://maplibre.org/maplibre-style-spec/sources/#geojson)
 */
public external interface GeoJSONSourceSpecification : SourceSpecification {
  /** A URL to a GeoJSON file, or inline GeoJSON. */
  public var data: GeoJsonDataDefinition

  /**
   * Maximum zoom level at which to create vector tiles (higher means greater detail at high zoom
   * levels). Defaults to 18
   */
  public var maxzoom: Int?

  /** Contains an attribution to be displayed when map is shown to a user. */
  public var attribution: String?

  /**
   * Size of the tile buffer on each side. A value of 0 produces no buffer. A value of 512 produces
   * a buffer as wide as tile itself. Larger values produce fewer rendering artifacts near tile
   * edges and slower performance. Defaults to 128.
   */
  public var buffer: Int?

  /** An expression for filtering features prior to processing them for rendering. */
  public var filter: Expression?

  /**
   * Douglas-Peucker simplification tolerance (higher means simpler geometries and faster
   * performance). Defaults to 0.375
   */
  public var tolerance: Double?

  /**
   * If data is a collection of point features, setting this to true clusters points by radius into
   * groups. Cluster groups become new Point features in source with additional properties:
   * - cluster: Is true if the point is a cluster
   * - cluster_id: A unique id for the cluster
   * - point_count: Number of original points grouped into this cluster
   * - point_count_abbreviated: An abbreviated point count
   */
  public var cluster: Boolean?

  /**
   * Radius of each cluster if clustering is enabled. A value of 512 indicates a radius equal to
   * width of a tile. Defaults to 50.
   */
  public var clusterRadius: Int?

  /**
   * Max zoom on which to cluster points if clustering is enabled. Defaults to one zoom less than
   * maxzoom (so that last zoom features are not clustered). Clusters are re-evaluated at integer
   * zoom levels so setting clusterMaxZoom to 14 means clusters will be displayed until z15.
   */
  public var clusterMaxZoom: Int?

  /**
   * Minimum number of points necessary to form a cluster if clustering is enabled. Defaults to 2.
   */
  public var clusterMinPoints: Int?

  /**
   * An object defining custom properties on generated clusters if clustering is enabled,
   * aggregating values from clustered points. Has form `{"property_name":
   * [operator, map_expression]}`. `operator` is any expression function that accepts at least 2
   * operands (e.g. "+" or "max") — it accumulates the property value from clusters/points the
   * cluster contains; `map_expression` produces the value of a single point.
   *
   * Example: `{"sum": ["+", ["get", "scalerank"]]}`.
   */
  public var clusterProperties: Json?

  /** Whether to calculate line distance metrics. Required for line layers with gradient. */
  public var lineMetrics: Boolean?

  /**
   * Whether to generate ids for the geojson features. When enabled, feature.id property will be
   * auto assigned based on its index in features array, over-writing any previous values.
   */
  public var generateId: Boolean?

  /**
   * A property to use as a feature id (for feature state). Either a property name, or an object of
   * form `{<sourceLayer>: <propertyName>}`.
   */
  public var promoteId: PromoteIdDefinition?
}
