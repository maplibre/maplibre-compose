package org.maplibre.compose.sources

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

internal sealed class SourceHandleImpl
protected constructor(
  final override val id: String,
  final override val attributionHtml: String,
  internal val style: StyleBinding,
  private val expectedKind: String?,
  private val currentKind: () -> String?,
  private val operations: StyleHandleOperationGuard,
) : SourceHandle {
  private val identity: StyleIdentity = style.identity
  private val resourceIdentity = style.identity.sources.get(id)
  // Set once a removal through this handle has run; using the handle after that is misuse. A
  // removal that the engine rejects or a later change supersedes leaves it unset.
  @Volatile private var removed = false

  override val asMutable: MutableSourceHandle?
    get() = if (begin() && operations.isSourceWritable(id)) mutableView() else null

  private fun mutableView(): MutableSourceHandle =
    when (this) {
      is GeoJsonSourceHandleImpl -> MutableGeoJsonSourceHandleImpl(this)
      is ImageSourceHandleImpl -> MutableImageSourceHandleImpl(this)
      is CustomVectorTileSourceHandleImpl -> MutableCustomVectorTileSourceHandleImpl(this)
      is CustomGeometrySourceHandleImpl -> MutableCustomGeometrySourceHandleImpl(this)
      is VectorTileSourceHandleImpl -> MutableVectorTileSourceHandleImpl(this)
      is RasterTileSourceHandleImpl -> MutableRasterTileSourceHandleImpl(this)
      is RasterDemTileSourceHandleImpl -> MutableRasterDemTileSourceHandleImpl(this)
      is UnmodeledSourceHandleImpl -> MutableUnmodeledSourceHandleImpl(this)
    }

  internal fun remove() =
    write("its removal") {
      operations.requireSourceWritable(id)
      operations.removeSource(id, resourceIdentity) { removed = true }
    }

  /** False once the loaded style reloads or this source is removed or replaced. */
  private fun isLive(): Boolean {
    val actualKind = currentKind()
    return operations.isReady() &&
      style.isLoaded &&
      actualKind != null &&
      (expectedKind == null || actualKind == expectedKind)
  }

  /**
   * Starts an operation and returns whether this handle is live.
   *
   * @throws IllegalStateException when the map is closed, or when [remove] on this handle has
   *   removed the source.
   */
  private fun begin(): Boolean {
    operations.requireOpen()
    check(!removed) {
      "Source '$id' was removed through this handle"
    }
    return isLive()
  }

  /** Runs [action] while this handle is live; an expired handle logs and does nothing. */
  private inline fun write(target: String, action: () -> Unit) {
    if (begin()) action()
    else style.logger?.w { "Source '$id' ignored $target: the handle has expired" }
  }

  /** Returns null when this handle has expired, including during [action]. */
  internal suspend fun <T> read(action: suspend () -> T?): T? {
    if (!begin()) return null
    val result = action()
    // A close during the read is a style change, not a use after close.
    return result.takeIf { isLive() }
  }

  protected fun writeFeatureState(sourceLayerId: String?, featureId: String, state: JsonObject) {
    write("a feature-state write") {
      postMutation(style.prepareFeatureStateUpdate(id, sourceLayerId, featureId, state))
    }
  }

  protected suspend fun readFeatureState(sourceLayerId: String?, featureId: String): JsonObject? =
    read {
      operations.visit { style.featureState(id, sourceLayerId, featureId) }
    }

  protected fun clearFeatureState(
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    mutationOperation("a feature-state removal") {
      style.removeFeatureState(id, sourceLayerId, featureId, stateKey)
    }
  }

  protected fun clearFeatureStates(sourceLayerId: String?) {
    mutationOperation("a feature-state reset") { style.resetFeatureStates(id, sourceLayerId) }
  }

  internal fun definitionOperation(action: () -> Unit) {
    write("a definition write") {
      operations.requireSourceWritable(id)
      postMutation(action)
    }
  }

  protected fun mutationOperation(target: String, action: () -> Unit) {
    write(target) { postMutation(action) }
  }

  private fun postMutation(action: () -> Unit) {
    operations.post("Source '$id'") {
      if (identity.sources.isCurrent(id, resourceIdentity)) action()
    }
  }

  /** Runs a read through the owner's single engine path. */
  internal suspend fun <T> visit(action: () -> T?): T? = operations.visit(action)
}

