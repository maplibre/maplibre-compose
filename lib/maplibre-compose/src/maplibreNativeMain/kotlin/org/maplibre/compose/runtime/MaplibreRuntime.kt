package org.maplibre.compose.runtime

import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.ensureMlnFfiConfigured
import org.maplibre.compose.offline.MlnFfiOfflineManager
import org.maplibre.compose.offline.OfflineManagerException
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.OfflinePackDefinition

/**
 * The application-scoped MapLibre runtime: the tile cache and the offline work that outlive any one
 * map. Every map in this process shares it.
 *
 * An offline pack is a downloaded collection of the resources that display a region offline: map
 * tiles, the style, and other assets. The ambient cache stores resources that maps render online,
 * so revisited areas load without a network request. The two are separate: clearing the ambient
 * cache leaves offline packs intact.
 *
 * The platform `MapLibre.configure` function sets the cache location and cache budget before the
 * first use; without it, the first member access installs the platform default configuration. On
 * Android the default configuration needs the application context, so a member access before
 * `MapLibre.configure(context)` and before the first composed map fails with
 * [IllegalStateException].
 */
public object MaplibreRuntime {

  private val offline: MlnFfiOfflineManager
    get() {
      ensureMlnFfiConfigured()
      return MlnFfiApplication.offlineManager
    }

  /** All known offline packs. Backed by [androidx.compose.runtime.State]. */
  public val offlinePacks: Set<OfflinePack>
    get() = offline.packs

  /**
   * Creates and registers an offline pack that downloads the resources for the given region. The
   * pack starts paused; call [resume] to start the download.
   *
   * Raster tiles render at a pixel ratio of 1 unless [OfflinePackDefinition] carries the density of
   * the window that shows the map.
   *
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun createOfflinePack(
    definition: OfflinePackDefinition,
    metadata: ByteArray = ByteArray(0),
  ): OfflinePack = offline.create(definition, metadata)

  /** Resumes downloading if the pack is paused. A pack created by [createOfflinePack] is paused. */
  public fun resume(pack: OfflinePack) {
    offline.resume(pack)
  }

  /** Pauses downloading if the pack is downloading. */
  public fun pause(pack: OfflinePack) {
    offline.pause(pack)
  }

  /**
   * Unregisters the given offline pack and frees the resources that no remaining pack requires.
   *
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun delete(pack: OfflinePack) {
    offline.delete(pack)
  }

  /**
   * Compares the tiles in the given offline pack with the server's and re-downloads the ones that
   * changed.
   *
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun invalidate(pack: OfflinePack) {
    offline.invalidate(pack)
  }

  /**
   * Compares the tiles in the ambient cache with the server's and re-downloads the ones that
   * changed.
   *
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun invalidateAmbientCache() {
    offline.invalidateAmbientCache()
  }

  /**
   * Deletes the resources in the ambient cache. Resources shared with offline packs are kept.
   *
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun clearAmbientCache() {
    offline.clearAmbientCache()
  }

  /**
   * Sets the maximum ambient cache size in bytes. The default is 50 MB, and 0 disables ambient
   * caching. Offline packs are not limited by this size.
   *
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun setMaximumAmbientCacheSize(size: Long) {
    offline.setMaximumAmbientCacheSize(size)
  }
}
