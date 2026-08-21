package org.maplibre.compose.offline

import androidx.compose.runtime.mutableStateOf

/** Represents a collection of resources necessary for viewing a region offline. */
public class OfflinePack
internal constructor(
  internal val manager: MlnFfiOfflineManager,
  internal val regionId: Long,
  /** The area for which this pack manages resources. */
  public val definition: OfflinePackDefinition,
  initialMetadata: ByteArray?,
) {

  // Written from the UI dispatcher with values already copied on the owner thread.
  internal val metadataState = mutableStateOf(initialMetadata)
  internal val progressState = mutableStateOf<DownloadProgress>(DownloadProgress.Unknown)

  /**
   * Arbitrary data stored alongside the downloaded resources.
   *
   * Backed by [androidx.compose.runtime.State].
   */
  public val metadata: ByteArray?
    get() = metadataState.value

  /**
   * The pack's current download progress.
   *
   * Backed by [androidx.compose.runtime.State].
   */
  public val downloadProgress: DownloadProgress
    get() = progressState.value

  /**
   * Associates arbitrary [metadata] with the offline pack, replacing any metadata that was
   * previously associated.
   *
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun setMetadata(metadata: ByteArray) {
    manager.updateMetadata(this, metadata)
  }

  override fun equals(other: Any?): Boolean =
    other is OfflinePack && other.regionId == regionId && other.manager === manager

  override fun hashCode(): Int = regionId.hashCode()

  override fun toString(): String = "OfflinePack(regionId=$regionId, definition=$definition)"
}
