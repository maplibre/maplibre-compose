package org.maplibre.compose.material3

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.bytesize.binaryBytes
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.material3.generated.Res
import org.maplibre.compose.material3.generated.check_circle_filled
import org.maplibre.compose.material3.generated.delete
import org.maplibre.compose.material3.generated.error_filled
import org.maplibre.compose.material3.generated.offline_pack_delete
import org.maplibre.compose.material3.generated.offline_pack_delete_cancel
import org.maplibre.compose.material3.generated.offline_pack_delete_confirm
import org.maplibre.compose.material3.generated.offline_pack_delete_error
import org.maplibre.compose.material3.generated.offline_pack_delete_message
import org.maplibre.compose.material3.generated.offline_pack_delete_title
import org.maplibre.compose.material3.generated.offline_pack_delete_unknown_error
import org.maplibre.compose.material3.generated.pause
import org.maplibre.compose.material3.generated.pause_circle_filled
import org.maplibre.compose.material3.generated.resume
import org.maplibre.compose.material3.generated.sync
import org.maplibre.compose.material3.generated.warning_filled
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflineManager
import org.maplibre.compose.offline.OfflinePack

/**
 * A [ListItem] to manage an [OfflinePack].
 *
 * By default, it includes controls to pause, resume, invalidate, and delete the pack, and a
 * [CircularProgressIndicator] for download progress.
 *
 * You must supply a [headlineContent] for the list item. Typically, this will be a suitable name
 * for the pack, parsed from [OfflinePack.metadata].
 *
 * Swipe the item from end to start to request deletion. The default trailing delete button and the
 * swipe gesture both require confirmation before deleting the pack.
 *
 * Pass the [OfflineManager] from the [org.maplibre.compose.map.MapRuntime] that created [pack].
 *
 * You can customize each part of the [ListItem] by supplying alternate [leadingContent],
 * [supportingContent], and [trailingContent].
 */
@Composable
public fun OfflinePackListItem(
  pack: OfflinePack,
  offlineManager: OfflineManager,
  modifier: Modifier = Modifier,
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
  ConfirmedOfflinePackSwipeToDelete(
    deleteKey = pack,
    onDelete = { offlineManager.delete(pack) },
    modifier = modifier,
  ) { requestDelete ->
    CompositionLocalProvider(LocalDeleteRequest provides requestDelete) {
      ListItem(
        leadingContent = leadingContent,
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
      )
    }
  }
}

