package dev.sargunv.maplibrecompose.compose.offline

import cocoapods.MapLibre.MLNOfflinePack
import cocoapods.MapLibre.MLNOfflinePackProgress
import cocoapods.MapLibre.MLNOfflineRegionProtocol
import cocoapods.MapLibre.MLNShapeOfflineRegion
import cocoapods.MapLibre.MLNTilePyramidOfflineRegion
import dev.sargunv.maplibrecompose.core.util.toBoundingBox
import io.github.dellisd.spatialk.geojson.Geometry
import platform.Foundation.NSError
import platform.Foundation.NSUTF8StringEncoding
import platform.posix.UINT64_MAX

internal fun NSError.toOfflineRegionException(): OfflineRegionException {
  return OfflineRegionException(message = localizedDescription)
}

internal fun MLNOfflineRegionProtocol.toRegionDefinition(): OfflineRegionDefinition =
  when (this) {
    is MLNTilePyramidOfflineRegion ->
      OfflineRegionDefinition.TilePyramid(
        styleUrl = styleURL.toString(),
        bounds = bounds.toBoundingBox(),
        minZoom = minimumZoomLevel.toInt(),
        maxZoom = if (maximumZoomLevel.isInfinite()) null else maximumZoomLevel.toInt(),
        includeIdeographs = includesIdeographicGlyphs,
      )
    is MLNShapeOfflineRegion ->
      OfflineRegionDefinition.Shape(
        styleUrl = styleURL.toString(),
        geometry =
          Geometry.fromJson(shape.geoJSONDataUsingEncoding(NSUTF8StringEncoding).toString()),
        minZoom = minimumZoomLevel.toInt(),
        maxZoom = if (maximumZoomLevel.isInfinite()) null else maximumZoomLevel.toInt(),
        includeIdeographs = includesIdeographicGlyphs,
      )
    else -> error("Unknown OfflineRegion type: $this")
  }

internal fun OfflineRegionDefinition.toMLNOfflineRegion(): MLNOfflineRegionProtocol =
  when (this) {
    is OfflineRegionDefinition.TilePyramid -> MLNTilePyramidOfflineRegion() // TODO
    is OfflineRegionDefinition.Shape -> MLNShapeOfflineRegion() // TODO
  }

internal fun MLNOfflinePack.toOfflineRegion(): OfflineRegion = OfflineRegion(this)

internal fun MLNOfflinePackProgress.toOfflineRegionStatus(
  state: DownloadState
): OfflineRegionStatus =
  OfflineRegionStatus.Normal(
    completedResourceCount = countOfResourcesCompleted.toLong(),
    completedResourceSize = countOfBytesCompleted.toLong(),
    completedTileCount = countOfTilesCompleted.toLong(),
    completedTileSize = countOfTileBytesCompleted.toLong(),
    downloadState = state,
    // UINT64_MAX when unknown
    isRequiredResourceCountPrecise = maximumResourcesExpected < UINT64_MAX,
    requiredResourceCount = countOfResourcesExpected.toLong(),
  )
