package org.maplibre.compose.offline

/** Reports the current download state of one [OfflinePack]. */
public sealed interface DownloadProgress {
  /** The SDK has not reported the download progress. */
  public data object Unknown : DownloadProgress

  /** The download is in a known state. It can be progressing, paused, or complete. */
  public data class Healthy(
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

  /** The download has failed. */
  public data class Error(public val reason: String, public val message: String) : DownloadProgress

  /** The download exceeded the maximum number of offline tiles. */
  public data class TileLimitExceeded(public val limit: Long) : DownloadProgress
}
