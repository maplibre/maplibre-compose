@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
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

/** Imperative access to one source in one loaded base-style generation. */
public sealed class SourceHandle
protected constructor(
  public val id: String,
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

  /** The source attribution that the current loaded style reports. */
  public val attributionHtml: String
    get() = operation { style.getSource(id)?.attributionHtml.orEmpty() }

  protected fun writeFeatureState(sourceLayerId: String?, featureId: String, state: JsonObject) {
    operation { style.setFeatureState(id, sourceLayerId, featureId, state) }
  }

  protected fun readFeatureState(sourceLayerId: String?, featureId: String): JsonObject {
    return operation { style.featureState(id, sourceLayerId, featureId) }
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

  protected fun <T> operation(action: () -> T): T = operations.run {
    requireCurrent()
    action()
  }

  protected suspend fun <T> suspendingOperation(action: suspend () -> T): T {
    val checkpoint = operations.checkpoint()
    operation {}
    val result = action()
    operations.requireUnchanged(checkpoint)
    operation {}
    return result
  }
}

/**
 * Imperative access to one GeoJSON source in one loaded base-style generation.
 *
 * A mutation that MapLibre refuses throws [StyleHandleException].
 */
public class GeoJsonSourceHandle
internal constructor(
  id: String,
  style: StyleBinding,
  private val options: GeoJsonOptions,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandle(
    id,
    style,
    expectedKind = "geojson",
    currentKind = currentKind,
    operations = operations,
  ) {
  private val requestedData = AtomicLong(0L)
  private val installedData = AtomicLong(0L)

  /** Replaces the transient data in this loaded style. A base-style reload discards the change. */
  public fun setData(data: GeoJsonData) {
    operation {
      val generation = requestedData.incrementAndFetch()
      if (data is GeoJsonData.Uri) {
        mutate("set data") { style.setGeoJsonSourceUrl(id, data.uri) { claimData(generation) } }
      } else {
        mutate("set data") {
          style.prepareGeoJsonUpdate(id, data, options).use { prepared ->
            style.setGeoJsonSourceData(id, prepared) { claimData(generation) }
          }
        }
      }
    }
  }

  /** Whether [feature] represents a cluster created by this source. */
  public fun isCluster(feature: Feature<*, JsonObject?>): Boolean =
    CLUSTER_ID_PROPERTY in feature.properties.orEmpty()

  /** The zoom at which [feature]'s cluster breaks apart, or zero for a non-cluster feature. */
  public suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double =
    suspendingOperation {
      style.clusterExpansionZoom(id, feature) ?: 0.0
    }

  /** The features one level below [feature]'s cluster, or an empty collection for a non-cluster. */
  public suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<Geometry, JsonObject?> = suspendingOperation {
    style.clusterChildren(id, feature) ?: FeatureCollection(emptyList())
  }

  /** The original points in [feature]'s cluster, or an empty collection for a non-cluster. */
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
  public fun getFeatureState(featureId: String): JsonObject =
    readFeatureState(sourceLayerId = null, featureId)

  /** Removes [stateKey], or every state key when [stateKey] is null. */
  public fun removeFeatureState(featureId: String, stateKey: String? = null) {
    clearFeatureState(sourceLayerId = null, featureId, stateKey)
  }

  /** Removes runtime state from every feature in this source. */
  public fun resetFeatureStates() {
    clearFeatureStates(sourceLayerId = null)
  }

  private fun claimData(generation: Long): Boolean {
    if (!style.isLoaded || generation != requestedData.load()) return false
    while (true) {
      val installed = installedData.load()
      if (generation <= installed) return false
      if (installedData.compareAndSet(installed, generation)) return true
    }
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

/** Imperative access to one vector source in one loaded base-style generation. */
public open class VectorSourceHandle
internal constructor(
  id: String,
  style: StyleBinding,
  expectedKind: String = "vector",
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, style, expectedKind, currentKind, operations) {
  /** Returns loaded features from [sourceLayerIds] that match [predicate]. */
  public fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> {
    return operation { style.querySourceFeatures(id, sourceLayerIds, predicate.toFilterJson()) }
  }

  /** Merges [state] into the runtime state of one feature. */
  public fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    writeFeatureState(sourceLayerId, featureId, state)
  }

  /** Returns the runtime state of one feature. */
  public fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
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

/** Imperative access to one application-supplied vector source. */
public class CustomVectorSourceHandle
internal constructor(
  id: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  VectorSourceHandle(
    id,
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

/** Imperative access to one application-supplied geometry source. */
public class CustomGeometrySourceHandle
internal constructor(
  id: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, style, "custom-geometry", currentKind, operations) {
  /** Requests new features for tiles that intersect [bounds]. */
  public fun invalidateBounds(bounds: BoundingBox) {
    operation { style.invalidateCustomGeometrySourceBounds(id, bounds) }
  }

  /** Requests new features for [tile]. */
  public fun invalidateTile(tile: TileCoordinate) {
    operation { style.invalidateCustomGeometrySourceTile(id, tile) }
  }
}

/** Imperative access to one image source in one loaded base-style generation. */
public class ImageSourceHandle
internal constructor(
  id: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, style, "image", currentKind, operations) {
  /** Updates the geographic corners of the image. */
  public fun setBounds(bounds: PositionQuad) {
    operation {
      style.setImageSourceCoordinates(
        id,
        listOf(bounds.topLeft, bounds.topRight, bounds.bottomRight, bounds.bottomLeft),
      )
    }
  }

  /** Replaces the source image with [image]. */
  public fun setImage(image: ImageBitmap) {
    operation { style.setImageSourceImage(id, image) }
  }

  /** Replaces the source image URI with [uri]. */
  public fun setUri(uri: String) {
    operation { style.setImageSourceUrl(id, uri) }
  }
}

/** Imperative access to one raster source in one loaded base-style generation. */
public class RasterSourceHandle
internal constructor(
  id: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, style, "raster", currentKind, operations)

/** Imperative access to one raster DEM source in one loaded base-style generation. */
public class RasterDemSourceHandle
internal constructor(
  id: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, style, "raster-dem", currentKind, operations)

/** Imperative access to a source type with no specialized common handle. */
public class UnknownSourceHandle
internal constructor(
  id: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandle(id, style, null, currentKind, operations)

internal fun StyleBinding.sourceHandle(
  id: String,
  definition: SourceDefinition?,
  currentDefinition: () -> SourceDefinition?,
  operations: StyleHandleOperationGuard,
): SourceHandle? {
  requireCurrent()
  val source = getSource(id) ?: return null
  val kind = sourceKind(definition, source)
  val composed = definition != null
  val currentKind = {
    val current = currentDefinition()
    if (composed && current == null) null else sourceKind(current, getSource(id))
  }
  return when (kind) {
    "geojson" ->
      GeoJsonSourceHandle(
        id,
        this,
        (definition as? SourceDefinition.GeoJson)?.options ?: GeoJsonOptions(),
        currentKind,
        operations,
      )
    "custom-vector" -> CustomVectorSourceHandle(id, this, currentKind, operations)
    "custom-geometry" -> CustomGeometrySourceHandle(id, this, currentKind, operations)
    "image" -> ImageSourceHandle(id, this, currentKind, operations)
    "raster" -> RasterSourceHandle(id, this, currentKind, operations)
    "raster-dem" -> RasterDemSourceHandle(id, this, currentKind, operations)
    "vector" -> VectorSourceHandle(id, this, currentKind = currentKind, operations = operations)
    else -> UnknownSourceHandle(id, this, currentKind, operations)
  }
}

private fun sourceKind(definition: SourceDefinition?, source: Source?): String? =
  when (definition) {
    is SourceDefinition.CustomGeometry -> "custom-geometry"
    is SourceDefinition.CustomVector -> "custom-vector"
    else -> (source?.toJson()?.get("type") as? JsonPrimitive)?.content
  }
