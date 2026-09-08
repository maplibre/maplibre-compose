package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
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
    }

  internal fun remove(): Boolean = operation {
    operations.requireSourceWritable(id)
    operations.removeSource(id, resourceIdentity)
  }

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

  internal fun definitionOperation(action: () -> Unit): Unit = operation {
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
    definitionOperation {
      mutate("set data") { style.submitGeoJsonData(id, data, options) }
    }
  }

  override fun isCluster(feature: Feature<*, JsonObject?>): Boolean =
    CLUSTER_ID_PROPERTY in feature.properties.orEmpty()

  override suspend fun getClusterExpansionZoom(feature: Feature<*, JsonObject?>): Double =
    suspendingOperation {
      style.clusterExpansionZoom(id, feature) ?: 0.0
    }

  override suspend fun getClusterChildren(
    feature: Feature<*, JsonObject?>
  ): FeatureCollection<Geometry, JsonObject?> = suspendingOperation {
    style.clusterChildren(id, feature) ?: FeatureCollection(emptyList())
  }

  override suspend fun getClusterLeaves(
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?> = suspendingOperation {
    style.clusterLeaves(id, feature, limit, offset) ?: FeatureCollection(emptyList())
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
  override fun invalidateTile(tile: TileCoordinate) {
    operation { style.invalidateCustomVectorSourceTile(id, tile) }
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
  override fun invalidateBounds(bounds: BoundingBox) {
    operation { style.invalidateCustomGeometrySourceBounds(id, bounds) }
  }

  override fun invalidateTile(tile: TileCoordinate) {
    operation { style.invalidateCustomGeometrySourceTile(id, tile) }
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

  internal fun setImage(image: ImageBitmap) {
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
  RasterTileSourceHandle

internal class RasterDemTileSourceHandleImpl
internal constructor(
  id: String,
  attributionHtml: String,
  style: StyleBinding,
  currentKind: () -> String?,
  operations: StyleHandleOperationGuard,
) :
  SourceHandleImpl(id, attributionHtml, style, "raster-dem", currentKind, operations),
  RasterDemTileSourceHandle

internal fun StyleBinding.sourceHandle(
  id: String,
  definition: SourceDefinition?,
  currentDefinition: () -> SourceDefinition?,
  isCurrentResource: () -> Boolean,
  operations: StyleHandleOperationGuard,
): SourceHandle? {
  requireCurrent()
  if (sourceExists(id) != true) return null
  val source = getSource(id)
  val kind = sourceKind(definition, source) ?: return null
  val attribution = source?.attributionHtml.orEmpty()
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
      GeoJsonSourceHandleImpl(
        id,
        attribution,
        this,
        (definition as? SourceDefinition.GeoJson)?.options ?: GeoJsonOptions(),
        currentKind,
        operations,
      )
    "custom-vector" ->
      CustomVectorTileSourceHandleImpl(id, attribution, this, currentKind, operations)
    "custom-geometry" ->
      CustomGeometrySourceHandleImpl(id, attribution, this, currentKind, operations)
    "image" -> ImageSourceHandleImpl(id, attribution, this, currentKind, operations)
    "raster" -> RasterTileSourceHandleImpl(id, attribution, this, currentKind, operations)
    "raster-dem" -> RasterDemTileSourceHandleImpl(id, attribution, this, currentKind, operations)
    "vector" ->
      VectorTileSourceHandleImpl(
        id,
        attribution,
        this,
        currentKind = currentKind,
        operations = operations,
      )
    else -> null
  }
}

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

private class MutableGeoJsonSourceHandleImpl(val source: GeoJsonSourceHandleImpl) :
  MutableGeoJsonSourceHandle, GeoJsonSourceHandle by source {
  override fun setData(data: GeoJsonData) = source.setData(data)

  override fun remove(): Boolean = source.remove()
}

private class MutableImageSourceHandleImpl(val source: ImageSourceHandleImpl) :
  MutableImageSourceHandle, ImageSourceHandle by source {
  override fun setBounds(bounds: PositionQuad) = source.setBounds(bounds)

  override fun setImage(image: ImageBitmap) = source.setImage(image)

  override fun setUri(uri: String) = source.setUri(uri)

  override fun remove(): Boolean = source.remove()
}

private class MutableVectorTileSourceHandleImpl(val source: VectorTileSourceHandleImpl) :
  MutableSourceHandle, VectorTileSourceHandle by source {
  override fun remove(): Boolean = source.remove()
}

private class MutableCustomVectorTileSourceHandleImpl(
  val source: CustomVectorTileSourceHandleImpl
) : MutableSourceHandle, CustomVectorTileSourceHandle by source {
  override fun remove(): Boolean = source.remove()
}

private class MutableCustomGeometrySourceHandleImpl(val source: CustomGeometrySourceHandleImpl) :
  MutableSourceHandle, CustomGeometrySourceHandle by source {
  override fun remove(): Boolean = source.remove()
}

private class MutableRasterTileSourceHandleImpl(val source: RasterTileSourceHandleImpl) :
  MutableSourceHandle, RasterTileSourceHandle by source {
  override fun remove(): Boolean = source.remove()
}

private class MutableRasterDemTileSourceHandleImpl(val source: RasterDemTileSourceHandleImpl) :
  MutableSourceHandle, RasterDemTileSourceHandle by source {
  override fun remove(): Boolean = source.remove()
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
    }
