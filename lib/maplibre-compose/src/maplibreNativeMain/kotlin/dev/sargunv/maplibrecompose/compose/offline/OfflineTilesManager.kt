package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.Composable

@Composable public expect fun rememberOfflineTilesManager(): OfflineTilesManager

public interface OfflineTilesManager {
  public val regions: Set<OfflineTilePack>

  public suspend fun create(
    definition: TilePackDefinition,
    metadata: ByteArray = ByteArray(0),
  ): OfflineTilePack

  public suspend fun delete(region: OfflineTilePack)

  public suspend fun invalidate(region: OfflineTilePack)

  public suspend fun invalidateAmbientCache()

  public suspend fun clearAmbientCache()

  public suspend fun setMaximumAmbientCacheSize(size: Long)

  public fun setOfflineTileCountLimit(limit: Long)
}
