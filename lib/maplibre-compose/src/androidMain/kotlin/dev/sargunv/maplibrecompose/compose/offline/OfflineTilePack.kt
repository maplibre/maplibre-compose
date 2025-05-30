package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.mutableStateOf
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus

public actual class OfflineTilePack internal constructor(internal val impl: OfflineRegion) :
  OfflineRegion.OfflineRegionObserver {
  public actual val definition: TilePackDefinition
    get() = impl.definition.toTilePackDefinition()

  private val metadataState = mutableStateOf(impl.metadata)

  private val progressState = mutableStateOf<DownloadProgress>(DownloadProgress.Unknown)

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val progress: DownloadProgress
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
          throw OfflineTilesManagerException(error ?: "Unknown error")
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

  public actual fun suspend() {
    impl.setDownloadState(OfflineRegion.STATE_INACTIVE)
  }

  public actual fun resume() {
    impl.setDownloadState(OfflineRegion.STATE_ACTIVE)
  }

  public actual suspend fun updateMetadata(metadata: ByteArray): Unit =
    suspendCoroutine { continuation ->
      impl.updateMetadata(
        metadata,
        object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
          override fun onUpdate(metadata: ByteArray) {
            metadataState.value = metadata
            continuation.resume(Unit)
          }

          override fun onError(error: String) =
            continuation.resumeWithException(OfflineTilesManagerException(error))
        },
      )
    }

  override fun equals(other: Any?): Boolean = other is OfflineTilePack && other.impl == impl

  override fun hashCode(): Int = impl.hashCode()
}
