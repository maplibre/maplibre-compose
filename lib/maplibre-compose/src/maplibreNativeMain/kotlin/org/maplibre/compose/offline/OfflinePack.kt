package org.maplibre.compose.offline

/** Represents a collection of resources necessary for viewing a region offline. */
public expect class OfflinePack {
  /** The area for which this pack manages resources. */
  public val definition: OfflinePackDefinition

  /**
   * Arbitrary data stored alongside the downloaded resources.
   *
   * Backed by [androidx.compose.runtime.State].
   */
  public val metadata: ByteArray?

  /**
   * The pack's current download progress.
   *
   * Backed by [androidx.compose.runtime.State].
   */
  public val downloadProgress: DownloadProgress

  /**
   * Associates arbitrary [metadata] with the offline pack, replacing any metadata that was
   * previously associated.
   *
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun setMetadata(metadata: ByteArray)
}
