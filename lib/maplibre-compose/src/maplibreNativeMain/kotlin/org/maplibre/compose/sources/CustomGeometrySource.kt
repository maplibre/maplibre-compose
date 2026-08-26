@file:kotlin.jvm.JvmName("MlnFfiCustomGeometrySourceKt")

package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.nativeffi.geo.CanonicalTileId
import org.maplibre.nativeffi.style.CustomGeometrySourceCallback
import org.maplibre.nativeffi.style.CustomGeometrySourceOptions as FfiCustomGeometrySourceOptions
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.toJson

public actual class CustomGeometrySource : Source {
  private val options: CustomGeometrySourceOptions
  private val coordinator: MlnFfiTileRequestCoordinator<ByteArray>

  private val callback =
    object : CustomGeometrySourceCallback {
      override fun fetchTile(tileId: CanonicalTileId) {
        coordinator.fetch(tileId)
      }

      override fun cancelTile(tileId: CanonicalTileId) {
        coordinator.cancel(tileId)
      }
    }

  public actual constructor(
    id: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ) : super(id) {
    this.options = options
    coordinator =
      MlnFfiTileRequestCoordinator(
        name = "maplibre-custom-geometry-$id",
        load = { tile -> provider.loadTile(tile).toJson().encodeToByteArray() },
        deliver = { map, tile, data -> map.setCustomGeometrySourceTileData(id, tile, data) },
        fail = { map, tile, error ->
          binding.logger?.e(error) {
            "Loading tile ${tile.toTileCoordinate()} of source '$id' failed"
          }
          map.setCustomGeometrySourceTileData(id, tile, EMPTY_FEATURE_COLLECTION)
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
      map.addCustomGeometrySource(
        id,
        FfiCustomGeometrySourceOptions(callback).also {
          it.minZoom = options.minZoom.toDouble()
          it.maxZoom = options.maxZoom.toDouble()
          it.buffer = options.buffer
          it.tolerance = options.tolerance.toDouble()
          it.clip = options.clip
          it.wrap = options.wrap
        },
      )
    }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "custom-geometry")
    put("minzoom", options.minZoom)
    put("maxzoom", options.maxZoom)
    put("buffer", options.buffer)
    put("tolerance", options.tolerance)
    put("clip", options.clip)
    put("wrap", options.wrap)
  }

  public actual fun invalidateBounds(bounds: BoundingBox) {
    mutate { map -> map.invalidateCustomGeometrySourceRegion(id, bounds.toLatLngBounds()) }
  }

  public actual fun invalidateTile(tile: TileCoordinate) {
    mutate { map -> map.invalidateCustomGeometrySourceTile(id, tile.toMlnFfiTileId()) }
  }

  private companion object {
    val EMPTY_FEATURE_COLLECTION =
      """{"type":"FeatureCollection","features":[]}""".encodeToByteArray()
  }
}
