package org.maplibre.compose.offline

public sealed interface DownloadProgress {
  /** The download progress is unknown, typically because the SDK object has not initialized yet. */
  public data object Unknown : DownloadProgress

  /** The download is in a known, healthy state. It may be progressing, paused, or completed. */
  public data class Healthy(
    /**
     * The number of resources, including tiles, that have been completely downloaded and are ready
     * to use offline.
     */
    val completedResourceCount: Long,

    /**
     * The cumulative size of the downloaded resources on disk, including tiles, measured in bytes.
     */
    val completedResourceBytes: Long,

    /** The number of tiles that have been completely downloaded and are ready to use offline. */
    val completedTileCount: Long,

    /** The cumulative size of the downloaded tiles on disk, measured in bytes. */
    val completedTileBytes: Long,

    /** See [DownloadStatus]. */
    val status: DownloadStatus,

    /** Whether [requiredResourceCount] is an exact count or a lower bound. */
    val isRequiredResourceCountPrecise: Boolean,

    /**
     * The minimum number of resources that must be downloaded in order to view the pack’s full
     * region without any omissions.
     *
     * If [isRequiredResourceCountPrecise] is `false`, the true value may be greater.
     */
    val requiredResourceCount: Long,
  ) : DownloadProgress

  /** The download has failed. */
  public data class Error(val reason: String, val message: String) : DownloadProgress

  /**
   * The download has exceeded the maximum number of allowed offline tiles. See
   * [OfflineManager.setTileCountLimit].
   */
  public data class TileLimitExceeded(val limit: Long) : DownloadProgress
}
