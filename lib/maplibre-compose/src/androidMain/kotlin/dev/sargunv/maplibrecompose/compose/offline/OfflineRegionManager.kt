package dev.sargunv.maplibrecompose.compose.offline

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.maplibre.android.offline.OfflineManager as MlnOfflineManager
import org.maplibre.android.offline.OfflineRegion as MlnOfflineRegion

@Composable
public actual fun rememberOfflineRegionManager(): OfflineRegionManager {
  val context = LocalContext.current
  return remember(context) { AndroidOfflineRegionManager.getInstance(context) }
}

public fun getOfflineRegionManager(context: Context): OfflineRegionManager =
  AndroidOfflineRegionManager.getInstance(context)

internal class AndroidOfflineRegionManager(private val context: Context) : OfflineRegionManager {
  companion object {
    private val managers = mutableMapOf<Context, AndroidOfflineRegionManager>()

    internal fun getInstance(context: Context): AndroidOfflineRegionManager =
      managers.getOrPut(context) { AndroidOfflineRegionManager(context) }
  }

  private val impl = MlnOfflineManager.getInstance(context)

  private val regionsState = mutableStateOf(emptySet<OfflineRegion>())

  override val regions
    get() = regionsState.value

  init {
    impl.listOfflineRegions(
      object : MlnOfflineManager.ListOfflineRegionsCallback {
        override fun onList(offlineRegions: Array<MlnOfflineRegion>?) {
          regionsState.value = offlineRegions.orEmpty().map { it.toOfflineRegion() }.toSet()
        }

        override fun onError(error: String) = throw OfflineRegionException(error)
      }
    )
  }

  override suspend fun create(
    definition: OfflineRegionDefinition,
    metadata: ByteArray,
  ): OfflineRegion =
    suspendCoroutine { continuation ->
        impl.createOfflineRegion(
          definition =
            definition.toMlnOfflineRegionDefinition(context.resources.displayMetrics.density),
          metadata = metadata,
          callback =
            object : MlnOfflineManager.CreateOfflineRegionCallback {
              override fun onCreate(offlineRegion: MlnOfflineRegion) {
                continuation.resume(offlineRegion.toOfflineRegion())
              }

              override fun onError(error: String) =
                continuation.resumeWithException(OfflineRegionException(error))
            },
        )
      }
      .also { regionsState.value += it }

  override suspend fun delete(region: OfflineRegion): Unit =
    suspendCoroutine { continuation ->
        region.impl.delete(
          object : MlnOfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
              continuation.resume(Unit)
            }

            override fun onError(error: String) =
              continuation.resumeWithException(OfflineRegionException(error))
          }
        )
      }
      .also { regionsState.value -= region }

  override suspend fun invalidate(region: OfflineRegion) = suspendCoroutine { continuation ->
    region.impl.invalidate(
      object : MlnOfflineRegion.OfflineRegionInvalidateCallback {
        override fun onInvalidate() = continuation.resume(Unit)

        override fun onError(error: String) =
          continuation.resumeWithException(OfflineRegionException(error))
      }
    )
  }

  override suspend fun invalidateAmbientCache() = suspendCoroutine { continuation ->
    impl.invalidateAmbientCache(
      object : MlnOfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineRegionException(message))
      }
    )
  }

  override suspend fun clearAmbientCache() = suspendCoroutine { continuation ->
    impl.clearAmbientCache(
      object : MlnOfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineRegionException(message))
      }
    )
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) = suspendCoroutine { continuation ->
    impl.setMaximumAmbientCacheSize(
      size,
      object : MlnOfflineManager.FileSourceCallback {
        override fun onSuccess() = continuation.resume(Unit)

        override fun onError(message: String) =
          continuation.resumeWithException(OfflineRegionException(message))
      },
    )
  }

  override fun setOfflineMapboxTileCountLimit(limit: Long) {
    impl.setOfflineMapboxTileCountLimit(limit)
  }
}
