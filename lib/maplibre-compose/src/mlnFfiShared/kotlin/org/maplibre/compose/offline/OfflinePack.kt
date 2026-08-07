package org.maplibre.compose.offline

import androidx.compose.runtime.mutableStateOf

/**
 * An offline pack: a Compose-facing view of a region, holding no native handle. Every call touching
 * a region has to run on the manager's owner thread, so the manager does the work.
 */
public actual class OfflinePack
internal constructor(
  internal val manager: MlnFfiOfflineManager,
  internal val regionId: Long,
  public actual val definition: OfflinePackDefinition,
  initialMetadata: ByteArray?,
) {

  // Written from the UI dispatcher with values already copied on the owner thread.
  internal val metadataState = mutableStateOf(initialMetadata)
  internal val progressState = mutableStateOf<DownloadProgress>(DownloadProgress.Unknown)

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val downloadProgress: DownloadProgress
    get() = progressState.value

  public actual suspend fun setMetadata(metadata: ByteArray) {
    manager.updateMetadata(this, metadata)
  }

  override fun equals(other: Any?): Boolean =
    other is OfflinePack && other.regionId == regionId && other.manager === manager

  override fun hashCode(): Int = regionId.hashCode()

  override fun toString(): String = "OfflinePack(regionId=$regionId, definition=$definition)"
}
