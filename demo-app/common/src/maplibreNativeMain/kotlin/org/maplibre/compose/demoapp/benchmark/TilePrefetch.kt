package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.rememberMapRuntime
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflineManager
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.spatialk.geojson.BoundingBox

@Composable
actual fun rememberTilePrefetcher(): TilePrefetcher {
  val manager = rememberMapRuntime().offlineManager
  val pixelRatio = LocalDensity.current.density
  return remember(manager, pixelRatio) { OfflinePackPrefetcher(manager, pixelRatio) }
}

actual val benchmarkPlatformLabel: String = nativeBenchmarkPlatformLabel()

internal expect fun nativeBenchmarkPlatformLabel(): String

private class OfflinePackPrefetcher(
  private val manager: OfflineManager,
  private val pixelRatio: Float,
) : TilePrefetcher {
  override val mode = "offline-pack"

  override suspend fun ensurePacked(
    scenarioId: String,
    styleUrl: String,
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    camera: MapState,
    onStatus: (String) -> Unit,
  ) {
    val metadata = "bench:v1:$scenarioId".encodeToByteArray()
    val existing = manager.packs.firstOrNull { it.metadata?.contentEquals(metadata) == true }
    val pack =
      existing
        ?: manager.create(
          definition =
            OfflinePackDefinition.TilePyramid(
              styleUrl = styleUrl,
              bounds = bounds,
              pixelRatio = pixelRatio,
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
