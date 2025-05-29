package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import cocoapods.MapLibre.MLNOfflinePack
import cocoapods.MapLibre.MLNOfflineStorage
import dev.sargunv.maplibrecompose.core.util.KVObserverProtocol
import dev.sargunv.maplibrecompose.core.util.toNSData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import platform.Foundation.NSKeyValueChangeSetting
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.addObserver
import platform.darwin.NSObject

@Composable
public actual fun rememberOfflineRegionManager(): OfflineRegionManager {
  return IosOfflineRegionManager
}

internal object IosOfflineRegionManager : OfflineRegionManager, NSObject(), KVObserverProtocol {

  private val impl = MLNOfflineStorage.sharedOfflineStorage

  private val regionsState = mutableStateOf(emptySet<OfflineRegion>())

  override val regions
    get() = regionsState.value

  // TODO observe progress notifications and update region states:
  // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinepackprogresschangednotification
  // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinepackmaximummapboxtilesreachednotification
  // https://maplibre.org/maplibre-native/ios/latest/documentation/maplibre/mlnofflinepackerrornotification

  init {
    impl.addObserver(this, "packs", NSKeyValueObservingOptionNew, null)
  }

  override fun observeValueForKeyPath(
    keyPath: String?,
    ofObject: Any?,
    change: Map<Any?, *>?,
    context: kotlinx.cinterop.CPointer<out kotlinx.cinterop.CPointed>?,
  ) {
    if (
      ofObject == impl &&
        keyPath == "packs" &&
        change != null &&
        change.contains(NSKeyValueChangeSetting)
    ) {
      updateRegions()
    }
  }

  private fun updateRegions() {
    impl.packs?.let { packs ->
      regionsState.value = packs.map { (it as MLNOfflinePack).toOfflineRegion() }.toSet()
    }
  }

  override suspend fun create(
    definition: OfflineRegionDefinition,
    metadata: ByteArray,
  ): OfflineRegion =
    suspendCoroutine { continuation ->
        impl.addPackForRegion(
          region = definition.toMlnOfflineRegionDefinition(),
          withContext = metadata.toNSData(),
          completionHandler = { pack, error ->
            if (error != null) continuation.resumeWithException(error.toOfflineRegionException())
            else if (pack != null) continuation.resume(pack.toOfflineRegion())
            else continuation.resumeWithException(IllegalStateException("Offline pack is null"))
          },
        )
      }
      .also { updateRegions() }

  override suspend fun delete(region: OfflineRegion) =
    suspendCoroutine { continuation ->
        impl.removePack(region.impl) { error ->
          if (error != null) continuation.resumeWithException(error.toOfflineRegionException())
          else continuation.resume(Unit)
        }
      }
      .also { updateRegions() }

  override suspend fun invalidate(region: OfflineRegion) = suspendCoroutine { continuation ->
    impl.invalidatePack(region.impl) { error ->
      if (error != null) continuation.resumeWithException(error.toOfflineRegionException())
      else continuation.resume(Unit)
    }
  }

  override suspend fun invalidateAmbientCache() = suspendCoroutine { continuation ->
    impl.invalidateAmbientCacheWithCompletionHandler { error ->
      if (error != null) continuation.resumeWithException(error.toOfflineRegionException())
      else continuation.resume(Unit)
    }
  }

  override suspend fun clearAmbientCache() = suspendCoroutine { continuation ->
    impl.clearAmbientCacheWithCompletionHandler { error ->
      if (error != null) continuation.resumeWithException(error.toOfflineRegionException())
      else continuation.resume(Unit)
    }
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) = suspendCoroutine { continuation ->
    impl.setMaximumAmbientCacheSize(size.toULong()) { error ->
      if (error != null) continuation.resumeWithException(error.toOfflineRegionException())
      else continuation.resume(Unit)
    }
  }

  override fun setOfflineMapboxTileCountLimit(limit: Long) {
    impl.setMaximumAllowedMapboxTiles(limit.toULong())
  }
}
