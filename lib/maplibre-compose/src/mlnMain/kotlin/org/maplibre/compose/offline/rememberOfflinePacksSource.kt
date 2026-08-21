package org.maplibre.compose.offline

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * Specialization of [rememberGeoJsonSource] that contains the list of [OfflinePack] as features.
 * This allows you to implement a UI to manage offline packs directly on the map.
 *
 * By default, each feature has properties corresponding to the [OfflinePack.downloadProgress].
 *
 * @param offlinePacks The collection of offline packs to represent in the source.
 * @param putExtraProperties A function that will be called with each [OfflinePack] to allow you to
 *   add additional properties to the feature. For example, you can use this to add properties based
 *   on the [OfflinePack.metadata].
 */
@Composable
public fun rememberOfflinePacksSource(
  offlinePacks: Set<OfflinePack>,
  options: GeoJsonOptions = GeoJsonOptions(),
  putExtraProperties: JsonObjectBuilder.(OfflinePack) -> Unit = {},
): Source {
  return rememberGeoJsonSource(
    options = options,
    data =
      GeoJsonData.Features(
        buildFeatureCollection {
          offlinePacks.forEach { pack ->
            addFeature(geometry = pack.definition.geometry) {
              properties = buildJsonObject {
                putDownloadProgressProperties(pack.downloadProgress)
                putExtraProperties(pack)
              }
            }
          }
        }
      ),
  )
}

private fun JsonObjectBuilder.putDownloadProgressProperties(progress: DownloadProgress) =
  when (progress) {
    is DownloadProgress.Healthy -> {
      put("status", progress.status.name)
      put("completed_resource_count", progress.completedResourceCount)
      put("required_resource_count", progress.requiredResourceCount)
      put("completed_resource_bytes", progress.completedResourceBytes)
      put("completed_tile_count", progress.completedTileCount)
      put("completed_tile_bytes", progress.completedTileBytes)
      put("is_required_resource_count_precise", progress.isRequiredResourceCountPrecise)
    }
    is DownloadProgress.Error -> {
      put("status", "Error")
      put("error_reason", progress.reason)
      put("error_message", progress.message)
    }
    is DownloadProgress.TileLimitExceeded -> {
      put("status", "TileLimitExceeded")
      put("tile_limit", progress.limit)
    }
    DownloadProgress.Unknown -> put("status", "Unknown")
  }

private val OfflinePackDefinition.geometry
  get() =
    when (this) {
      is OfflinePackDefinition.TilePyramid -> bounds.toPolygon()
      is OfflinePackDefinition.Shape -> shape
    }

private fun BoundingBox.toPolygon() =
  Polygon(listOf(southwest, northwest, northeast, southeast, southwest))

private val BoundingBox.northwest
  get() = Position(longitude = southwest.longitude, latitude = northeast.latitude)

private val BoundingBox.southeast
  get() = Position(longitude = northeast.longitude, latitude = southwest.latitude)