internal class GeoJsonSourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  private val options: GeoJsonOptions,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandleImpl(
    id,
    attributionHtml,
    style,
    expectedKind = "geojson",
    currentKind = currentKind,
    operations = operations,
  ),
  GeoJsonSourceHandle {
  override val asMutable: MutableGeoJsonSourceHandle?
    get() = super.asMutable as? MutableGeoJsonSourceHandle

  internal fun setData(data: GeoJsonData) {
    definitionOperation { style.submitGeoJsonData(id, data, options) }
  }

  override fun isCluster(feature: Feature<*, JsonObject?>): Boolean =
    ClusterIdProperty in feature.properties.orEmpty()

  override suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double? = read {
    style.clusterExpansionZoom(id, feature)
  }

  override suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<Geometry, JsonObject?>? = read { style.clusterChildren(id, feature) }

  override suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Int,
    offset: Int,
  ): FeatureCollection<Geometry, JsonObject?>? {
    require(limit >= 0) { "limit must not be negative, was $limit" }
    require(offset >= 0) { "offset must not be negative, was $offset" }
    return read {
      // GL JS reads a limit of 0 as its default of 10.
      if (limit == 0) FeatureCollection(emptyList())
      else style.clusterLeaves(id, feature, limit, offset)
    }
  }

  override fun setFeatureState(featureId: String, state: JsonObject) {
    writeFeatureState(sourceLayerId = null, featureId, state)
  }

  override suspend fun getFeatureState(featureId: String): JsonObject? =
    readFeatureState(sourceLayerId = null, featureId)

  override fun removeFeatureState(featureId: String, stateKey: String?) {
    clearFeatureState(sourceLayerId = null, featureId, stateKey)
  }

  override fun resetFeatureStates() {
    clearFeatureStates(sourceLayerId = null)
  }
}

internal open class VectorTileSourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  expectedKind: String = "vector",
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandleImpl(id, attributionHtml, style, expectedKind, currentKind, operations),
  VectorTileSourceHandle {
  override val asMutable: MutableVectorTileSourceHandle?
    get() = super.asMutable as? MutableVectorTileSourceHandle

  override suspend fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature<Geometry, JsonObject?>> = read {
    style.querySourceFeatures(id, sourceLayerIds, predicate.toFilterJson())
  }
    .orEmpty()

  override fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    writeFeatureState(sourceLayerId, featureId, state)
  }

  override suspend fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject? =
    readFeatureState(sourceLayerId, featureId)

  override fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String?,
  ) {
    clearFeatureState(sourceLayerId, featureId, stateKey)
  }

  override fun resetFeatureStates(sourceLayerId: String) {
    clearFeatureStates(sourceLayerId)
  }
}

internal class CustomVectorTileSourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  VectorTileSourceHandleImpl(
    id,
    attributionHtml,
    style,
    expectedKind = "custom-vector",
    currentKind = currentKind,
    operations = operations,
  ),
  CustomVectorTileSourceHandle {
  override val asMutable: MutableCustomVectorTileSourceHandle?
    get() = super.asMutable as? MutableCustomVectorTileSourceHandle

  override fun invalidateTile(tile: TileCoordinate) {
    mutationOperation("a tile invalidation") { style.invalidateCustomVectorSourceTile(id, tile) }
  }
}

internal class CustomGeometrySourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandleImpl(id, attributionHtml, style, "custom-geometry", currentKind, operations),
  CustomGeometrySourceHandle {
  override val asMutable: MutableCustomGeometrySourceHandle?
    get() = super.asMutable as? MutableCustomGeometrySourceHandle

  override fun invalidateBounds(bounds: BoundingBox) {
    mutationOperation("a bounds invalidation") {
      style.invalidateCustomGeometrySourceBounds(id, bounds)
    }
  }

  override fun invalidateTile(tile: TileCoordinate) {
    mutationOperation("a tile invalidation") { style.invalidateCustomGeometrySourceTile(id, tile) }
  }
}

internal class ImageSourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandleImpl(id, attributionHtml, style, "image", currentKind, operations),
  ImageSourceHandle {
  override val asMutable: MutableImageSourceHandle?
    get() = super.asMutable as? MutableImageSourceHandle

  internal fun setBounds(bounds: PositionQuad) {
    definitionOperation {
      style.setImageSourceCoordinates(
        id,
        listOf(bounds.topLeft, bounds.topRight, bounds.bottomRight, bounds.bottomLeft),
      )
    }
  }

  internal fun setImage(image: PreparedImage) {
    definitionOperation { style.setImageSourceImage(id, image) }
  }

  internal fun setUri(uri: String) {
    definitionOperation { style.setImageSourceUrl(id, uri) }
  }
}

internal class RasterTileSourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandleImpl(id, attributionHtml, style, "raster", currentKind, operations),
  RasterTileSourceHandle {
  override val asMutable: MutableRasterTileSourceHandle?
    get() = super.asMutable as? MutableRasterTileSourceHandle
}

internal class RasterDemTileSourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandleImpl(id, attributionHtml, style, "raster-dem", currentKind, operations),
  RasterDemTileSourceHandle {
  override val asMutable: MutableRasterDemTileSourceHandle?
    get() = super.asMutable as? MutableRasterDemTileSourceHandle
}

