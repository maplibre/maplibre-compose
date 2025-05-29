package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.mutableStateOf
import cocoapods.MapLibre.MLNOfflinePack

public actual class OfflineRegion internal constructor(internal val impl: MLNOfflinePack) {

  public actual val definition: OfflineRegionDefinition
    get() = TODO("Not yet implemented")

  private val metadataState = mutableStateOf(ByteArray(0)) // TODO

  private val statusState = mutableStateOf<OfflineRegionStatus?>(null)

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val status: OfflineRegionStatus?
    get() = statusState.value

  init {}

  public actual fun setDownloadState(downloadState: DownloadState) {}

  public actual suspend fun invalidate() {}

  public actual suspend fun updateMetadata(metadata: ByteArray) {}
}
