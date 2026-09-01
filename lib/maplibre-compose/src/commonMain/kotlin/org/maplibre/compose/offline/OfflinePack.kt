package org.maplibre.compose.offline

import androidx.compose.runtime.mutableStateOf

internal fun interface OfflinePackOwner {
  suspend fun updateMetadata(pack: OfflinePack, metadata: ByteArray)
}

/** Represents a collection of resources necessary for viewing a region offline. */
public class OfflinePack
internal constructor(
  internal val owner: OfflinePackOwner,
  internal val regionId: Long,
  /** The area for which this pack manages resources. */
  public val definition: OfflinePackDefinition,
  initialMetadata: ByteArray?,
) {
  internal val metadataState = mutableStateOf(initialMetadata)
  internal val progressState = mutableStateOf<DownloadProgress>(DownloadProgress.Unknown)
  private var requireRuntimeOpen: () -> Unit = {}

  /** Arbitrary data stored alongside the downloaded resources. Backed by Compose snapshot state. */
  public val metadata: ByteArray?
    get() = metadataState.value

  /** The pack's current download progress. Backed by Compose snapshot state. */
  public val downloadProgress: DownloadProgress
    get() = progressState.value

  /**
   * Replaces the arbitrary metadata that is associated with this offline pack.
   *
   * @throws org.maplibre.compose.map.MapRuntimeClosedException if the pack's runtime is closed.
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun setMetadata(metadata: ByteArray) {
    requireRuntimeOpen()
    owner.updateMetadata(this, metadata)
  }

  internal fun bindToRuntime(requireRuntimeOpen: () -> Unit): OfflinePack = apply {
    this.requireRuntimeOpen = requireRuntimeOpen
  }

  override fun equals(other: Any?): Boolean =
    other is OfflinePack && other.regionId == regionId && other.owner === owner

  override fun hashCode(): Int = regionId.hashCode()

  override fun toString(): String = "OfflinePack(regionId=$regionId, definition=$definition)"
}
