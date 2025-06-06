package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.Composable
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.core.source.GeoJsonOptions
import dev.sargunv.maplibrecompose.core.source.Source
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Polygon
import io.github.dellisd.spatialk.geojson.Position
import io.github.dellisd.spatialk.geojson.dsl.PropertiesBuilder
import io.github.dellisd.spatialk.geojson.dsl.featureCollection

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
  id: String,
  offlinePacks: Set<OfflinePack>,
  options: GeoJsonOptions = GeoJsonOptions(),
  putExtraProperties: PropertiesBuilder.(OfflinePack) -> Unit = {},
): Source {
  return rememberGeoJsonSource(
    id = id,
    options = options,
    data =
      GeoJsonData.Features(
        featureCollection {
          offlinePacks.forEach { pack ->
            feature(pack.definition.geometry) {
              putDownloadProgressProperties(pack.downloadProgress)
              putExtraProperties(pack)
            }
          }
        }
      ),
  )
}

private fun PropertiesBuilder.putDownloadProgressProperties(progress: DownloadProgress) =
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
