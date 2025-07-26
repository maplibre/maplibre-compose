package org.maplibre.compose.offline

import androidx.compose.runtime.Composable

/** Acquire an instance of [OfflineManager]. */
@Composable public expect fun rememberOfflineManager(): OfflineManager

/**
 * An instance of this interface is a singleton that manages offline packs and ambient caching.
 *
 * An offline pack represents a collection of resources needed to display a region offline,
 * including map tiles, styles, and other assets. It allows you to selectively download regions of
 * the map to be made available offline.
 *
 * The ambient cache is a temporary storage mechanism used to improve map loading performance and
 * reduce network requests. It caches map tiles and other resources that the map renders, allowing
 * them to be retrieved faster on subsequent map views or when zooming into previously viewed areas.
 * The ambient cache is distinct from offline packs, which are used for persistent offline access.
 */
public interface OfflineManager {

  /** A list of all known offline packs. Backed by [androidx.compose.runtime.State]. */
  public val packs: Set<OfflinePack>

  /**
   * Creates and registers an offline pack that downloads the resources needed to use the given
   * region offline. The pack starts paused; to actually start the download, call [resume].
   *
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray = ByteArray(0),
  ): OfflinePack

  /**
   * Resume downloading if the pack is paused. Must be called after [create] to start downloading.
   */
  public fun resume(pack: OfflinePack)

  /** Pause downloading if the pack is downloading. */
  public fun pause(pack: OfflinePack)

  /**
   * Unregisters the given offline pack and allows resources that are no longer required by any
   * remaining packs to be freed.
   *
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun delete(pack: OfflinePack)

  /**
   * Invalidates the specified offline pack. This method checks that the tiles in the specified pack
   * match those from the server. Local tiles that do not match the latest version on the server are
   * updated.
   *
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun invalidate(pack: OfflinePack)

  /**
   * Invalidates the ambient cache. This method checks that the tiles in the ambient cache match
   * those from the server. If the local tiles do not match those on the server, they are
   * re-downloaded.
   *
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun invalidateAmbientCache()

  /**
   * Clears the ambient cache by deleting resources. This method does not affect resources shared
   * with offline regions.
   *
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun clearAmbientCache()

  /**
   * Sets the maximum ambient cache size in bytes. The default maximum cache size is 50 MB. To
   * disable ambient caching, set the maximum ambient cache size to 0. Setting the maximum ambient
   * cache size does not impact the maximum size of offline packs.
   *
   * @throws [OfflineManagerException] if the operation failed.
   */
  public suspend fun setMaximumAmbientCacheSize(size: Long)

  /**
   * Sets the maximum number of tiles that may be downloaded and stored on the current device. By
   * default, the limit is set to 6000.
   */
  public fun setTileCountLimit(limit: Long)
}
