package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflineManager
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.rememberOfflineManager
import org.maplibre.spatialk.geojson.BoundingBox

@Composable
actual fun rememberTilePrefetcher(): TilePrefetcher {
  val manager = rememberOfflineManager()
  return remember(manager) { OfflinePackPrefetcher(manager) }
}

actual val benchmarkPlatformLabel: String = nativeBenchmarkPlatformLabel()

internal expect fun nativeBenchmarkPlatformLabel(): String

private class OfflinePackPrefetcher(private val manager: OfflineManager) : TilePrefetcher {
  override val mode = "offline-pack"

  override suspend fun ensurePacked(
    scenarioId: String,
    styleUrl: String,
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    camera: CameraState,
    onStatus: (String) -> Unit,
  ) {
    manager.setTileCountLimit(50_000)
    val metadata = "bench:v1:$scenarioId".encodeToByteArray()
    val existing = manager.packs.firstOrNull { it.metadata?.contentEquals(metadata) == true }
    val pack =
      existing
        ?: manager.create(
          definition =
            OfflinePackDefinition.TilePyramid(
              styleUrl = styleUrl,
              bounds = bounds,
              minZoom = minZoom,
              maxZoom = maxZoom,
            ),
          metadata = metadata,
        )
    val alreadyDone =
      pack.downloadProgress.let { progress ->
        progress is DownloadProgress.Healthy && progress.status == DownloadStatus.Complete
      }
    if (alreadyDone) {
      onStatus("Tiles ready")
      return
    }
    manager.resume(pack)
    try {
      val terminal = snapshotFlow {
        pack.downloadProgress
      }
        .first { progress ->
          when (progress) {
            is DownloadProgress.Healthy -> {
              val total = progress.requiredResourceCount
              onStatus("Prefetching tiles ${progress.completedResourceCount}/$total")
              progress.status == DownloadStatus.Complete
            }
            is DownloadProgress.Error,
            is DownloadProgress.TileLimitExceeded -> true
            DownloadProgress.Unknown -> {
              onStatus("Prefetching tiles")
              false
            }
          }
        }
      when (terminal) {
        is DownloadProgress.Error -> error(terminal.message)
        is DownloadProgress.TileLimitExceeded ->
          error("Offline tile limit ${terminal.limit} was exceeded")
        else -> onStatus("Tiles ready")
      }
    } finally {
      val progress = pack.downloadProgress
      val complete =
        progress is DownloadProgress.Healthy && progress.status == DownloadStatus.Complete
      if (!complete) manager.pause(pack)
    }
  }
}
