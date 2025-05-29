package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.Composable
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Geometry

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

public sealed interface OfflineRegionDefinition {
  public val styleUrl: String
  public val minZoom: Int
  public val maxZoom: Int?
  public val pixelRatio: Float
  public val includeIdeographs: Boolean

  public data class TilePyramid(
    override val styleUrl: String,
    public val bounds: BoundingBox,
    override val minZoom: Int = 0,
    override val maxZoom: Int? = null,
    override val pixelRatio: Float,
    override val includeIdeographs: Boolean = true,
  ) : OfflineRegionDefinition

  public data class Shape(
    override val styleUrl: String,
    public val geometry: Geometry,
    override val minZoom: Int = 0,
    override val maxZoom: Int? = null,
    override val pixelRatio: Float,
    override val includeIdeographs: Boolean = true,
  ) : OfflineRegionDefinition
}

public expect class OfflineRegion {
  public val definition: OfflineRegionDefinition
  public val metadata: ByteArray?
  public val status: OfflineRegionStatus?

  public fun setDownloadState(downloadState: DownloadState)

  public suspend fun updateMetadata(metadata: ByteArray)
}

public sealed interface OfflineRegionStatus {
  public data class Normal(
    val completedResourceCount: Long,
    val completedResourceSize: Long,
    val completedTileCount: Long,
    val completedTileSize: Long,
    val downloadState: DownloadState,
    val isComplete: Boolean,
    val isRequiredResourceCountPrecise: Boolean,
    val requiredResourceCount: Long,
  ) : OfflineRegionStatus

  public data class Error(val reason: String, val message: String) : OfflineRegionStatus

  public data class TileLimitExceeded(val limit: Long) : OfflineRegionStatus
}

public enum class DownloadState {
  Active,
  Inactive,
}

public class OfflineRegionException(message: String) : Exception(message)
