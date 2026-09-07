package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * Provides access to a source for one loaded base-style generation.
 *
 * [id] and [attributionHtml] are plain values, read once when the handle is created. A read that
 * asks the engine for a value, such as feature state, a source query, or a cluster query, suspends
 * until the engine answers. A feature-state write posts to the engine's thread and returns at once;
 * a state the engine rejects is logged, and the feature keeps its previous state.
 *
 * Style content owns the definitions of declared sources: attempts to replace their data, image,
 * URI, or bounds throw [StyleHandleException]. Feature state, queries, and invalidation remain
 * available. Base-style sources and sources added through
 * [org.maplibre.compose.map.StyleSources.add] also permit definition writes.
 */
public sealed class SourceHandle
protected constructor(
  public val id: String,
  /**
   * The source attribution when this handle was created. A source whose attribution arrives later,
   * such as from a TileJSON document, is published again as a new handle.
   */
  public val attributionHtml: String,
  internal val style: StyleBinding,
  private val expectedKind: String?,
  private val currentKind: () -> String?,
  private val operations: StyleHandleOperationGuard,
) {
  private val identity: StyleIdentity = style.identity

  private fun requireCurrent() {
    style.requireCurrent(identity)
    val actualKind = currentKind()
    check(actualKind != null && (expectedKind == null || actualKind == expectedKind)) {
      "Source '$id' is no longer the $expectedKind source owned by this handle"
    }
  }

  protected fun writeFeatureState(sourceLayerId: String?, featureId: String, state: JsonObject) {
    operation { style.setFeatureState(id, sourceLayerId, featureId, state) }
  }

  protected suspend fun readFeatureState(sourceLayerId: String?, featureId: String): JsonObject {
    return suspendingOperation { style.featureState(id, sourceLayerId, featureId) }
  }

  protected fun clearFeatureState(
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    operation { style.removeFeatureState(id, sourceLayerId, featureId, stateKey) }
  }

  protected fun clearFeatureStates(sourceLayerId: String?) {
    operation { style.resetFeatureStates(id, sourceLayerId) }
  }

  internal fun <T> operation(action: () -> T): T = operations.run {
    requireCurrent()
    action()
  }

  protected fun definitionOperation(action: () -> Unit): Unit = operation {
    operations.requireSourceWritable(id)
    action()
  }

  protected suspend fun <T> suspendingOperation(action: suspend () -> T): T {
    operation {}
    val result = action()
    operation {}
    return result
  }
}

/** Provides imperative access to a GeoJSON source for one loaded base-style generation. */
public class GeoJsonSourceHandle
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  private val options: GeoJsonOptions,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandle(
    id,
    attributionHtml,
    style,
    expectedKind = "geojson",
    currentKind = currentKind,
    operations = operations,
  ) {
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
  public fun setData(data: GeoJsonData) {
    definitionOperation {
      mutate("set data") { style.submitGeoJsonData(id, data, options) }
    }
  }

  /** Returns true if [feature] represents a cluster created by this source. */
  public fun isCluster(feature: Feature<*, JsonObject?>): Boolean =
    CLUSTER_ID_PROPERTY in feature.properties.orEmpty()

  /** Returns the cluster expansion zoom for [feature], or zero if [feature] is not a cluster. */
  public suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double =
    suspendingOperation {
      style.clusterExpansionZoom(id, feature) ?: 0.0
    }

  /** Returns the cluster children for [feature], or an empty collection for a non-cluster. */
  public suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<Geometry, JsonObject?> = suspendingOperation {
    style.clusterChildren(id, feature) ?: FeatureCollection(emptyList())
  }

  /** Returns the cluster leaves for [feature], or an empty collection for a non-cluster. */
  public suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?> = suspendingOperation {
    style.clusterLeaves(id, feature, limit, offset) ?: FeatureCollection(emptyList())
  }

  /** Merges [state] into the runtime state of the feature identified by [featureId]. */
  public fun setFeatureState(featureId: String, state: JsonObject) {
    writeFeatureState(sourceLayerId = null, featureId, state)
  }

  /** Returns the runtime state of the feature identified by [featureId]. */
  public suspend fun getFeatureState(featureId: String): JsonObject =
    readFeatureState(sourceLayerId = null, featureId)

  /** Removes [stateKey], or every state key when [stateKey] is null. */
  public fun removeFeatureState(featureId: String, stateKey: String? = null) {
    clearFeatureState(sourceLayerId = null, featureId, stateKey)
  }

  /** Removes runtime state from every feature in this source. */
  public fun resetFeatureStates() {
    clearFeatureStates(sourceLayerId = null)
  }

  private inline fun mutate(operation: String, action: () -> Unit) {
    try {
      action()
    } catch (error: StyleMutationException) {
      throw StyleHandleException(
        "Could not $operation on GeoJSON source '$id': ${error.message}",
        error,
      )
    }
  }
}

/** Provides imperative access to a vector source for one loaded base-style generation. */
public open class VectorSourceHandle
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  expectedKind: String = "vector",
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, attributionHtml, style, expectedKind, currentKind, operations) {
  /**
   * Returns loaded features from [sourceLayerIds] that match [predicate]. The result is empty
   * before the map has rendered.
   */
  public suspend fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> {
    return suspendingOperation {
      style.querySourceFeatures(id, sourceLayerIds, predicate.toFilterJson())
    }
  }

  /** Merges [state] into the runtime state of one feature. */
  public fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    writeFeatureState(sourceLayerId, featureId, state)
  }

  /** Returns the runtime state of one feature. */
  public suspend fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    readFeatureState(sourceLayerId, featureId)

  /** Removes [stateKey], or every state key when [stateKey] is null. */
  public fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String? = null,
  ) {
    clearFeatureState(sourceLayerId, featureId, stateKey)
  }

  /** Removes runtime state from every feature in [sourceLayerId]. */
  public fun resetFeatureStates(sourceLayerId: String) {
    clearFeatureStates(sourceLayerId)
  }
}

