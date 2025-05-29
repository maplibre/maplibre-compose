package dev.sargunv.maplibrecompose.compose.offline

public actual class OfflineRegion {
  public actual val id: Long
    get() = TODO("Not yet implemented")

  public actual val definition: OfflineRegionDefinition
    get() = TODO("Not yet implemented")

  public actual val metadata: ByteArray?
    get() = TODO("Not yet implemented")

  public actual val status: OfflineRegionStatus?
    get() = TODO("Not yet implemented")

  public actual fun setDownloadState(downloadState: DownloadState) {}

  public actual suspend fun delete() {}

  public actual suspend fun invalidate() {}

  public actual suspend fun updateMetadata(metadata: ByteArray) {}
}