@Composable
internal fun ConfirmedOfflinePackSwipeToDelete(
  deleteKey: Any?,
  onDelete: suspend () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable (requestDelete: () -> Unit) -> Unit,
) {
  key(deleteKey) {
    val dismissState = rememberSwipeToDismissBoxState()
    OfflinePackDeleteConfirmation(deleteKey, onDelete, dismissState) {
      requestDelete,
      deleting,
      confirmationVisible ->
      SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.clipToBounds(),
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        gesturesEnabled = !deleting && !confirmationVisible,
        onDismiss = { requestDelete() },
        backgroundContent = {
          Box(
            modifier =
              Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterEnd,
          ) {
            Icon(
              imageVector = vectorResource(Res.drawable.delete),
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        },
      ) {
        content(requestDelete)
      }
    }
  }
}

public object OfflinePackListItemDefaults {
  /**
   * The default leading content for an [OfflinePackListItem]. It includes a
   * [CircularProgressIndicator] for in-progress downloads, and otherwise an [Icon] representing the
   * status of the pack.
   */
  @Composable
  public fun LeadingContent(
    pack: OfflinePack,
    offlineManager: OfflineManager,
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
    val icon by
      remember(pack) {
        derivedStateOf {
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
      }
    AnimatedContent(icon) { icon -> icon() }
  }

  /**
   * The default trailing content for an [OfflinePackListItem]. It includes a button to pause,
   * resume, or update the pack, depending on the pack's current status. It also includes a delete
   * button.
   */
  @Composable
  public fun TrailingContent(
    pack: OfflinePack,
    offlineManager: OfflineManager,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
  ): Unit = Row {
    PauseResumeUpdateButton(pack, offlineManager)
    DeleteButton(pack, offlineManager)
  }

  /**
   * The default supporting content for an [OfflinePackListItem]. It includes a [Text] describing
   * the status of the pack; typically the download status and size. If the pack is in an error or
   * other unhealthy state, it'll be indicated here.
   */
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
  val sharedDeleteRequest = LocalDeleteRequest.current
  if (sharedDeleteRequest != null) {
    DeleteIconButton(onClick = sharedDeleteRequest)
  } else {
    OfflinePackDeleteConfirmation(
      deleteKey = pack,
      onDelete = { offlineManager.delete(pack) },
    ) { requestDelete, deleting, _ ->
      DeleteIconButton(requestDelete, enabled = !deleting)
    }
  }
}

@Composable
private fun DeleteIconButton(onClick: () -> Unit, enabled: Boolean = true) {
  IconButton(onClick = onClick, enabled = enabled) {
    Icon(
      imageVector = vectorResource(Res.drawable.delete),
      contentDescription = stringResource(Res.string.offline_pack_delete),
      tint = MaterialTheme.colorScheme.error,
    )
  }
}

private val LocalDeleteRequest = compositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun OfflinePackDeleteConfirmation(
  deleteKey: Any?,
  onDelete: suspend () -> Unit,
  dismissState: SwipeToDismissBoxState? = null,
  content:
    @Composable
    (
      requestDelete: () -> Unit,
      deleting: Boolean,
      confirmationVisible: Boolean,
    ) -> Unit,
) {
  val coroutineScope = rememberCoroutineScope()
  val currentOnDelete by rememberUpdatedState(onDelete)
  val unknownDeleteError = stringResource(Res.string.offline_pack_delete_unknown_error)
  var confirmationVisible by remember(deleteKey) { mutableStateOf(false) }
  var deleting by remember(deleteKey) { mutableStateOf(false) }
  var deleteError by remember(deleteKey) { mutableStateOf<String?>(null) }

  val requestDelete =
    remember(deleteKey) {
      {
        if (!deleting) {
          deleteError = null
          confirmationVisible = true
        }
      }
    }
  val cancelDelete =
    remember(deleteKey, dismissState, coroutineScope) {
      {
        if (!deleting) {
          coroutineScope.launch {
            dismissState?.reset()
            deleteError = null
            confirmationVisible = false
          }
        }
      }
    }
  val confirmDelete =
    remember(deleteKey, coroutineScope, unknownDeleteError) {
      {
        if (!deleting) {
          deleting = true
          deleteError = null
          coroutineScope.launch {
            try {
              currentOnDelete()
              confirmationVisible = false
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              deleteError = e.message ?: unknownDeleteError
            } finally {
              deleting = false
            }
          }
        }
      }
    }

  content(requestDelete, deleting, confirmationVisible)

  if (confirmationVisible) {
    AlertDialog(
      onDismissRequest = cancelDelete,
      title = { Text(stringResource(Res.string.offline_pack_delete_title)) },
      text = {
        Column {
          Text(stringResource(Res.string.offline_pack_delete_message))
          deleteError?.let {
            Text(
              text = stringResource(Res.string.offline_pack_delete_error, it),
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(top = 16.dp),
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = confirmDelete, enabled = !deleting) {
          Text(stringResource(Res.string.offline_pack_delete_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = cancelDelete, enabled = !deleting) {
          Text(stringResource(Res.string.offline_pack_delete_cancel))
        }
      },
    )
  }
}

@Composable
private fun DownloadProgressCircle(pack: OfflinePack) {
  val progressRatio by
    remember(pack) {
      derivedStateOf {
        val progress = pack.downloadProgress
        if (progress is DownloadProgress.Healthy && progress.requiredResourceCount != 0L)
          progress.completedResourceCount.toFloat() / progress.requiredResourceCount
        else 0f
      }
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
