package dev.sargunv.maplibrecompose.compose.offline

public expect class OfflineTilePack {
  public val definition: TilePackDefinition
  public val metadata: ByteArray?
  public val progress: DownloadProgress

  public fun resume()

  public fun suspend()

  public suspend fun updateMetadata(metadata: ByteArray)
}
