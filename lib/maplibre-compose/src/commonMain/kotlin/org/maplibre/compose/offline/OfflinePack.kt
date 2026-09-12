package org.maplibre.compose.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface OfflinePackOwner {
  /** Throws [IllegalStateException] when the runtime that owns this manager is closed. */
  fun requireRuntimeOpen()

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
  internal val metadataState = MutableStateFlow(initialMetadata)
  internal val progressState = MutableStateFlow<DownloadProgress>(DownloadProgress.Unknown)

  /** Arbitrary data stored alongside the downloaded resources. */
  public val metadata: StateFlow<ByteArray?> = metadataState.asStateFlow()

  /**
   * The pack's current download progress.
   *
   * A pack reads as [DownloadProgress.Unknown] until MapLibre reports its first status.
   */
  public val downloadProgress: StateFlow<DownloadProgress> = progressState.asStateFlow()

  /**
   * Replaces the arbitrary metadata that is associated with this offline pack.
   *
   * @throws IllegalStateException if the pack's runtime is closed.
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun setMetadata(metadata: ByteArray) {
    owner.requireRuntimeOpen()
    owner.updateMetadata(this, metadata)
  }

  override fun equals(other: Any?): Boolean =
    other is OfflinePack && other.regionId == regionId && other.owner === owner

  override fun hashCode(): Int = regionId.hashCode()

  override fun toString(): String = "OfflinePack(regionId=$regionId, definition=$definition)"
}
