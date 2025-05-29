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
  return remember(context) { AndroidOfflineRegionManager(context, coroutineScope) }
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
}
