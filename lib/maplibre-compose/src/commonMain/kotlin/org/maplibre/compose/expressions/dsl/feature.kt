package org.maplibre.compose.expressions.dsl

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.GeoJsonValue
import org.maplibre.compose.expressions.value.GeometryType
import org.maplibre.compose.expressions.value.MapValue
import org.maplibre.compose.expressions.value.StringValue

/** Object to access feature-related data, see [feature] */
public object Feature {
  /**
   * Returns the value corresponding to the given [key] in the current feature's properties or
   * `null` if it is not present.
   */
  public operator fun get(key: Expression<StringValue>): Expression<*> = FunctionCall.of("get", key)

  /**
   * Returns the value corresponding to the given [key] in the current feature's properties or
   * `null` if it is not present.
   */
  public operator fun get(key: String): Expression<*> = get(const(key))

  /** Tests for the presence of a property value [key] in the current feature's properties. */
  public fun has(key: Expression<StringValue>): Expression<BooleanValue> =
    FunctionCall.of("has", key).cast()

  /** Tests for the presence of a property value [key] in the current feature's properties. */
  public fun has(key: String): Expression<BooleanValue> = has(const(key))

  /**
   * Gets the feature properties object. Note that in some cases, it may be more efficient to use
   * [get]`("property_name")` directly.
   */
  public fun properties(): Expression<MapValue<*>> = FunctionCall.of("properties").cast()

  /**
   * **Note: Not supported on native platforms. See
   * [maplibre-native#1698](https://github.com/maplibre/maplibre-native/issues/1698)**
   *
   * Retrieves a property value from the current feature's state. Returns `null` if the requested
   * property is not present on the feature's state.
   *
   * A feature's state is not part of the GeoJSON or vector tile data, and must be set
   * programmatically on each feature.
   *
   * When `source.promoteId` is not provided, features are identified by their `id` attribute, which
   * must be an integer or a string that can be cast to an integer. When `source.promoteId` is
   * provided, features are identified by their `promoteId` property, which may be a number, string,
   * or any primitive data type. Note that [state] can only be used with layer properties that
   * support data-driven styling.
   */
  public fun <T : ExpressionValue> state(key: Expression<StringValue>): Expression<T> =
    FunctionCall.of("feature-state", key).cast()

  /**
   * **Note: Not supported on native platforms. See
   * [maplibre-native#1698](https://github.com/maplibre/maplibre-native/issues/1698)**
   *
   * Retrieves a property value from the current feature's state. Returns `null` if the requested
   * property is not present on the feature's state.
   *
   * A feature's state is not part of the GeoJSON or vector tile data, and must be set
   * programmatically on each feature.
   *
   * When `source.promoteId` is not provided, features are identified by their `id` attribute, which
   * must be an integer or a string that can be cast to an integer. When `source.promoteId` is
   * provided, features are identified by their `promoteId` property, which may be a number, string,
   * or any primitive data type. Note that [state] can only be used with layer properties that
   * support data-driven styling.
   */
  public fun <T : ExpressionValue> state(key: String): Expression<T> = state(const(key))

  /** Gets the feature's geometry type. */
  public fun geometryType(): Expression<GeometryType> = FunctionCall.of("geometry-type").cast()

  /** Gets the feature's id, if it has one. */
  public fun <T : ExpressionValue> id(): Expression<T> = FunctionCall.of("id").cast()

  /**
   * Gets the progress along a gradient line. Can only be used in the `gradient` property of a line
   * layer, see [LineLayer][org.maplibre.compose.layers.LineLayer].
   */
  public fun lineProgress(): Expression<FloatValue> = FunctionCall.of("line-progress").cast()

  /**
   * Gets the value of a cluster property accumulated so far. Can only be used in the
   * `clusterProperties` option of a clustered GeoJSON source, see
   * [GeoJsonOptions][org.maplibre.compose.sources.GeoJsonOptions].
   */
  public fun accumulated(): Expression<*> = FunctionCall.of("accumulated")

  /**
   * Returns true if the evaluated feature is fully contained inside a boundary of the input
   * geometry, false otherwise. The input value can be a valid GeoJSON of type Polygon,
   * MultiPolygon, Feature, or FeatureCollection. Supported features for evaluation:
   * - Point: Returns false if a point is on the boundary or falls outside the boundary.
   * - LineString: Returns false if any part of a line falls outside the boundary, the line
   *   intersects the boundary, or a line's endpoint is on the boundary.
   */
  public fun within(geometry: Expression<GeoJsonValue>): Expression<BooleanValue> =
    FunctionCall.of("within", geometry).cast()

  /** Returns the shortest distance in meters between the evaluated feature and [geometry]. */
  public fun distance(geometry: Expression<GeoJsonValue>): Expression<FloatValue> =
    FunctionCall.of("distance", geometry).cast()
}

/** Accesses to feature-related data */
public val feature: Feature = Feature
