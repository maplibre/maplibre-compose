package dev.sargunv.maplibrecompose.compose.offline

import cocoapods.MapLibre.MLNOfflinePack
import cocoapods.MapLibre.MLNOfflinePackProgress
import cocoapods.MapLibre.MLNOfflineRegionProtocol
import platform.Foundation.NSError

internal fun NSError.toOfflineRegionException(): OfflineRegionException {
  return OfflineRegionException(message = localizedDescription)
}

internal fun MLNOfflineRegionProtocol.toRegionDefinition(): OfflineRegionDefinition = TODO()

internal fun OfflineRegionDefinition.toMLNOfflineRegion(): MLNOfflineRegionProtocol = TODO()

internal fun MLNOfflinePack.toOfflineRegion(): OfflineRegion = OfflineRegion(this)

internal fun MLNOfflinePackProgress.toOfflineRegionStatus(): OfflineRegionStatus = TODO()
