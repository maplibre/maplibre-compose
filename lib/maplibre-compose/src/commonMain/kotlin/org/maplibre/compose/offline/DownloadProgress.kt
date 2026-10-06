package org.maplibre.compose.offline

import androidx.compose.runtime.Immutable
import org.maplibre.compose.resource.MapResourceError

/**
 * Reports the current download state of one [OfflinePack].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface DownloadProgress {
  /** The SDK has not reported the download progress. */
  public data object NotReported : DownloadProgress

  /** The download is in a known state. It can be progressing, paused, or complete. */
  public data class Healthy
  internal constructor(
    /** The number of resources that have completed their downloads. */
    public val completedResourceCount: Long,
    /** The cumulative size of the downloaded resources in bytes. */
    public val completedResourceBytes: Long,
    /** The number of tiles that have completed their downloads. */
    public val completedTileCount: Long,
    /** The cumulative size of the downloaded tiles in bytes. */
    public val completedTileBytes: Long,
    /** The current download status. */
    public val status: DownloadStatus,
    /** Whether [requiredResourceCount] is exact instead of a lower bound. */
    public val isRequiredResourceCountPrecise: Boolean,
    /** The minimum resource count that is required to display the complete region. */
    public val requiredResourceCount: Long,
  ) : DownloadProgress

  /**
   * The download has failed.
   *
   * @property reason The cause of the failed resource load.
   * @property message A description of the failure.
   */
  public data class Error
  internal constructor(public val reason: MapResourceError, public val message: String) :
    DownloadProgress

  /**
   * The download exceeded the maximum number of offline tiles.
   *
   * @property limit The tile limit that the download reached.
   */
  public data class TileLimitExceeded internal constructor(public val limit: Long) :
    DownloadProgress
}

/**
 * Keeps [DownloadProgress] open: callers' `when` needs an `else` branch. The library never reports
 * it.
 */
internal data object UnspecifiedDownloadProgress : DownloadProgress
