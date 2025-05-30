package dev.sargunv.maplibrecompose.compose.offline

/** Represents a collection of resources necessary for viewing a region offline. */
public expect class OfflinePack {
  /** The area for which this pack manages resources. */
  public val definition: OfflinePackDefinition

  /** Arbitrary data stored alongside the downloaded resources. */
  public val metadata: ByteArray?

  /** The pack's current progress. */
  public val progress: DownloadProgress

  /** Resume downloading if the pack is paused. */
  public fun resume()

  /** Pause downloading if the pack is downloading. */
  public fun suspend()

  /**
   * Associates arbitrary [metadata] with the offline pack, replacing any metadata that was
   * previously associated.
   */
  public suspend fun setMetadata(metadata: ByteArray)
}
