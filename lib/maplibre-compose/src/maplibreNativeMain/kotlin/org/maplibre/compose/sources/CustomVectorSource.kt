@file:kotlin.jvm.JvmName("MlnFfiCustomVectorSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.toGeoJsonFeatures
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.compose.util.toStyleJson
import org.maplibre.nativeffi.geo.CanonicalTileId
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.query.SourceFeatureQueryOptions
import org.maplibre.nativeffi.style.CustomMvtVectorSourceCallback
import org.maplibre.nativeffi.style.CustomMvtVectorSourceOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

public actual class CustomVectorSource : Source {
  private val options: CustomVectorSourceOptions
  private val coordinator: MlnFfiTileRequestCoordinator<ByteArray>

  private val callback =
    object : CustomMvtVectorSourceCallback {
      override fun fetchTile(tileId: CanonicalTileId) {
        coordinator.fetch(tileId)
      }

      override fun cancelTile(tileId: CanonicalTileId) {
        coordinator.cancel(tileId)
      }
    }

  public actual constructor(
    id: String,
    options: CustomVectorSourceOptions,
    provider: VectorTileProvider,
  ) : super(id) {
    this.options = options
    coordinator =
      MlnFfiTileRequestCoordinator(
        name = "maplibre-custom-vector-$id",
        load = provider::loadTile,
        deliver = { map, tile, data -> map.setCustomMvtVectorSourceTileData(id, tile, data) },
        fail = { map, tile, error ->
          map.setCustomMvtVectorSourceTileError(id, tile, error.message ?: "Tile loading failed")
        },
      )
  }

  override fun attachedToStyle(binding: MlnFfiStyleBinding) {
    coordinator.attach(binding)
  }

  override fun detachedFromStyle() {
    coordinator.detach()
  }

  override fun addTo(map: MapHandle, prepared: AutoCloseable?) {
    prepared?.close()
    map.addCustomMvtVectorSource(
      id,
      CustomMvtVectorSourceOptions(callback).also {
        it.minZoom = options.minZoom.toDouble()
        it.maxZoom = options.maxZoom.toDouble()
      },
    )
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "vector")
    putJsonArray("tiles") {}
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
  }

  public actual fun invalidateTile(tile: TileCoordinate) {
    mutate { map -> map.invalidateCustomMvtVectorSourceTile(id, tile.toMlnFfiTileId()) }
  }

  public actual fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue>,
  ): List<Feature<Geometry, JsonObject?>> {
    if (sourceLayerIds.isEmpty()) return emptyList()
    val query =
      SourceFeatureQueryOptions().also {
        it.sourceLayerIds = sourceLayerIds.toList()
        it.filter =
          predicate
            .takeUnless { expression -> expression == const(true) }
            ?.compile(ExpressionContext.None)
            ?.toStyleJson()
            ?.toJsonBytes()
      }
    return binding
      .withRenderSession { session -> session.querySourceFeatures(id, query) }
      ?.toGeoJsonFeatures()
      .orEmpty()
  }

  public actual fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    binding.setFeatureState(id, featureId, state, sourceLayerId)
  }

  public actual fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    binding.getFeatureState(id, featureId, sourceLayerId)

  public actual fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String?,
  ) {
    binding.removeFeatureState(id, featureId, stateKey, sourceLayerId)
  }

  public actual fun resetFeatureStates(sourceLayerId: String) {
    binding.resetFeatureStates(id, sourceLayerId)
  }
}
