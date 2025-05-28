package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.Composable

@Composable public expect fun rememberOfflineRegionManager(): OfflineRegionManager

public interface OfflineRegionManager {
  public val regions: Set<OfflineRegion>

  public suspend fun create(
    definition: OfflineRegionDefinition,
    metadata: ByteArray = ByteArray(0),
  ): OfflineRegion

  public suspend fun delete(region: OfflineRegion)

  public suspend fun invalidate(region: OfflineRegion)

  public suspend fun invalidateAmbientCache()

  public suspend fun clearAmbientCache()

  public suspend fun setMaximumAmbientCacheSize(size: Long)

  public fun setOfflineMapboxTileCountLimit(limit: Long)
}
