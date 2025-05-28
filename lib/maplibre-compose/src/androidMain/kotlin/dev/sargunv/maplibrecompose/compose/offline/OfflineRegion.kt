package dev.sargunv.maplibrecompose.compose.offline

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.maplibre.android.offline.OfflineRegion as MlnOfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus as MlnOfflineRegionStatus

public actual class OfflineRegion internal constructor(private val impl: MlnOfflineRegion) {
  public actual val id: Long
    get() = impl.id

  public actual val definition: OfflineRegionDefinition
    get() = impl.definition.toRegionDefinition()

  public actual val metadata: ByteArray?
    get() = impl.metadata

  public actual fun setDownloadState(downloadState: DownloadState): Unit =
    impl.setDownloadState(
      when (downloadState) {
        DownloadState.Active -> MlnOfflineRegion.STATE_ACTIVE
        DownloadState.Inactive -> MlnOfflineRegion.STATE_INACTIVE
      }
    )

  public actual suspend fun delete(): Unit = suspendCoroutine { continuation ->
    impl.delete(
      object : MlnOfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = continuation.resume(Unit)

        override fun onError(error: String) =
          continuation.resumeWithException(OfflineRegionException(error))
      }
    )
  }

  public actual suspend fun getStatus(): OfflineRegionStatus? = suspendCoroutine { continuation ->
    impl.getStatus(
      object : MlnOfflineRegion.OfflineRegionStatusCallback {
        override fun onStatus(status: MlnOfflineRegionStatus?) =
          continuation.resume(status?.toOfflineRegionStatus())

        override fun onError(error: String?) =
          continuation.resumeWithException(OfflineRegionException(error ?: "Unknown error"))
      }
    )
  }

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
          override fun onUpdate(metadata: ByteArray) = continuation.resume(Unit)

          override fun onError(error: String) =
            continuation.resumeWithException(OfflineRegionException(error))
        },
      )
    }

  override fun equals(other: Any?): Boolean = other is OfflineRegion && other.impl == impl

  override fun hashCode(): Int = impl.hashCode()
}
