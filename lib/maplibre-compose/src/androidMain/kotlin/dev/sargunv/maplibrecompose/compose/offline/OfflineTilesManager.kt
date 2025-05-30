package dev.sargunv.maplibrecompose.compose.offline

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion

@Composable
public actual fun rememberOfflineManager():
  dev.sargunv.maplibrecompose.compose.offline.OfflineManager {
  val context = LocalContext.current
  return remember(context) { AndroidOfflineManager.getInstance(context) }
}

/**
 * Acquire an instance of [OfflineManager] outside a Composition. For use in Composable code, see
 * [rememberOfflineManager].
 */
public fun getOfflineManager(
  context: Context
): dev.sargunv.maplibrecompose.compose.offline.OfflineManager =
  AndroidOfflineManager.getInstance(context)

internal class AndroidOfflineManager(private val context: Context) :
  dev.sargunv.maplibrecompose.compose.offline.OfflineManager {
  companion object {
    private val managers = mutableMapOf<Context, AndroidOfflineManager>()

    internal fun getInstance(context: Context): AndroidOfflineManager =
      managers.getOrPut(context) { AndroidOfflineManager(context) }
  }

  private val impl = OfflineManager.getInstance(context)

  private val regionsState = mutableStateOf(emptySet<OfflinePack>())

  override val regions
    get() = regionsState.value

  init {
    impl.listOfflineRegions(
      object : OfflineManager.ListOfflineRegionsCallback {
        override fun onList(offlineRegions: Array<OfflineRegion>?) {
          regionsState.value = offlineRegions.orEmpty().map { it.toOfflinePack() }.toSet()
        }

        override fun onError(error: String) = throw OfflineManagerException(error)
      }
    )
  }

  override suspend fun create(definition: OfflinePackDefinition, metadata: ByteArray): OfflinePack =
    suspendCoroutine { continuation ->
        impl.createOfflineRegion(
          definition =
            definition.toMLNOfflineRegionDefinition(context.resources.displayMetrics.density),
          metadata = metadata,
          callback =
            object : OfflineManager.CreateOfflineRegionCallback {
              override fun onCreate(offlineRegion: OfflineRegion) {
                continuation.resume(offlineRegion.toOfflinePack())
              }

              override fun onError(error: String) =
                continuation.resumeWithException(OfflineManagerException(error))
            },
        )
      }
      .also { regionsState.value += it }

  override suspend fun delete(pack: OfflinePack): Unit =
    suspendCoroutine { continuation ->
        pack.impl.delete(
          object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
              continuation.resume(Unit)
            }

            override fun onError(error: String) =
              continuation.resumeWithException(OfflineManagerException(error))
          }
        )
      }
      .also { regionsState.value -= pack }

  override suspend fun invalidate(pack: OfflinePack) = suspendCoroutine { continuation ->
    pack.impl.invalidate(
      object : OfflineRegion.OfflineRegionInvalidateCallback {
        override fun onInvalidate() = continuation.resume(Unit)

        override fun onError(error: String) =
          continuation.resumeWithException(OfflineManagerException(error))
      }
    )
  }

  override suspend fun invalidateAmbientCache() = suspendCoroutine { continuation ->
    impl.invalidateAmbientCache(
      object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineManagerException(message))
      }
    )
  }

  override suspend fun clearAmbientCache() = suspendCoroutine { continuation ->
    impl.clearAmbientCache(
      object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineManagerException(message))
      }
    )
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) = suspendCoroutine { continuation ->
    impl.setMaximumAmbientCacheSize(
      size,
      object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineManagerException(message))
      },
    )
  }

  override fun setTileCountLimit(limit: Long) {
    impl.setOfflineMapboxTileCountLimit(limit)
  }
}
