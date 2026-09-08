package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * Access to a source in one loaded style generation. Feature state and invalidation remain
 * available when composition owns the source definition. Handles expire on removal, replacement, or
 * a base-style reload. A feature-state write does not wait for the engine; rejected writes are
 * logged and retain the previous state.
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

/** Permission to remove a source in its loaded style generation. */
public sealed interface MutableSourceHandle : SourceHandle {
  /** Removes this source. Fails if the handle expired or a layer still references the source. */
  public fun remove(): Boolean
}

/** Access to a GeoJSON source in one loaded style generation. */
public sealed interface GeoJsonSourceHandle : SourceHandle {
  override val asMutable: MutableGeoJsonSourceHandle?

  /** Returns true if [feature] represents a cluster created by this source. */
  public fun isCluster(feature: Feature<*, JsonObject?>): Boolean

  /** Returns the cluster expansion zoom for [feature], or zero if [feature] is not a cluster. */
  public suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double

  /** Returns the cluster children for [feature], or an empty collection for a non-cluster. */
  public suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<Geometry, JsonObject?>

  /** Returns the cluster leaves for [feature], or an empty collection for a non-cluster. */
  public suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>

  /** Merges [state] into the runtime state of the feature identified by [featureId]. */
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
   * By default, a successful return means that the update was submitted to the current source
   * generation. A newer call supersedes an older pending update. Loading a new base style discards
   * the submitted data. This function does not wait for URL loading or rendering.
   *
   * Submitted [GeoJsonData.Features] and all nested collections and properties must remain
   * immutable. By default, native engines serialize and prepare the data on a background thread.
   * Preparation or installation failures after submission emit
   * [org.maplibre.compose.map.MapEvent.SourceDataFailed] and retain the previous source data.
   *
   * With [GeoJsonOptions.synchronousUpdate], native engines serialize, parse, index, and install
   * inline data on the map's owner thread before returning. Failures throw and retain the previous
   * data. The source's currently applied options determine this behavior, including after source
   * replacement. The browser ignores this option.
   *
   * @throws StyleHandleException if style content declares this source, or submission or
   *   synchronous preparation or installation fails.
   */
  public fun setData(data: GeoJsonData): Unit
}

/** Access to a vector tile source in one loaded style generation. */
public sealed interface VectorTileSourceHandle : SourceHandle {
  /**
   * Returns loaded features from [sourceLayerIds] that match [predicate]. The result is empty
   * before the map has rendered.
   */
  public suspend fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>>

  /** Merges [state] into the runtime state of one feature. */
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

/** Access to a custom vector tile source in one loaded style generation. */
public sealed interface CustomVectorTileSourceHandle : VectorTileSourceHandle {
  /** Requests new data for [tile]. */
  public fun invalidateTile(tile: TileCoordinate): Unit
}

/** Access to a custom geometry source in one loaded style generation. */
public sealed interface CustomGeometrySourceHandle : SourceHandle {
  /** Requests new features for tiles that intersect [bounds]. */
  public fun invalidateBounds(bounds: BoundingBox): Unit

  /** Requests new features for [tile]. */
  public fun invalidateTile(tile: TileCoordinate): Unit
}

/** Access to an image source in one loaded style generation. */
public sealed interface ImageSourceHandle : SourceHandle {
  override val asMutable: MutableImageSourceHandle?
}

/** Definition writes and removal for an image source. */
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
  public fun setImage(image: ImageBitmap): Unit

  /**
   * Replaces the source image URI with [uri].
   *
   * @throws StyleHandleException if style content declares this source.
   */
  public fun setUri(uri: String): Unit
}

/** Access to a raster tile source in one loaded style generation. */
public sealed interface RasterTileSourceHandle : SourceHandle {}

/** Access to a raster DEM tile source in one loaded style generation. */
public sealed interface RasterDemTileSourceHandle : SourceHandle {}