/** Provides imperative access to an application-supplied vector source. */
public class CustomVectorSourceHandle
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  VectorSourceHandle(
    id,
    attributionHtml,
    style,
    expectedKind = "custom-vector",
    currentKind = currentKind,
    operations = operations,
  ) {
  /** Requests new data for [tile]. */
  public fun invalidateTile(tile: TileCoordinate) {
    operation { style.invalidateCustomVectorSourceTile(id, tile) }
  }
}

/** Provides imperative access to an application-supplied geometry source. */
public class CustomGeometrySourceHandle
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, attributionHtml, style, "custom-geometry", currentKind, operations) {
  /** Requests new features for tiles that intersect [bounds]. */
  public fun invalidateBounds(bounds: BoundingBox) {
    operation { style.invalidateCustomGeometrySourceBounds(id, bounds) }
  }

  /** Requests new features for [tile]. */
  public fun invalidateTile(tile: TileCoordinate) {
    operation { style.invalidateCustomGeometrySourceTile(id, tile) }
  }
}

/** Provides imperative access to an image source for one loaded base-style generation. */
public class ImageSourceHandle
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, attributionHtml, style, "image", currentKind, operations) {
  /**
   * Updates the geographic corners of the image.
   *
   * @throws StyleHandleException if style content declares this source.
   */
  public fun setBounds(bounds: PositionQuad) {
    definitionOperation {
      style.setImageSourceCoordinates(
        id,
        listOf(bounds.topLeft, bounds.topRight, bounds.bottomRight, bounds.bottomLeft),
      )
    }
  }

  /**
   * Replaces the source image with [image].
   *
   * @throws StyleHandleException if style content declares this source.
   */
  public fun setImage(image: ImageBitmap) {
    definitionOperation { style.setImageSourceImage(id, image) }
  }

  /**
   * Replaces the source image URI with [uri].
   *
   * @throws StyleHandleException if style content declares this source.
   */
  public fun setUri(uri: String) {
    definitionOperation { style.setImageSourceUrl(id, uri) }
  }
}

/** Provides imperative access to a raster source for one loaded base-style generation. */
public class RasterSourceHandle
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, attributionHtml, style, "raster", currentKind, operations)

/** Provides imperative access to a raster DEM source for one loaded base-style generation. */
public class RasterDemSourceHandle
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, attributionHtml, style, "raster-dem", currentKind, operations)

/** Provides imperative access to a source type that has no specialized common handle. */
public class UnknownSourceHandle
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, attributionHtml, style, null, currentKind, operations)

internal fun StyleBinding.sourceHandle(
  id: String,
  definition: SourceDefinition?,
  currentDefinition: () -> SourceDefinition?,
  isCurrentResource: () -> Boolean,
  operations: StyleHandleOperationGuard,
): SourceHandle? {
  requireCurrent()
  val source = getSource(id) ?: return null
  val kind = sourceKind(definition, source)
  val attribution = source.attributionHtml
  val composed = definition != null
  // The identity check covers replacement under the same ID, so the kind check needs no engine
  // read: a composed source's kind follows its desired definition, and any other source keeps the
  // kind it was created with.
  val currentKind = currentKind@{
    if (!isCurrentResource()) return@currentKind null
    if (!composed) return@currentKind kind
    currentDefinition()?.let { sourceKind(it, null) ?: kind }
  }
  return when (kind) {
    "geojson" ->
      GeoJsonSourceHandle(
        id,
        attribution,
        this,
        (definition as? SourceDefinition.GeoJson)?.options ?: GeoJsonOptions(),
        currentKind,
        operations,
      )
    "custom-vector" -> CustomVectorSourceHandle(id, attribution, this, currentKind, operations)
    "custom-geometry" -> CustomGeometrySourceHandle(id, attribution, this, currentKind, operations)
    "image" -> ImageSourceHandle(id, attribution, this, currentKind, operations)
    "raster" -> RasterSourceHandle(id, attribution, this, currentKind, operations)
    "raster-dem" -> RasterDemSourceHandle(id, attribution, this, currentKind, operations)
    "vector" ->
      VectorSourceHandle(id, attribution, this, currentKind = currentKind, operations = operations)
    else -> UnknownSourceHandle(id, attribution, this, currentKind, operations)
  }
}

/**
 * The style-spec type of a source, from its definition when the definition states one and from the
 * engine's [source] otherwise. Null when neither does.
 */
private fun sourceKind(definition: SourceDefinition?, source: Source?): String? =
  when (definition) {
    is SourceDefinition.CustomGeometry -> "custom-geometry"
    is SourceDefinition.CustomVector -> "custom-vector"
    is SourceDefinition.GeoJson -> "geojson"
    is SourceDefinition.Image -> "image"
    is SourceDefinition.RasterDem -> "raster-dem"
    is SourceDefinition.Json -> (definition.value["type"] as? JsonPrimitive)?.content
    null -> (source?.toJson()?.get("type") as? JsonPrimitive)?.content
  }
