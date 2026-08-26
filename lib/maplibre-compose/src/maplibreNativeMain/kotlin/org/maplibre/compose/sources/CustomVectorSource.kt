@file:kotlin.jvm.JvmName("MlnFfiCustomVectorSourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.nativeffi.geo.CanonicalTileId
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

  override fun attachedToStyle(binding: StyleBinding) {
    coordinator.attach(binding as MlnFfiStyleBinding)
  }

  override fun detachedFromStyle() {
    coordinator.detach()
  }

  override fun addTo(binding: StyleBinding): Boolean =
    (binding as MlnFfiStyleBinding).addSourceWith(id) { map ->
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
  ): List<Feature<Geometry, JsonObject?>> =
    binding.querySourceFeatures(id, sourceLayerIds, predicate.toFilterJson())

  public actual fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    binding.setFeatureState(id, sourceLayerId, featureId, state)
  }

  public actual fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    binding.featureState(id, sourceLayerId, featureId)

  public actual fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String?,
  ) {
    binding.removeFeatureState(id, sourceLayerId, featureId, stateKey)
  }

  public actual fun resetFeatureStates(sourceLayerId: String) {
    binding.resetFeatureStates(id, sourceLayerId)
  }
}