/**
 * A handle for a source whose style-spec type has no public handle interface in this version, such
 * as a GL JS video or canvas source. It also keeps [SourceHandle] and [MutableSourceHandle] open:
 * callers' `when` needs an `else` branch.
 */
internal class UnmodeledSourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  kind: String,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) : SourceHandleImpl(id, attributionHtml, style, kind, currentKind, operations)

/** Builds a handle from metadata already captured on the engine owner. */
internal fun StyleBinding.sourceHandle(
  id: String,
  kind: String,
  attributionHtml: String,
  options: GeoJsonOptions,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
): SourceHandle {
  return when (kind) {
    "geojson" ->
      GeoJsonSourceHandleImpl(
        id,
        attributionHtml,
        this,
        options,
        currentKind,
        operations,
      )
    "custom-vector" ->
      CustomVectorTileSourceHandleImpl(id, attributionHtml, this, currentKind, operations)
    "custom-geometry" ->
      CustomGeometrySourceHandleImpl(id, attributionHtml, this, currentKind, operations)
    "image" -> ImageSourceHandleImpl(id, attributionHtml, this, currentKind, operations)
    "raster" -> RasterTileSourceHandleImpl(id, attributionHtml, this, currentKind, operations)
    "raster-dem" ->
      RasterDemTileSourceHandleImpl(id, attributionHtml, this, currentKind, operations)
    "vector" ->
      VectorTileSourceHandleImpl(
        id,
        attributionHtml,
        this,
        currentKind = currentKind,
        operations = operations,
      )
    else -> UnmodeledSourceHandleImpl(id, attributionHtml, this, kind, currentKind, operations)
  }
}

internal fun sourceKind(definition: SourceDefinition?, source: Source?): String? =
  when (definition) {
    is SourceDefinition.CustomGeometry -> "custom-geometry"
    is SourceDefinition.CustomVector -> "custom-vector"
    is SourceDefinition.GeoJson -> "geojson"
    is SourceDefinition.Image -> "image"
    is SourceDefinition.RasterDem -> "raster-dem"
    is SourceDefinition.Json -> (definition.value["type"] as? JsonPrimitive)?.content
    null -> (source?.toJson()?.get("type") as? JsonPrimitive)?.content
  }

private class MutableGeoJsonSourceHandleImpl(val source: GeoJsonSourceHandleImpl) :
  MutableGeoJsonSourceHandle, GeoJsonSourceHandle by source {
  override fun setData(data: GeoJsonData) = source.setData(data)

  override fun remove() = source.remove()
}

private class MutableImageSourceHandleImpl(val source: ImageSourceHandleImpl) :
  MutableImageSourceHandle, ImageSourceHandle by source {
  override fun setBounds(bounds: PositionQuad) = source.setBounds(bounds)

  override fun setImage(image: PreparedImage) = source.setImage(image)

  override fun setUri(uri: String) = source.setUri(uri)

  override fun remove() = source.remove()
}

private class MutableVectorTileSourceHandleImpl(val source: VectorTileSourceHandleImpl) :
  MutableVectorTileSourceHandle, VectorTileSourceHandle by source {
  override fun remove() = source.remove()
}

private class MutableCustomVectorTileSourceHandleImpl(
  val source: CustomVectorTileSourceHandleImpl
) : MutableCustomVectorTileSourceHandle, CustomVectorTileSourceHandle by source {
  override fun remove() = source.remove()
}

private class MutableCustomGeometrySourceHandleImpl(val source: CustomGeometrySourceHandleImpl) :
  MutableCustomGeometrySourceHandle, CustomGeometrySourceHandle by source {
  override fun remove() = source.remove()
}

private class MutableRasterTileSourceHandleImpl(val source: RasterTileSourceHandleImpl) :
  MutableRasterTileSourceHandle, RasterTileSourceHandle by source {
  override fun remove() = source.remove()
}

private class MutableRasterDemTileSourceHandleImpl(val source: RasterDemTileSourceHandleImpl) :
  MutableRasterDemTileSourceHandle, RasterDemTileSourceHandle by source {
  override fun remove() = source.remove()
}

private class MutableUnmodeledSourceHandleImpl(val source: UnmodeledSourceHandleImpl) :
  MutableSourceHandle, SourceHandle by source {
  override fun remove() = source.remove()
}

internal val SourceHandle.implementation: SourceHandleImpl
  get() =
    when (this) {
      is SourceHandleImpl -> this
      is MutableGeoJsonSourceHandleImpl -> source
      is MutableImageSourceHandleImpl -> source
      is MutableVectorTileSourceHandleImpl -> source
      is MutableCustomVectorTileSourceHandleImpl -> source
      is MutableCustomGeometrySourceHandleImpl -> source
      is MutableRasterTileSourceHandleImpl -> source
      is MutableRasterDemTileSourceHandleImpl -> source
      is MutableUnmodeledSourceHandleImpl -> source
    }
