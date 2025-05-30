package dev.sargunv.maplibrecompose.material3.offline

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.compose.offline.DownloadProgress
import dev.sargunv.maplibrecompose.compose.offline.DownloadStatus
import dev.sargunv.maplibrecompose.compose.offline.OfflineManager
import dev.sargunv.maplibrecompose.compose.offline.OfflinePack
import dev.sargunv.maplibrecompose.compose.offline.rememberOfflineManager
import dev.sargunv.maplibrecompose.material3.generated.Res
import dev.sargunv.maplibrecompose.material3.generated.delete
import dev.sargunv.maplibrecompose.material3.generated.pause
import dev.sargunv.maplibrecompose.material3.generated.recenter
import dev.sargunv.maplibrecompose.material3.generated.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource

@Composable
public fun OfflinePackListItem(
  pack: OfflinePack,
  onLocate: (() -> Unit)? = null,
  offlineManager: OfflineManager = rememberOfflineManager(),
  leadingContent: @Composable () -> Unit = {},
  supportingContent: @Composable () -> Unit = { OfflinePackStatusIndicator(pack.downloadProgress) },
  trailingContent: @Composable () -> Unit = {
    OfflinePackControlRow(pack, onLocate = onLocate, offlineManager = offlineManager)
  },
  headlineContent: @Composable () -> Unit,
): Unit =
  ListItem(
    leadingContent = leadingContent,
    headlineContent = headlineContent,
    supportingContent = supportingContent,
    trailingContent = trailingContent,
  )

@Composable
public fun OfflinePackControlRow(
  pack: OfflinePack,
  onLocate: (() -> Unit)? = null,
  offlineManager: OfflineManager = rememberOfflineManager(),
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
): Unit = Row {
  val progress = pack.downloadProgress
  if (progress is DownloadProgress.Healthy)
    PauseResumeButton(
      progress.status,
      onPause = { offlineManager.pause(pack) },
      onResume = { offlineManager.resume(pack) },
    )
  onLocate?.let { LocateButton(it) }
  DeleteButton { coroutineScope.launch { offlineManager.delete(pack) } }
}

@Composable
public fun OfflinePackStatusIndicator(progress: DownloadProgress): Unit =
  when (progress) {
    is DownloadProgress.Healthy ->
      when (progress.status) {
        DownloadStatus.Complete -> Text("Downloaded")
        else -> DownloadProgressBar(progress)
      }
    is DownloadProgress.Error -> Text("Error - ${progress.message}")
    is DownloadProgress.TileLimitExceeded -> Text("Tile limit exceeded - ${progress.limit} tiles")
    is DownloadProgress.Unknown -> Text("Unknown status")
  }

@Composable
private fun DownloadProgressBar(progress: DownloadProgress.Healthy) =
  LinearProgressIndicator(
    progress = {
      if (progress.requiredResourceCount == 0L) 0f
      else progress.completedResourceCount.toFloat() / progress.requiredResourceCount
    },
    modifier = Modifier.fillMaxWidth(),
  )

@Composable
private fun PauseResumeButton(status: DownloadStatus, onPause: () -> Unit, onResume: () -> Unit) =
  when (status) {
    DownloadStatus.Paused -> ResumeButton(onResume)
    DownloadStatus.Downloading -> PauseButton(onPause)
    else -> {}
  }

@Composable
private fun PauseButton(onClick: () -> Unit) =
  IconButton(onClick = onClick) { Icon(vectorResource(Res.drawable.pause), "Pause") }

@Composable
private fun ResumeButton(onClick: () -> Unit) =
  IconButton(onClick = onClick) { Icon(vectorResource(Res.drawable.resume), "Resume") }

@Composable
private fun LocateButton(onClick: () -> Unit) =
  IconButton(onClick = onClick) { Icon(vectorResource(Res.drawable.recenter), "Locate") }

@Composable
private fun DeleteButton(onClick: () -> Unit) =
  IconButton(onClick = onClick) { Icon(vectorResource(Res.drawable.delete), "Delete") }
