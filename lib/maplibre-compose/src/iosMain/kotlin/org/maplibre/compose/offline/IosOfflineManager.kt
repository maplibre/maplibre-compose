package org.maplibre.compose.offline

import MapLibre.MLNOfflinePack
import MapLibre.MLNOfflinePackErrorNotification
import MapLibre.MLNOfflinePackMaximumMapboxTilesReachedNotification
import MapLibre.MLNOfflinePackProgressChangedNotification
import MapLibre.MLNOfflinePackUserInfoKeyError
import MapLibre.MLNOfflinePackUserInfoKeyMaximumCount
import MapLibre.MLNOfflineStorage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.useContents
import org.maplibre.compose.util.KVObserverProtocol
import org.maplibre.compose.util.toNSData
import platform.Foundation.NSError
import platform.Foundation.NSKeyValueObservingOptionInitial
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.addObserver
import platform.darwin.NSObject
import platform.darwin.sel_registerName

@Composable
public actual fun rememberOfflineManager(): OfflineManager = remember { getOfflineManager() }

/**
 * Acquire an instance of [OfflineManager] outside a Composition. For use in Composable code, see
 * [org.maplibre.compose.offline.rememberOfflineManager].
 */
public fun getOfflineManager(): OfflineManager = IosOfflineManager

internal object IosOfflineManager : OfflineManager {

  private val impl = MLNOfflineStorage.sharedOfflineStorage

  private val packsState = mutableStateOf(emptySet<OfflinePack>())

  override val packs
    get() = packsState.value

  // hold on to the objects to prevent ObjC weak references from losing them
  @Suppress("unused") private val progressObserver = OfflinePackProgressObserver()
  @Suppress("unused") private val packsListObserver = PacksListObserver()

  private class PacksListObserver() : NSObject(), KVObserverProtocol {
    private val packsContext = StableRef.create(Any()).asCPointer()

    init {
      impl.addObserver(
        this,
        "packs",
        NSKeyValueObservingOptionNew or NSKeyValueObservingOptionInitial,
        this.packsContext,
      )
    }

    override fun observeValueForKeyPath(
      keyPath: String?,
      ofObject: Any?,
      change: Map<Any?, *>?,
      context: CPointer<out CPointed>?,
    ) {
      when (context) {
        packsContext ->
          packsState.value =
            impl.packs.orEmpty().map { OfflinePack.getInstance(it as MLNOfflinePack) }.toSet()
      }
      // ignore other contexts
    }
  }

  private class OfflinePackProgressObserver : NSObject() {
    val packToProgress by derivedStateOf { packs.associate { it.impl to it.progressState } }

    init {
      val nc = NSNotificationCenter.defaultCenter
      nc.addObserver(
        observer = this,
        selector = sel_registerName(::offlinePackProgressDidChange.name + ":"),
        name = MLNOfflinePackProgressChangedNotification,
        `object` = null,
      )
      nc.addObserver(
        observer = this,
        selector = sel_registerName(::offlinePackDidReceiveError.name + ":"),
        name = MLNOfflinePackErrorNotification,
        `object` = null,
      )
      nc.addObserver(
        observer = this,
        selector = sel_registerName(::offlinePackDidReceiveMaximumAllowedMapboxTiles.name + ":"),
        name = MLNOfflinePackMaximumMapboxTilesReachedNotification,
        `object` = null,
      )
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun offlinePackProgressDidChange(notification: NSNotification) {
      val pack = notification.`object` as MLNOfflinePack
      packToProgress[pack]?.value = pack.progress.useContents { toDownloadProgress(pack.state) }
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun offlinePackDidReceiveError(notification: NSNotification) {
      val pack = notification.`object` as MLNOfflinePack
      val error = notification.userInfo!![MLNOfflinePackUserInfoKeyError] as NSError
      packToProgress[pack]?.value =
        DownloadProgress.Error(
          error.localizedFailureReason ?: "Unknown",
          error.localizedDescription,
        )
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun offlinePackDidReceiveMaximumAllowedMapboxTiles(notification: NSNotification) {
      val pack = notification.`object` as MLNOfflinePack
      val limit = notification.userInfo!![MLNOfflinePackUserInfoKeyMaximumCount] as ULong
      packToProgress[pack]?.value = DownloadProgress.TileLimitExceeded(limit.toLong())
    }
  }

  override suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray,
  ): org.maplibre.compose.offline.OfflinePack = suspendCoroutine { continuation ->
    impl.addPackForRegion(
      region = definition.toMLNOfflineRegion(),
      withContext = metadata.toNSData(),
      completionHandler = { pack, error ->
        if (error != null) continuation.resumeWithException(error.toOfflineManagerException())
        else if (pack != null) continuation.resume(OfflinePack.getInstance(pack))
        else continuation.resumeWithException(IllegalStateException("Offline pack is null"))
      },
    )
  }

  override fun resume(pack: org.maplibre.compose.offline.OfflinePack) = pack.impl.resume()

  override fun pause(pack: org.maplibre.compose.offline.OfflinePack) = pack.impl.suspend()

  override suspend fun delete(pack: org.maplibre.compose.offline.OfflinePack) =
    suspendCoroutine { continuation ->
      impl.removePack(pack.impl) { error ->
        if (error != null) continuation.resumeWithException(error.toOfflineManagerException())
        else continuation.resume(Unit).also { OfflinePack.dispose(pack) }
      }
    }

  override suspend fun invalidate(pack: org.maplibre.compose.offline.OfflinePack) =
    suspendCoroutine { continuation ->
      impl.invalidatePack(pack.impl) { error ->
        if (error != null) continuation.resumeWithException(error.toOfflineManagerException())
        else continuation.resume(Unit)
      }
    }

  override suspend fun invalidateAmbientCache() = suspendCoroutine { continuation ->
    impl.invalidateAmbientCacheWithCompletionHandler { error ->
      if (error != null) continuation.resumeWithException(error.toOfflineManagerException())
      else continuation.resume(Unit)
    }
  }

  override suspend fun clearAmbientCache() = suspendCoroutine { continuation ->
    impl.clearAmbientCacheWithCompletionHandler { error ->
      if (error != null) continuation.resumeWithException(error.toOfflineManagerException())
      else continuation.resume(Unit)
    }
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) = suspendCoroutine { continuation ->
    impl.setMaximumAmbientCacheSize(size.toULong()) { error ->
      if (error != null) continuation.resumeWithException(error.toOfflineManagerException())
      else continuation.resume(Unit)
    }
  }

  override fun setTileCountLimit(limit: Long) {
    impl.setMaximumAllowedMapboxTiles(limit.toULong())
  }
}
