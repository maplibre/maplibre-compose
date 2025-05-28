package dev.sargunv.maplibrecompose.compose.offline

import androidx.compose.runtime.Composable
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Geometry

@Composable public expect fun rememberOfflineRegionManager(): OfflineRegionManager

public interface OfflineRegionManager {
  public suspend fun createOfflineRegion(
    definition: OfflineRegionDefinition,
    metadata: ByteArray = ByteArray(0),
  ): OfflineRegion

  // TODO refactor as State?
  public suspend fun listOfflineRegions(): List<OfflineRegion>
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
  public val id: Long
  public val definition: OfflineRegionDefinition
  public val metadata: ByteArray?

  public fun setDownloadState(downloadState: DownloadState)

  public suspend fun delete()

  // TODO refactor as State?
  public suspend fun getStatus(): OfflineRegionStatus?

  public suspend fun invalidate()

  public suspend fun updateMetadata(metadata: ByteArray)
}

public data class OfflineRegionStatus(
  val completedResourceCount: Long,
  val completedResourceSize: Long,
  val completedTileCount: Long,
  val completedTileSize: Long,
  val downloadState: DownloadState,
  val isComplete: Boolean,
  val isRequiredResourceCountPrecise: Boolean,
  val requiredResourceCount: Long,
)

public enum class DownloadState {
  Active,
  Inactive,
}

public class OfflineRegionException(message: String) : Exception(message)
