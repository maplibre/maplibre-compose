package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.checkStyleHandle
import org.maplibre.compose.style.postWrite
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

  override val asMutable: MutableSourceHandle?
    get() = operation {
      if (operations.isSourceWritable(id)) mutableView() else null
    }

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

  internal fun remove() = operation {
    operations.requireSourceWritable(id)
    operations.removeSource(id, resourceIdentity)
  }

  private fun requireCurrent() {
    style.requireCurrent(identity)
    val actualKind = currentKind()
    checkStyleHandle(actualKind != null && (expectedKind == null || actualKind == expectedKind)) {
      "Source '$id' is no longer the $expectedKind source owned by this handle"
    }
  }

  protected fun writeFeatureState(sourceLayerId: String?, featureId: String, state: JsonObject) {
    operation {
      val update = style.prepareFeatureStateUpdate(id, sourceLayerId, featureId, state)
      postMutation(update)
    }
  }

  protected suspend fun readFeatureState(sourceLayerId: String?, featureId: String): JsonObject {
    return suspendingOperation {
      style.awaitOwner { style.featureState(id, sourceLayerId, featureId) }
        ?: JsonObject(emptyMap())
    }
  }

  protected fun clearFeatureState(
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    mutationOperation { style.removeFeatureState(id, sourceLayerId, featureId, stateKey) }
  }

  protected fun clearFeatureStates(sourceLayerId: String?) {
    mutationOperation { style.resetFeatureStates(id, sourceLayerId) }
  }

  internal fun <T> operation(action: () -> T): T = operations.run {
    requireCurrent()
    action()
  }

  internal fun definitionOperation(action: () -> Unit): Unit = operation {
    operations.requireSourceWritable(id)
    postMutation(action)
  }

  protected fun mutationOperation(action: () -> Unit): Unit = operation { postMutation(action) }

  private fun postMutation(action: () -> Unit) {
    style.postWrite("Source '$id'") {
      if (identity.sources.isCurrent(id, resourceIdentity)) action()
    }
  }

  internal suspend fun <T> suspendingOperation(action: suspend () -> T): T {
    operation {}
    val result = action()
    operation {}
    return result
  }
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

  override suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double? =
    suspendingOperation {
      style.clusterExpansionZoom(id, feature)
    }

  override suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<Geometry, JsonObject?>? = suspendingOperation {
    style.clusterChildren(id, feature)
  }

  override suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Int,
    offset: Int,
  ): FeatureCollection<Geometry, JsonObject?>? {
    require(limit >= 0) { "limit must not be negative, was $limit" }
    require(offset >= 0) { "offset must not be negative, was $offset" }
    return suspendingOperation {
      // GL JS reads a limit of 0 as its default of 10.
      if (limit == 0) FeatureCollection(emptyList())
      else style.clusterLeaves(id, feature, limit, offset)
    }
  }

  override fun setFeatureState(featureId: String, state: JsonObject) {
    writeFeatureState(sourceLayerId = null, featureId, state)
  }

  override suspend fun getFeatureState(featureId: String): JsonObject =
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
  ): List<Feature<Geometry, JsonObject?>> {
    return suspendingOperation {
      style.querySourceFeatures(id, sourceLayerIds, predicate.toFilterJson())
    }
  }

  override fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    writeFeatureState(sourceLayerId, featureId, state)
  }

  override suspend fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
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
    mutationOperation { style.invalidateCustomVectorSourceTile(id, tile) }
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
    mutationOperation { style.invalidateCustomGeometrySourceBounds(id, bounds) }
  }

  override fun invalidateTile(tile: TileCoordinate) {
    mutationOperation { style.invalidateCustomGeometrySourceTile(id, tile) }
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
