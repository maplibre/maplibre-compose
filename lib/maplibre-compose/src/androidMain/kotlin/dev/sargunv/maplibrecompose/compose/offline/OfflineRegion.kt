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

  private val statusState = mutableStateOf<OfflineRegionStatus?>(null)

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val status: OfflineRegionStatus?
    get() = statusState.value

  init {
    impl.setDeliverInactiveMessages(true)
    impl.setObserver(this)
  }

  override fun onStatusChanged(status: MlnOfflineRegionStatus) {
    statusState.value = status.toOfflineRegionStatus()
  }

  override fun onError(error: MlnOfflineRegionError) {
    statusState.value = OfflineRegionStatus.Error(error.reason, error.message)
  }

  override fun mapboxTileCountLimitExceeded(limit: Long) {
    statusState.value = OfflineRegionStatus.TileLimitExceeded(limit)
  }

  public actual fun setDownloadState(downloadState: DownloadState): Unit =
    impl.setDownloadState(
      when (downloadState) {
        DownloadState.Active -> MlnOfflineRegion.STATE_ACTIVE
        DownloadState.Inactive -> MlnOfflineRegion.STATE_INACTIVE
      }
    )

  public actual suspend fun invalidate(): Unit = suspendCoroutine { continuation ->
    impl.invalidate(
      object : MlnOfflineRegion.OfflineRegionInvalidateCallback {
        override fun onInvalidate() = continuation.resume(Unit)

        override fun onError(error: String) =
          continuation.resumeWithException(OfflineRegionException(error))
      }
    )
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
