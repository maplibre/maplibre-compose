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
public actual fun rememberOfflineTilesManager(): OfflineTilesManager {
  val context = LocalContext.current
  return remember(context) { AndroidOfflineTilesManager.getInstance(context) }
}

public fun getOfflineTilesManager(context: Context): OfflineTilesManager =
  AndroidOfflineTilesManager.getInstance(context)

internal class AndroidOfflineTilesManager(private val context: Context) : OfflineTilesManager {
  companion object {
    private val managers = mutableMapOf<Context, AndroidOfflineTilesManager>()

    internal fun getInstance(context: Context): AndroidOfflineTilesManager =
      managers.getOrPut(context) { AndroidOfflineTilesManager(context) }
  }

  private val impl = OfflineManager.getInstance(context)

  private val regionsState = mutableStateOf(emptySet<OfflineTilePack>())

  override val regions
    get() = regionsState.value

  init {
    impl.listOfflineRegions(
      object : OfflineManager.ListOfflineRegionsCallback {
        override fun onList(offlineRegions: Array<OfflineRegion>?) {
          regionsState.value = offlineRegions.orEmpty().map { it.toOfflineTilePack() }.toSet()
        }

        override fun onError(error: String) = throw OfflineTilesManagerException(error)
      }
    )
  }

  override suspend fun create(
    definition: TilePackDefinition,
    metadata: ByteArray,
  ): OfflineTilePack =
    suspendCoroutine { continuation ->
        impl.createOfflineRegion(
          definition =
            definition.toMLNOfflineRegionDefinition(context.resources.displayMetrics.density),
          metadata = metadata,
          callback =
            object : OfflineManager.CreateOfflineRegionCallback {
              override fun onCreate(offlineRegion: OfflineRegion) {
                continuation.resume(offlineRegion.toOfflineTilePack())
              }

              override fun onError(error: String) =
                continuation.resumeWithException(OfflineTilesManagerException(error))
            },
        )
      }
      .also { regionsState.value += it }

  override suspend fun delete(region: OfflineTilePack): Unit =
    suspendCoroutine { continuation ->
        region.impl.delete(
          object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
              continuation.resume(Unit)
            }

            override fun onError(error: String) =
              continuation.resumeWithException(OfflineTilesManagerException(error))
          }
        )
      }
      .also { regionsState.value -= region }

  override suspend fun invalidate(region: OfflineTilePack) = suspendCoroutine { continuation ->
    region.impl.invalidate(
      object : OfflineRegion.OfflineRegionInvalidateCallback {
        override fun onInvalidate() = continuation.resume(Unit)

        override fun onError(error: String) =
          continuation.resumeWithException(OfflineTilesManagerException(error))
      }
    )
  }

  override suspend fun invalidateAmbientCache() = suspendCoroutine { continuation ->
    impl.invalidateAmbientCache(
      object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineTilesManagerException(message))
      }
    )
  }

  override suspend fun clearAmbientCache() = suspendCoroutine { continuation ->
    impl.clearAmbientCache(
      object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineTilesManagerException(message))
      }
    )
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) = suspendCoroutine { continuation ->
    impl.setMaximumAmbientCacheSize(
      size,
      object : OfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineTilesManagerException(message))
      },
    )
  }

  override fun setOfflineTileCountLimit(limit: Long) {
    impl.setOfflineMapboxTileCountLimit(limit)
  }
}
