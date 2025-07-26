package org.maplibre.compose.offline

import MapLibre.MLNOfflinePack
import androidx.compose.runtime.mutableStateOf
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.useContents
import org.maplibre.compose.util.toByteArray
import org.maplibre.compose.util.toNSData

public actual class OfflinePack private constructor(internal val impl: MLNOfflinePack) {
  internal companion object {
    // there must only be one OfflinePack instance per MLNOfflinePack, because only the newest will
    // receive updates to its State
    private val instances = mutableMapOf<MLNOfflinePack, OfflinePack>()

    internal fun getInstance(region: MLNOfflinePack): OfflinePack {
      return instances.getOrPut(region) { OfflinePack(region) }
    }

    internal fun dispose(pack: OfflinePack) {
      instances.remove(pack.impl)
    }
  }

  public actual val definition: OfflinePackDefinition
    get() = impl.region.toOfflinePackDefinition()

  private val metadataState = mutableStateOf(impl.context.toByteArray())

  internal val progressState =
    mutableStateOf(impl.progress.useContents { toDownloadProgress(impl.state) })

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val downloadProgress: DownloadProgress
    get() = progressState.value

  init {
    impl.requestProgress()
  }

  public actual suspend fun setMetadata(metadata: ByteArray): Unit =
    suspendCoroutine { continuation ->
      impl.setContext(metadata.toNSData()) { error ->
        if (error != null) continuation.resumeWithException(error.toOfflineManagerException())
        else {
          metadataState.value = metadata
          continuation.resume(Unit)
        }
      }
    }

  override fun equals(other: Any?): Boolean = other is OfflinePack && other.impl == impl

  override fun hashCode(): Int = impl.hashCode()
}
