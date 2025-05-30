package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.mutableStateOf
import cocoapods.MapLibre.MLNOfflinePack
import dev.sargunv.maplibrecompose.core.util.toByteArray
import dev.sargunv.maplibrecompose.core.util.toNSData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.useContents

public actual class OfflineTilePack internal constructor(internal val impl: MLNOfflinePack) {

  public actual val definition: TilePackDefinition
    get() = impl.region.toTilePackDefinition()

  private val metadataState = mutableStateOf(impl.context.toByteArray())

  internal val progressState =
    mutableStateOf(impl.progress.useContents { toDownloadProgress(impl.state) })

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val progress: DownloadProgress
    get() = progressState.value

  init {
    impl.requestProgress()
  }

  public actual fun resume() {
    impl.resume()
  }

  public actual fun suspend() {
    impl.suspend()
  }

  public actual suspend fun updateMetadata(metadata: ByteArray): Unit =
    suspendCoroutine { continuation ->
      impl.setContext(metadata.toNSData()) { error ->
        if (error != null) continuation.resumeWithException(error.toOfflineRegionException())
        else {
          metadataState.value = metadata
          continuation.resume(Unit)
        }
      }
    }

  override fun equals(other: Any?): Boolean = other is OfflineTilePack && other.impl == impl

  override fun hashCode(): Int = impl.hashCode()
}
