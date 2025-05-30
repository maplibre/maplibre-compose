package dev.sargunv.maplibrecompose.material3.offline

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.offline.DownloadProgress
import dev.sargunv.maplibrecompose.compose.offline.DownloadStatus
import dev.sargunv.maplibrecompose.compose.offline.OfflineManager
import dev.sargunv.maplibrecompose.compose.offline.OfflinePack
import dev.sargunv.maplibrecompose.compose.offline.rememberOfflineManager
import dev.sargunv.maplibrecompose.material3.generated.Res
import dev.sargunv.maplibrecompose.material3.generated.check_circle_filled
import dev.sargunv.maplibrecompose.material3.generated.delete
import dev.sargunv.maplibrecompose.material3.generated.error_filled
import dev.sargunv.maplibrecompose.material3.generated.pause
import dev.sargunv.maplibrecompose.material3.generated.pending_filled
import dev.sargunv.maplibrecompose.material3.generated.resume
import dev.sargunv.maplibrecompose.material3.generated.warning_filled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.bytesize.binaryBytes
import org.jetbrains.compose.resources.vectorResource

@Composable
public fun OfflinePackListItem(
  pack: OfflinePack,
  onClick: (() -> Unit)? = null,
  offlineManager: OfflineManager = rememberOfflineManager(),
  leadingContent: @Composable () -> Unit = {
    OfflinePackListItemDefaults.LeadingContent(pack, offlineManager)
  },
  supportingContent: @Composable () -> Unit = {
    OfflinePackListItemDefaults.SupportingContent(pack.downloadProgress)
  },
  trailingContent: @Composable () -> Unit = {
    OfflinePackListItemDefaults.TrailingContent(pack, offlineManager)
  },
  headlineContent: @Composable () -> Unit,
) {
  // TODO swipe to delete? confirmation to delete?
  ListItem(
    modifier = Modifier.let { if (onClick != null) it.clickable(onClick = onClick) else it },
    leadingContent = leadingContent,
    headlineContent = headlineContent,
    supportingContent = supportingContent,
    trailingContent = trailingContent,
  )
}

public object OfflinePackListItemDefaults {

  @Composable
  public fun LeadingContent(
    pack: OfflinePack,
    offlineManager: OfflineManager = rememberOfflineManager(),
    completedIcon: @Composable () -> Unit = {
      Icon(
        imageVector = vectorResource(Res.drawable.check_circle_filled),
        contentDescription = "Complete",
      )
    },
    downloadingIcon: @Composable () -> Unit = {
      Icon(
        imageVector = vectorResource(Res.drawable.pending_filled),
        contentDescription = "Complete",
      )
    },
    errorIcon: @Composable () -> Unit = {
      Icon(
        imageVector = vectorResource(Res.drawable.error_filled),
        contentDescription = "Complete",
        tint = MaterialTheme.colorScheme.error,
      )
    },
    warningIcon: @Composable () -> Unit = {
      Icon(
        imageVector = vectorResource(Res.drawable.warning_filled),
        contentDescription = "Complete",
      )
    },
  ) {
    val icon by derivedStateOf {
      val progress = pack.downloadProgress
      when (progress) {
        is DownloadProgress.Healthy ->
          when (progress.status) {
            DownloadStatus.Complete -> completedIcon
            DownloadStatus.Paused,
            DownloadStatus.Downloading -> downloadingIcon
          }
        is DownloadProgress.Error -> errorIcon
        is DownloadProgress.TileLimitExceeded,
        is DownloadProgress.Unknown -> warningIcon
      }
    }
    AnimatedContent(icon) { icon -> icon() }
  }

  @Composable
  public fun TrailingContent(
    pack: OfflinePack,
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
    IconButton(onClick = { coroutineScope.launch { offlineManager.delete(pack) } }) {
      Icon(vectorResource(Res.drawable.delete), "Delete", tint = MaterialTheme.colorScheme.error)
    }
  }

  @Composable
  public fun SupportingContent(progress: DownloadProgress): Unit =
    when (progress) {
      is DownloadProgress.Healthy ->
        when (progress.status) {
          DownloadStatus.Complete -> Text(progress.completedResourceBytes.binaryBytes.toString())
          else ->
            Column {
              DownloadProgressBar(progress)
              Text("${progress.completedResourceCount}/${progress.requiredResourceCount} resources")
            }
        }
      is DownloadProgress.Error -> Text("Error - ${progress.message}")
      is DownloadProgress.TileLimitExceeded -> Text("Tile limit exceeded - ${progress.limit} tiles")
      is DownloadProgress.Unknown -> Text("Unknown status")
    }
}

@Composable
private fun DownloadProgressBar(progress: DownloadProgress.Healthy) =
  LinearProgressIndicator(
    progress = {
      if (progress.requiredResourceCount == 0L) 0f
      else progress.completedResourceCount.toFloat() / progress.requiredResourceCount
    },
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
  )

@Composable
private fun PauseResumeButton(status: DownloadStatus, onPause: () -> Unit, onResume: () -> Unit) {
  IconButton(
    onClick = {
      when (status) {
        DownloadStatus.Paused -> onResume()
        DownloadStatus.Downloading -> onPause()
        else -> {}
      }
    }
  ) {
    AnimatedContent(status) { status ->
      when (status) {
        DownloadStatus.Paused -> Icon(vectorResource(Res.drawable.resume), "Resume")
        DownloadStatus.Downloading -> Icon(vectorResource(Res.drawable.pause), "Pause")
        else -> {}
      }
    }
  }
}

@Composable
private fun DeleteButton(onDelete: () -> Unit) {
  IconButton(onClick = onDelete) { Icon(vectorResource(Res.drawable.delete), "Delete") }
}
