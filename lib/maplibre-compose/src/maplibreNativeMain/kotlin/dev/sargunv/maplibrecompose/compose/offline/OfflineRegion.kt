package dev.sargunv.maplibrecompose.compose.offline

public expect class OfflineRegion {
  public val definition: OfflineRegionDefinition
  public val metadata: ByteArray?
  public val progress: DownloadProgress

  public fun resume()

  public fun suspend()

  public suspend fun updateMetadata(metadata: ByteArray)
}
