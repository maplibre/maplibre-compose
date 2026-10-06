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
 * a base-style reload. Native feature-state writes and invalidations return without waiting for the
 * engine. Rejected writes are logged and retain the previous state. Operations on an expired handle
 * or an unready style throw [StyleHandleException].
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
   * Mutation access to this source, or null when composition owns its definition. Access fails when
   * this handle has expired. The view retains this handle's identity and grants no ownership.
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
   * Enqueues removal of this source. An expired handle fails immediately; an engine rejection,
   * including a layer still referencing the source, is logged and leaves the source available.
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
   * Returns the cluster expansion zoom for [feature], or null when the feature has no cluster ID or
   * the engine reports that the cluster is unavailable.
   */
  public suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double?

  /** Returns the cluster children for [feature], or an empty collection when unavailable. */
  public suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<Geometry, JsonObject?>

  /** Returns the cluster leaves for [feature], or an empty collection when unavailable. */
  public suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>

  /**
   * Merges [state] into the runtime state of the feature identified by [featureId]. Captures the
   * state and its nested values before submitting the update.
   */
  public fun setFeatureState(featureId: String, state: JsonObject): Unit

  /** Returns the runtime state of the feature identified by [featureId]. */
  public suspend fun getFeatureState(featureId: String): JsonObject

  /** Removes [stateKey], or every state key when [stateKey] is null. */
  public fun removeFeatureState(featureId: String, stateKey: String? = null): Unit

  /** Removes runtime state from every feature in this source. */
  public fun resetFeatureStates(): Unit
}

/** Definition writes and removal for a GeoJSON source. */
public sealed interface MutableGeoJsonSourceHandle : GeoJsonSourceHandle, MutableSourceHandle {
  /**
   * Submits [data] to replace the source data for this loaded style.
   *
   * A successful return means that the update was submitted to the current source generation. A
   * newer call supersedes older pending data preparation. Loading a new base style discards the
   * submitted data. This function does not wait for URL loading or rendering.
   *
   * Submitted [GeoJsonData.Features] and all nested collections and properties must remain
   * immutable. Native engines serialize and prepare the data on a background thread. Preparation or
   * installation failures after submission emit
   * [org.maplibre.compose.map.MapEvent.SourceDataFailed] and retain the previous source data.
   *
   * [GeoJsonOptions.synchronousTiling] controls native tile generation and does not make this
   * function wait for preparation, installation, or rendering.
   *
   * @throws StyleHandleException if style content declares this source or submission fails.
   */
  public fun setData(data: GeoJsonData): Unit
}

/** Access to a vector tile source in one loaded style generation. */
public sealed interface VectorTileSourceHandle : SourceHandle {
  override val asMutable: MutableVectorTileSourceHandle?

  /**
   * Returns loaded features from [sourceLayerIds] that match [predicate]. The result is empty
   * before the map has rendered.
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

  /** Returns the runtime state of one feature. */
  public suspend fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject

  /** Removes [stateKey], or every state key when [stateKey] is null. */
  public fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String? = null,
  ): Unit

  /** Removes runtime state from every feature in [sourceLayerId]. */
  public fun resetFeatureStates(sourceLayerId: String): Unit
}

/** Removal for a vector tile source in its loaded style generation. */
public sealed interface MutableVectorTileSourceHandle : VectorTileSourceHandle, MutableSourceHandle

/** Access to a custom vector tile source in one loaded style generation. */
public sealed interface CustomVectorTileSourceHandle : VectorTileSourceHandle {
  override val asMutable: MutableCustomVectorTileSourceHandle?

  /** Requests new data for [tile]. */
  public fun invalidateTile(tile: TileCoordinate): Unit
}

/** Removal for a custom vector tile source in its loaded style generation. */
public sealed interface MutableCustomVectorTileSourceHandle :
  CustomVectorTileSourceHandle, MutableVectorTileSourceHandle

/**
 * Access to a custom geometry source in one loaded style generation.
 *
 * MapLibre GL JS has no per-tile invalidation: an invalidation there reloads every tile of the
 * source, so the requested tile or bounds is advisory. An invalidation requested while provider
 * calls are still in flight is applied once those tiles settle, not synchronously.
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

/** Removal for a custom geometry source in its loaded style generation. */
public sealed interface MutableCustomGeometrySourceHandle :
  CustomGeometrySourceHandle, MutableSourceHandle

/** Access to an image source in one loaded style generation. */
public sealed interface ImageSourceHandle : SourceHandle {
  override val asMutable: MutableImageSourceHandle?
}

/**
 * Definition writes and removal for an image source. Image, URI, and bounds writes return without
 * waiting for the engine and apply in call order. Rejected writes are logged and retain the
 * previous value.
 */
public sealed interface MutableImageSourceHandle : ImageSourceHandle, MutableSourceHandle {
  /**
   * Updates the geographic corners of the image.
   *
   * @throws StyleHandleException if style content declares this source.
   */
  public fun setBounds(bounds: PositionQuad): Unit

  /**
   * Replaces the source image with [image].
   *
   * @throws StyleHandleException if style content declares this source.
   */
  public fun setImage(image: PreparedImage): Unit

  /**
   * Replaces the source image URI with [uri].
   *
   * @throws StyleHandleException if style content declares this source.
   */
  public fun setUri(uri: String): Unit
}

/** Access to a raster tile source in one loaded style generation. */
public sealed interface RasterTileSourceHandle : SourceHandle {
  override val asMutable: MutableRasterTileSourceHandle?
}

/** Removal for a raster tile source in its loaded style generation. */
public sealed interface MutableRasterTileSourceHandle : RasterTileSourceHandle, MutableSourceHandle

/** Access to a raster DEM tile source in one loaded style generation. */
public sealed interface RasterDemTileSourceHandle : SourceHandle {
  override val asMutable: MutableRasterDemTileSourceHandle?
}

/** Removal for a raster DEM tile source in its loaded style generation. */
public sealed interface MutableRasterDemTileSourceHandle :
  RasterDemTileSourceHandle, MutableSourceHandle
