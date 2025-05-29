package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.mutableStateOf
import cocoapods.MapLibre.MLNOfflinePack
import dev.sargunv.maplibrecompose.core.util.toByteArray
import dev.sargunv.maplibrecompose.core.util.toNSData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

public actual class OfflineRegion internal constructor(internal val impl: MLNOfflinePack) {

  public actual val definition: OfflineRegionDefinition
    get() = impl.region.toRegionDefinition()

  private val metadataState = mutableStateOf(impl.context.toByteArray())

  internal val statusState = mutableStateOf<OfflineRegionStatus?>(null)

  public actual val metadata: ByteArray?
    get() = metadataState.value

  public actual val status: OfflineRegionStatus?
    get() = statusState.value

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

  override fun equals(other: Any?): Boolean = other is OfflineRegion && other.impl == impl

  override fun hashCode(): Int = impl.hashCode()
}
