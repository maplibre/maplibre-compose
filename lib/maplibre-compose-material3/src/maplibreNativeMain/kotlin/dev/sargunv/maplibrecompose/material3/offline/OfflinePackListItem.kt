package dev.sargunv.maplibrecompose.material3.offline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import dev.sargunv.maplibrecompose.material3.generated.pause_circle_filled
import dev.sargunv.maplibrecompose.material3.generated.resume
import dev.sargunv.maplibrecompose.material3.generated.sync
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
    pausedIcon: @Composable () -> Unit = {
      Icon(
        imageVector = vectorResource(Res.drawable.pause_circle_filled),
        contentDescription = "Paused",
      )
    },
    downloadingIcon: @Composable () -> Unit = { DownloadProgressCircle(pack) },
    errorIcon: @Composable () -> Unit = {
      Icon(
        imageVector = vectorResource(Res.drawable.error_filled),
        contentDescription = "Error",
        tint = MaterialTheme.colorScheme.error,
      )
    },
    warningIcon: @Composable () -> Unit = {
      Icon(
        imageVector = vectorResource(Res.drawable.warning_filled),
        contentDescription = "Warning",
      )
    },
  ) {
    val icon by derivedStateOf {
      val progress = pack.downloadProgress
      when (progress) {
        is DownloadProgress.Healthy ->
          when (progress.status) {
            DownloadStatus.Complete -> completedIcon
            DownloadStatus.Paused -> pausedIcon
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
    PauseResumeUpdateButton(pack, offlineManager)
    DeleteButton(pack, offlineManager)
  }

  @Composable
  public fun SupportingContent(
    progress: DownloadProgress,
    completedContent: @Composable (DownloadProgress.Healthy) -> Unit = {
      Text(it.completedBytesString())
    },
    downloadingContent: @Composable (DownloadProgress.Healthy) -> Unit = {
      Text("Downloading: ${it.completedBytesString()}")
    },
    pausedContent: @Composable (DownloadProgress.Healthy) -> Unit = {
      Text("Paused: ${it.completedBytesString()}")
    },
    errorContent: @Composable (DownloadProgress.Error) -> Unit = { Text("Error: ${it.message}") },
    tileLimitExceededContent: @Composable (DownloadProgress.TileLimitExceeded) -> Unit = {
      Text("Tile limit exceeded: ${it.limit} tiles")
    },
    unknownContent: @Composable (DownloadProgress.Unknown) -> Unit = { Text("Unknown status") },
  ) {
    when (progress) {
      is DownloadProgress.Healthy ->
        when (progress.status) {
          DownloadStatus.Complete -> completedContent(progress)
          DownloadStatus.Downloading -> downloadingContent(progress)
          DownloadStatus.Paused -> pausedContent(progress)
        }
      is DownloadProgress.Error -> errorContent(progress)
      is DownloadProgress.TileLimitExceeded -> tileLimitExceededContent(progress)
      is DownloadProgress.Unknown -> unknownContent(progress)
    }
  }
}

@Composable
private fun DeleteButton(pack: OfflinePack, offlineManager: OfflineManager) {
  val coroutineScope = rememberCoroutineScope()

  fun onDelete() {
    coroutineScope.launch { offlineManager.delete(pack) }
  }

  IconButton(onClick = ::onDelete) {
    Icon(vectorResource(Res.drawable.delete), "Delete", tint = MaterialTheme.colorScheme.error)
  }
}

@Composable
private fun DownloadProgressCircle(pack: OfflinePack) {
  val progressRatio by derivedStateOf {
    val progress = pack.downloadProgress
    if (progress is DownloadProgress.Healthy && progress.requiredResourceCount != 0L)
      progress.completedResourceCount.toFloat() / progress.requiredResourceCount
    else 0f
  }

  val animatedProgressRatio by
    animateFloatAsState(
      targetValue = progressRatio,
      animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
    )

  CircularProgressIndicator(progress = { animatedProgressRatio })
}

@Composable
private fun PauseResumeUpdateButton(pack: OfflinePack, offlineManager: OfflineManager) {
  val status = (pack.downloadProgress as? DownloadProgress.Healthy)?.status ?: return
  val coroutineScope = rememberCoroutineScope()

  fun onClick() {
    when (status) {
      DownloadStatus.Paused -> offlineManager.resume(pack)
      DownloadStatus.Downloading -> offlineManager.pause(pack)
      DownloadStatus.Complete -> coroutineScope.launch { offlineManager.invalidate(pack) }
    }
  }

  IconButton(::onClick) {
    AnimatedContent(status) { status ->
      when (status) {
        DownloadStatus.Paused -> Icon(vectorResource(Res.drawable.resume), "Resume")
        DownloadStatus.Downloading -> Icon(vectorResource(Res.drawable.pause), "Pause")
        DownloadStatus.Complete -> Icon(vectorResource(Res.drawable.sync), "Update")
      }
    }
  }
}

private fun DownloadProgress.Healthy.completedBytesString() =
  completedResourceBytes.binaryBytes.toString()
