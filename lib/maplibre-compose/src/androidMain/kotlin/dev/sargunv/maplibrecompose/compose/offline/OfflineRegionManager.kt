package dev.sargunv.maplibrecompose.compose.offline

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.android.offline.OfflineManager as MlnOfflineManager
import org.maplibre.android.offline.OfflineRegion as MlnOfflineRegion

@Composable
public actual fun rememberOfflineRegionManager(): OfflineRegionManager {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  return remember(context, coroutineScope) {
    AndroidOfflineRegionManagers.getForContext(context, coroutineScope)
  }
}

/** Helper singleton to ensure there's only one manager for a given context. */
internal object AndroidOfflineRegionManagers {
  private val managers = mutableMapOf<Context, AndroidOfflineRegionManager>()

  internal fun getForContext(
    context: Context,
    coroutineScope: CoroutineScope,
  ): AndroidOfflineRegionManager =
    managers.getOrPut(context) { AndroidOfflineRegionManager(context, coroutineScope) }
}

internal class AndroidOfflineRegionManager(context: Context, coroutineScope: CoroutineScope) :
  OfflineRegionManager {

  private val impl = MlnOfflineManager.getInstance(context)

  private val regionsState = mutableStateOf(emptySet<OfflineRegion>())

  override val regions
    get() = regionsState.value

  init {
    coroutineScope.launch { updateRegions() }
  }

  private suspend fun updateRegions() = suspendCoroutine { continuation ->
    impl.listOfflineRegions(
      object : MlnOfflineManager.ListOfflineRegionsCallback {
        override fun onList(offlineRegions: Array<MlnOfflineRegion>?) {
          regionsState.value = offlineRegions.orEmpty().map { it.toOfflineRegion() }.toSet()
          continuation.resume(Unit)
        }

        override fun onError(error: String) =
          continuation.resumeWithException(OfflineRegionException(error))
      }
    )
  }

  override suspend fun create(
    definition: OfflineRegionDefinition,
    metadata: ByteArray,
  ): OfflineRegion =
    suspendCoroutine { continuation ->
        impl.createOfflineRegion(
          definition = definition.toMlnOfflineRegionDefinition(),
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
      .also { updateRegions() }

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
      .also { updateRegions() }

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
