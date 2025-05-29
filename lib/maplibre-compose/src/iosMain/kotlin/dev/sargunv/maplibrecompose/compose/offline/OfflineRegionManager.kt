package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import cocoapods.MapLibre.MLNOfflineStorage

@Composable
public actual fun rememberOfflineRegionManager(): OfflineRegionManager {
  return IosOfflineRegionManager
}

internal object IosOfflineRegionManager : OfflineRegionManager {

  private val impl = MLNOfflineStorage.sharedOfflineStorage

  private val regionsState = mutableStateOf(emptySet<OfflineRegion>()) // TODO

  override val regions
    get() = regionsState.value

  override suspend fun create(
    definition: OfflineRegionDefinition,
    metadata: ByteArray,
  ): OfflineRegion {
    TODO("Not yet implemented")
  }

  override suspend fun delete(region: OfflineRegion) {
    TODO("Not yet implemented")
  }
}
