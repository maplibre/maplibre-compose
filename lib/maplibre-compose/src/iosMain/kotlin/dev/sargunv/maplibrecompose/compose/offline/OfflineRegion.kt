package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.mutableStateOf
import cocoapods.MapLibre.MLNOfflinePack

public actual class OfflineRegion internal constructor(internal val impl: MLNOfflinePack) {

  public actual val definition: OfflineRegionDefinition
    get() = TODO("Not yet implemented")

  private val metadataState = mutableStateOf(ByteArray(0)) // TODO

  internal val statusState = mutableStateOf<OfflineRegionStatus?>(null)

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val status: OfflineRegionStatus?
    get() = statusState.value

  public actual fun setDownloadState(downloadState: DownloadState) {
    // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinepack/suspend
    // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinepack/resume
    TODO("Not yet implemented")
  }

  public actual suspend fun updateMetadata(metadata: ByteArray) {
    // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinepack/setcontext:completionhandler:
    TODO("Not yet implemented")
  }

  override fun equals(other: Any?): Boolean = other is OfflineRegion && other.impl == impl

  override fun hashCode(): Int = impl.hashCode()
}
