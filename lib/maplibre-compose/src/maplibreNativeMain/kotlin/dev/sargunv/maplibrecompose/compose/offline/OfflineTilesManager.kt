package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.Composable

/** Acquire an instance of [OfflineTilesManager]. */
@Composable public expect fun rememberOfflineTilesManager(): OfflineTilesManager

/**
 * An instance of this interface is a singleton that manages offline packs and ambient caching.
 *
 * An offline tile pack represents a collection of resources needed to display a region offline,
 * including map tiles, styles, and other assets. It allows you to selectively download regions of
 * the map to be made available offline.
 *
 * The ambient cache is a temporary storage mechanism used to improve map loading performance and
 * reduce network requests. It caches map tiles and other resources that the map renders, allowing
 * them to be retrieved faster on subsequent map views or when zooming into previously viewed areas.
 * The ambient cache is distinct from offline packs, which are used for persistent offline access.
 */
public interface OfflineTilesManager {

  /** A list of all known offline tile packs. Backed by [androidx.compose.runtime.State]. */
  public val regions: Set<OfflineTilePack>

  /**
   * Creates and registers an offline tile pack that downloads the resources needed to use the given
   * region offline.
   */
  public suspend fun create(
    definition: TilePackDefinition,
    metadata: ByteArray = ByteArray(0),
  ): OfflineTilePack

  /**
   * Unregisters the given offline tile pack and allows resources that are no longer required by any
   * remaining packs to be freed.
   */
  public suspend fun delete(region: OfflineTilePack)

  /**
   * Invalidates the specified offline tile pack. This method checks that the tiles in the specified
   * pack match those from the server. Local tiles that do not match the latest version on the
   * server are updated.
   */
  public suspend fun invalidate(region: OfflineTilePack)

  /**
   * Invalidates the ambient cache. This method checks that the tiles in the ambient cache match
   * those from the server. If the local tiles do not match those on the server, they are
   * re-downloaded.
   */
  public suspend fun invalidateAmbientCache()

  /**
   * Clears the ambient cache by deleting resources. This method does not affect resources shared
   * with offline regions.
   */
  public suspend fun clearAmbientCache()

  /**
   * Sets the maximum ambient cache size in bytes. The default maximum cache size is 50 MB. To
   * disable ambient caching, set the maximum ambient cache size to 0. Setting the maximum ambient
   * cache size does not impact the maximum size of offline tile packs.
   */
  public suspend fun setMaximumAmbientCacheSize(size: Long)

  /**
   * Sets the maximum number of tiles that may be downloaded and stored on the current device. By
   * default, the limit is set to 6000.
   */
  public fun setOfflineTileCountLimit(limit: Long)
}
