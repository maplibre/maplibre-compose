package dev.sargunv.maplibrecompose.compose.offline

import cocoapods.MapLibre.MLNOfflinePack
import cocoapods.MapLibre.MLNOfflinePackProgress
import cocoapods.MapLibre.MLNOfflineRegionProtocol
import platform.Foundation.NSError
import platform.posix.UINT64_MAX

internal fun NSError.toOfflineRegionException(): OfflineRegionException {
  return OfflineRegionException(message = localizedDescription)
}

internal fun MLNOfflineRegionProtocol.toRegionDefinition(): OfflineRegionDefinition = TODO()

internal fun OfflineRegionDefinition.toMLNOfflineRegion(): MLNOfflineRegionProtocol = TODO()

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
