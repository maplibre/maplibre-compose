package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * Access to a source in one loaded style generation. Feature state and invalidation remain
 * available when composition owns the source definition. Handles expire on removal, replacement, or
 * a base-style reload, and while the style is not ready. An expired handle reads null or an empty
 * result, and its writes do nothing and log a warning. Using a handle after its own
 * [MutableSourceHandle.remove] has removed the source throws [IllegalStateException]. Native
 * feature-state writes and invalidations return without waiting for the engine. Rejected writes are
 * logged and retain the previous state.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface SourceHandle {
  public val id: String
  /**
   * Attribution captured when this handle was published. Attribution loaded later from TileJSON is
   * published as a new handle.
   */
  public val attributionHtml: String
  /**
   * Mutation access to this source, or null when composition owns its definition or this handle has
   * expired. The view retains this handle's identity and grants no ownership.
   */
  public val asMutable: MutableSourceHandle?
}

/**
 * Permission to remove a source in its loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableSourceHandle : SourceHandle {
  /**
   * Enqueues removal of this source. An expired handle does nothing and logs a warning; an engine
   * rejection, including a layer still referencing the source, is logged and leaves the source
   * available.
   */
  public fun remove()
}

/**
 * Access to a GeoJSON source in one loaded style generation.
 *
 * Cluster features must come from the source's current data.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface GeoJsonSourceHandle : SourceHandle {
  override val asMutable: MutableGeoJsonSourceHandle?

  /** Returns true if [feature] carries a cluster ID. */
  public fun isCluster(feature: Feature<*, JsonObject?>): Boolean

  /**
   * Returns the zoom level at which the cluster [feature] splits into more than one child.
   *
   * On the browser, the engine does not check the cluster ID, so a stale cluster ID can return the
   * zoom of a different cluster instead of null.
   *
   * @return the zoom, or null if [feature] has no cluster ID, no cluster with that ID exists in the
   *   source's current data, the source does not cluster its data, or this handle has expired.
   * @throws StyleHandleException if the engine fails the query.
   */
  public suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double?

  /**
   * Returns the clusters and points that the cluster [feature] splits into at the next zoom level.
   *
   * @return the children, or null if [feature] has no cluster ID, no cluster with that ID exists in
   *   the source's current data, the source does not cluster its data, or this handle has expired.
   * @throws StyleHandleException if the engine fails the query.
   */
  public suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<Geometry, JsonObject?>?

  /**
   * Returns the points in the cluster [feature], including the points of nested clusters. Skips the
   * first [offset] points and returns at most [limit] points.
   *
   * @param limit The maximum number of points to return. Must not be negative. When it is 0, the
   *   result is empty, even if the cluster does not exist.
   * @param offset The number of points to skip. Must not be negative.
   * @return the points, which are empty when [limit] is 0 or [offset] skips every point, or null if
   *   [feature] has no cluster ID, no cluster with that ID exists in the source's current data, the
   *   source does not cluster its data, or this handle has expired.
   * @throws IllegalArgumentException if [limit] or [offset] is negative.
   * @throws StyleHandleException if the engine fails the query.
   */
  public suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Int,
    offset: Int,
  ): FeatureCollection<Geometry, JsonObject?>?

  /**
   * Merges [state] into the runtime state of the feature identified by [featureId].
   *
   * @param featureId The feature's GeoJSON `id` as text, such as `"7"` for an `id` of `7`. For a
   *   feature from a query or a click, that is `feature.id?.content`. Give features integer ids:
   *   MapLibre GL JS reads a GeoJSON `id` as an integer, so on the browser, state set for an `id`
   *   such as `"a7"` does not reach the feature.
   * @param state The values to merge. The state and its nested values are captured before the
   *   update is submitted.
   */
  public fun setFeatureState(featureId: String, state: JsonObject): Unit

  /**
   * Returns the runtime state of the feature identified by [featureId], which is empty when the
   * feature has no state, or null if this handle has expired.
   */
  public suspend fun getFeatureState(featureId: String): JsonObject?

  /** Removes [stateKey], or every state key when [stateKey] is null. */
  public fun removeFeatureState(featureId: String, stateKey: String? = null): Unit

  /** Removes runtime state from every feature in this source. */
  public fun resetFeatureStates(): Unit
}

/**
 * Definition writes and removal for a GeoJSON source.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableGeoJsonSourceHandle : GeoJsonSourceHandle, MutableSourceHandle {
  /**
   * Submits [data] to replace the source data for this loaded style.
   *
   * A successful return means that the update was submitted to the current source generation. A
   * newer call supersedes older pending data preparation. Loading a new base style discards the
   * submitted data. This function does not wait for URL loading or rendering.
   *
   * Preparation or installation failures after submission emit
   * [org.maplibre.compose.map.MapEvent.SourceDataFailed] and retain the previous source data.
   *
   * [GeoJsonOptions.synchronousTiling] controls native tile generation and does not make this
   * function wait for preparation, installation, or rendering.
   *
   * @param data Submitted [GeoJsonData.Features] and all nested collections and properties must
   *   remain immutable.
   * @throws IllegalStateException if style content declares this source.
   */
  public fun setData(data: GeoJsonData): Unit
}

