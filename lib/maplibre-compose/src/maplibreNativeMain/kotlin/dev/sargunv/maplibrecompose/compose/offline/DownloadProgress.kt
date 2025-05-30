package dev.sargunv.maplibrecompose.compose.offline

public sealed interface DownloadProgress {
  public data object Unknown : DownloadProgress

  public data class Healthy(
    val completedResourceCount: Long,
    val completedResourceBytes: Long,
    val completedTileCount: Long,
    val completedTileBytes: Long,
    val downloadStatus: DownloadStatus,
    val isRequiredResourceCountPrecise: Boolean,
    val requiredResourceCount: Long,
  ) : DownloadProgress

  public data class Error(val reason: String, val message: String) : DownloadProgress

  public data class TileLimitExceeded(val limit: Long) : DownloadProgress
}
