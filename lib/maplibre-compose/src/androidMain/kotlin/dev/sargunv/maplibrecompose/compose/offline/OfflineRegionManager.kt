package dev.sargunv.maplibrecompose.compose.offline

import android.content.Context
import androidx.compose.runtime.Composable
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
  return remember(context) { AndroidOfflineRegionManager(context) }
}

internal class AndroidOfflineRegionManager(context: Context) : OfflineRegionManager {

  private val impl = MlnOfflineManager.getInstance(context)

  override suspend fun createOfflineRegion(
    definition: OfflineRegionDefinition,
    metadata: ByteArray,
  ): OfflineRegion = suspendCoroutine { continuation ->
    impl.createOfflineRegion(
      definition = definition.toMlnOfflineRegionDefinition(),
      metadata = metadata,
      callback =
        object : MlnOfflineManager.CreateOfflineRegionCallback {
          override fun onCreate(offlineRegion: MlnOfflineRegion) =
            continuation.resume(offlineRegion.toOfflineRegion())

          override fun onError(error: String) =
            continuation.resumeWithException(OfflineRegionException(error))
        },
    )
  }

  override suspend fun listOfflineRegions(): List<OfflineRegion> =
    suspendCoroutine { continuation ->
      impl.listOfflineRegions(
        object : MlnOfflineManager.ListOfflineRegionsCallback {
          override fun onList(offlineRegions: Array<MlnOfflineRegion>?) =
            continuation.resume(offlineRegions?.map { it.toOfflineRegion() } ?: emptyList())

          override fun onError(error: String) =
            continuation.resumeWithException(OfflineRegionException(error))
        }
      )
    }
}
