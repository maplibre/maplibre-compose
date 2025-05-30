package dev.sargunv.maplibrecompose.compose.offline

import dev.sargunv.maplibrecompose.core.util.toBoundingBox
import dev.sargunv.maplibrecompose.core.util.toLatLngBounds
import dev.sargunv.maplibrecompose.core.util.toMlnGeometry
import io.github.dellisd.spatialk.geojson.Geometry
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineRegion as MlnOfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition as MlnOfflineRegionDefinition
import org.maplibre.android.offline.OfflineRegionStatus as MlnOfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

internal fun MlnOfflineRegionDefinition.toRegionDefinition() =
  when (this) {
    is OfflineTilePyramidRegionDefinition ->
      OfflineRegionDefinition.TilePyramid(
        // can this ever be null? assuming no until proven otherwise
        styleUrl = styleURL!!,
        bounds = bounds!!.toBoundingBox(),
        minZoom = minZoom.toInt(),
        maxZoom = if (maxZoom.isInfinite()) null else maxZoom.toInt(),
      )
    is OfflineGeometryRegionDefinition ->
      OfflineRegionDefinition.Shape(
        styleUrl = styleURL!!,
        geometry = Geometry.fromJson(geometry!!.toJson()),
        minZoom = minZoom.toInt(),
        maxZoom = if (maxZoom.isInfinite()) null else maxZoom.toInt(),
      )
    else -> throw IllegalArgumentException("Unknown OfflineRegionDefinition type: $this")
  }

internal fun OfflineRegionDefinition.toMlnOfflineRegionDefinition(pixelRatio: Float) =
  when (this) {
    is OfflineRegionDefinition.TilePyramid ->
      OfflineTilePyramidRegionDefinition(
        styleURL = styleUrl,
        bounds = bounds.toLatLngBounds(),
        minZoom = minZoom.toDouble(),
        maxZoom = maxZoom?.toDouble() ?: Double.POSITIVE_INFINITY,
        pixelRatio = pixelRatio,
      )
    is OfflineRegionDefinition.Shape ->
      OfflineGeometryRegionDefinition(
        styleURL = styleUrl,
        geometry = geometry.toMlnGeometry(),
        minZoom = minZoom.toDouble(),
        maxZoom = maxZoom?.toDouble() ?: Double.POSITIVE_INFINITY,
        pixelRatio = pixelRatio,
      )
  }

internal fun MlnOfflineRegion.toOfflineRegion() = OfflineRegion(this)

internal fun MlnOfflineRegionStatus.toDownloadProgress() =
  DownloadProgress.Healthy(
    completedResourceCount = completedResourceCount,
    completedResourceBytes = completedResourceSize,
    completedTileCount = completedTileCount,
    completedTileBytes = completedTileSize,
    downloadStatus =
      if (isComplete) DownloadStatus.Complete
      else
        when (downloadState) {
          MlnOfflineRegion.STATE_ACTIVE -> DownloadStatus.Active
          MlnOfflineRegion.STATE_INACTIVE -> DownloadStatus.Inactive
          else -> error("Unknown OfflineRegion state: $downloadState")
        },
    isRequiredResourceCountPrecise = isRequiredResourceCountPrecise,
    requiredResourceCount = requiredResourceCount,
  )
