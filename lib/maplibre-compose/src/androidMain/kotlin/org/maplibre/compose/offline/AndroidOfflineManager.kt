package org.maplibre.compose.offline

import android.content.Context
import androidx.annotation.UiThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager as MLNOfflineManager
import org.maplibre.android.offline.OfflineRegion

@Composable
public actual fun rememberOfflineManager(): OfflineManager {
  val context = LocalContext.current
  return remember(context) { getOfflineManager(context) }
}

/**
 * Acquire an instance of [OfflineManager] outside a Composition. For use in Composable code, see
 * [org.maplibre.compose.offline.rememberOfflineManager].
 *
 * The first time, it should be called from the application's UI thread.
 */
public fun getOfflineManager(context: Context): OfflineManager =
  AndroidOfflineManager.getInstance(context)

internal class AndroidOfflineManager @UiThread internal constructor(context: Context) :
  OfflineManager {
  companion object {
    private var manager: AndroidOfflineManager? = null

    fun getInstance(context: Context): AndroidOfflineManager =
      manager ?: AndroidOfflineManager(context.applicationContext).also { manager = it }
  }

  private val pixelRatio = context.resources.displayMetrics.density

  private val impl =
    {
      MapLibre.getInstance(context) // must be called before getting OfflineManager instance
      MLNOfflineManager.getInstance(context)
    }()

  private val packsState = mutableStateOf(emptySet<OfflinePack>())

  override val packs
    get() = packsState.value

  init {
    impl.listOfflineRegions(
      object : MLNOfflineManager.ListOfflineRegionsCallback {
        override fun onList(offlineRegions: Array<OfflineRegion>?) {
          packsState.value = offlineRegions.orEmpty().map { OfflinePack.getInstance(it) }.toSet()
        }

        override fun onError(error: String) = throw OfflineManagerException(error)
      }
    )
  }

  override suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray,
  ): org.maplibre.compose.offline.OfflinePack =
    suspendCoroutine { continuation ->
        impl.createOfflineRegion(
          definition = definition.toMLNOfflineRegionDefinition(pixelRatio),
          metadata = metadata,
          callback =
            object : MLNOfflineManager.CreateOfflineRegionCallback {
              override fun onCreate(offlineRegion: OfflineRegion) {
                continuation.resume(OfflinePack.getInstance(offlineRegion))
              }

              override fun onError(error: String) =
                continuation.resumeWithException(OfflineManagerException(error))
            },
        )
      }
      .also { packsState.value += it }

  override fun resume(pack: org.maplibre.compose.offline.OfflinePack) =
    pack.impl.setDownloadState(OfflineRegion.STATE_ACTIVE)

  override fun pause(pack: org.maplibre.compose.offline.OfflinePack) =
    pack.impl.setDownloadState(OfflineRegion.STATE_INACTIVE)

  override suspend fun delete(pack: org.maplibre.compose.offline.OfflinePack): Unit =
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
      .also {
        packsState.value -= pack
        OfflinePack.dispose(pack)
      }

  override suspend fun invalidate(pack: org.maplibre.compose.offline.OfflinePack) =
    suspendCoroutine { continuation ->
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
      object : MLNOfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineManagerException(message))
      }
    )
  }

  override suspend fun clearAmbientCache() = suspendCoroutine { continuation ->
    impl.clearAmbientCache(
      object : MLNOfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineManagerException(message))
      }
    )
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) = suspendCoroutine { continuation ->
    impl.setMaximumAmbientCacheSize(
      size,
      object : MLNOfflineManager.FileSourceCallback {
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
