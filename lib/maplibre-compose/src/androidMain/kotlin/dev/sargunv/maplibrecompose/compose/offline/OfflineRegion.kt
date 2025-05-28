package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.mutableStateOf
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.maplibre.android.offline.OfflineRegion as MlnOfflineRegion
import org.maplibre.android.offline.OfflineRegionError as MlnOfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus as MlnOfflineRegionStatus

public actual class OfflineRegion internal constructor(internal val impl: MlnOfflineRegion) :
  MlnOfflineRegion.OfflineRegionObserver {
  public actual val definition: OfflineRegionDefinition
    get() = impl.definition.toRegionDefinition()

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
      object : MlnOfflineRegion.OfflineRegionStatusCallback {
        override fun onStatus(status: MlnOfflineRegionStatus?) {
          progressState.value = status?.toDownloadProgress() ?: DownloadProgress.Unknown
        }

        override fun onError(error: String?) =
          throw OfflineRegionException(error ?: "Unknown error")
      }
    )
  }

  override fun onStatusChanged(status: MlnOfflineRegionStatus) {
    progressState.value = status.toDownloadProgress()
  }

  override fun onError(error: MlnOfflineRegionError) {
    progressState.value = DownloadProgress.Error(error.reason, error.message)
  }

  override fun mapboxTileCountLimitExceeded(limit: Long) {
    progressState.value = DownloadProgress.TileLimitExceeded(limit)
  }

  public actual fun suspend() {
    impl.setDownloadState(MlnOfflineRegion.STATE_INACTIVE)
  }

  public actual fun resume() {
    impl.setDownloadState(MlnOfflineRegion.STATE_ACTIVE)
  }

  public actual suspend fun updateMetadata(metadata: ByteArray): Unit =
    suspendCoroutine { continuation ->
      impl.updateMetadata(
        metadata,
        object : MlnOfflineRegion.OfflineRegionUpdateMetadataCallback {
          override fun onUpdate(metadata: ByteArray) {
            metadataState.value = metadata
            continuation.resume(Unit)
          }

          override fun onError(error: String) =
            continuation.resumeWithException(OfflineRegionException(error))
        },
      )
    }

  override fun equals(other: Any?): Boolean = other is OfflineRegion && other.impl == impl

  override fun hashCode(): Int = impl.hashCode()
}