/**
 * Access to a vector tile source in one loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface VectorTileSourceHandle : SourceHandle {
  override val asMutable: MutableVectorTileSourceHandle?

  /**
   * Returns loaded features from [sourceLayerIds] that match [predicate].
   *
   * Features come from the tiles loaded for the current view, whether or not a layer draws them. A
   * feature that crosses tile boundaries can come back as several pieces, one per tile, and a point
   * near a tile edge can appear more than once.
   *
   * @return the features, which are empty before the map has rendered and when this handle has
   *   expired.
   */
  public suspend fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>>

  /**
   * Merges [state] into the runtime state of one feature. Captures the state and its nested values
   * before submitting the update.
   */
  public fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject): Unit

  /**
   * Returns the runtime state of one feature, which is empty when the feature has no state, or null
   * if this handle has expired.
   */
  public suspend fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject?

  /** Removes [stateKey], or every state key when [stateKey] is null. */
  public fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String? = null,
  ): Unit

  /** Removes runtime state from every feature in [sourceLayerId]. */
  public fun resetFeatureStates(sourceLayerId: String): Unit
}

/**
 * Removal for a vector tile source in its loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableVectorTileSourceHandle : VectorTileSourceHandle, MutableSourceHandle

/**
 * Access to a custom vector tile source in one loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface CustomVectorTileSourceHandle : VectorTileSourceHandle {
  override val asMutable: MutableCustomVectorTileSourceHandle?

  /**
   * Requests new data for [tile]. Invalidation may also reload other tiles of this source. A tile
   * whose provider call is in progress may show that call's result before it reloads.
   *
   * On MapLibre Native, invalidating a tile that failed to load has no effect. The tile stays
   * failed until MapLibre requests it again because it is needed again.
   */
  public fun invalidateTile(tile: TileCoordinate): Unit
}

/**
 * Removal for a custom vector tile source in its loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableCustomVectorTileSourceHandle :
  CustomVectorTileSourceHandle, MutableVectorTileSourceHandle

/**
 * Access to a custom geometry source in one loaded style generation.
 *
 * Invalidation may also reload tiles outside the requested tile or bounds. A tile whose provider
 * call is in progress may show that call's result before it reloads.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface CustomGeometrySourceHandle : SourceHandle {
  override val asMutable: MutableCustomGeometrySourceHandle?

  /** Requests new features for tiles that intersect [bounds]. */
  public fun invalidateBounds(bounds: BoundingBox): Unit

  /** Requests new features for [tile]. */
  public fun invalidateTile(tile: TileCoordinate): Unit
}

/**
 * Removal for a custom geometry source in its loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableCustomGeometrySourceHandle :
  CustomGeometrySourceHandle, MutableSourceHandle

/**
 * Access to an image source in one loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface ImageSourceHandle : SourceHandle {
  override val asMutable: MutableImageSourceHandle?
}

/**
 * Definition writes and removal for an image source. Image, URI, and bounds writes return without
 * waiting for the engine and apply in call order. Rejected writes are logged and retain the
 * previous value.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableImageSourceHandle : ImageSourceHandle, MutableSourceHandle {
  /**
   * Updates the geographic corners of the image.
   *
   * @throws IllegalStateException if style content declares this source.
   */
  public fun setBounds(bounds: PositionQuad): Unit

  /**
   * Replaces the source image with [image].
   *
   * @throws IllegalStateException if style content declares this source.
   */
  public fun setImage(image: PreparedImage): Unit

  /**
   * Replaces the source image URI with [uri].
   *
   * @throws IllegalStateException if style content declares this source.
   */
  public fun setUri(uri: String): Unit
}

/**
 * Access to a raster tile source in one loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface RasterTileSourceHandle : SourceHandle {
  override val asMutable: MutableRasterTileSourceHandle?
}

/**
 * Removal for a raster tile source in its loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableRasterTileSourceHandle : RasterTileSourceHandle, MutableSourceHandle

/**
 * Access to a raster DEM tile source in one loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface RasterDemTileSourceHandle : SourceHandle {
  override val asMutable: MutableRasterDemTileSourceHandle?
}

/**
 * Removal for a raster DEM tile source in its loaded style generation.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableRasterDemTileSourceHandle :
  RasterDemTileSourceHandle, MutableSourceHandle
