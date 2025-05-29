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

  // TODO initialize this with:
  // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinestorage/packs
  // gotta "observe KVO change notifications on the packs key path" to see when it's initialized
  // no idea how to accomplish that in Kotlin
  private val regionsState = mutableStateOf(emptySet<OfflineRegion>())

  override val regions
    get() = regionsState.value

  // TODO observe progress notifications and update region states:
  // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinepackprogresschangednotification

  override suspend fun create(
    definition: OfflineRegionDefinition,
    metadata: ByteArray,
  ): OfflineRegion {
    // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinestorage/addpackforregion:withcontext:completionhandler:
    TODO("Not yet implemented")
  }

  override suspend fun delete(region: OfflineRegion) {
    // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinestorage/removepack:withcompletionhandler:
    TODO("Not yet implemented")
  }

  override suspend fun invalidate(region: OfflineRegion) {
    // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinestorage/invalidatepack:withcompletionhandler:
    TODO("Not yet implemented")
  }
}
