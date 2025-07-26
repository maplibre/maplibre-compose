package org.maplibre.compose.offline

import androidx.compose.runtime.mutableStateOf
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus

public actual class OfflinePack private constructor(internal val impl: OfflineRegion) :
  OfflineRegion.OfflineRegionObserver {

  internal companion object {
    // there must only be one OfflinePack instance per OfflineRegion, because only the newest will
    // receive updates to its State
    private val instances = mutableMapOf<OfflineRegion, OfflinePack>()

    internal fun getInstance(region: OfflineRegion): OfflinePack {
      return instances.getOrPut(region) { OfflinePack(region) }
    }

    internal fun dispose(pack: OfflinePack) {
      instances.remove(pack.impl)
    }
  }

  public actual val definition: OfflinePackDefinition
    get() = impl.definition.toOfflinePackDefinition()

  private val metadataState = mutableStateOf(impl.metadata)

  private val progressState = mutableStateOf<DownloadProgress>(DownloadProgress.Unknown)

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val downloadProgress: DownloadProgress
    get() = progressState.value

  init {
    impl.setDeliverInactiveMessages(true)
    impl.setObserver(this)
    impl.getStatus(
      object : OfflineRegion.OfflineRegionStatusCallback {
        override fun onStatus(status: OfflineRegionStatus?) {
          progressState.value = status?.toDownloadProgress() ?: DownloadProgress.Unknown
        }

        override fun onError(error: String?) =
          throw OfflineManagerException(error ?: "Unknown error")
      }
    )
  }

  override fun onStatusChanged(status: OfflineRegionStatus) {
    progressState.value = status.toDownloadProgress()
  }

  override fun onError(error: OfflineRegionError) {
    progressState.value = DownloadProgress.Error(error.reason, error.message)
  }

  override fun mapboxTileCountLimitExceeded(limit: Long) {
    progressState.value = DownloadProgress.TileLimitExceeded(limit)
  }

  public actual suspend fun setMetadata(metadata: ByteArray): Unit =
    suspendCoroutine { continuation ->
      impl.updateMetadata(
        metadata,
        object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
          override fun onUpdate(metadata: ByteArray) {
            metadataState.value = metadata
            continuation.resume(Unit)
          }

          override fun onError(error: String) =
            continuation.resumeWithException(OfflineManagerException(error))
        },
      )
    }

  override fun equals(other: Any?): Boolean = other is OfflinePack && other.impl == impl

  override fun hashCode(): Int = impl.hashCode()
}
