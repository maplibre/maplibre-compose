package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.close_24px
import org.maplibre.compose.demoapp.generated.download_24px
import org.maplibre.compose.demoapp.generated.share_24px

/**
 * The result sheet for the docked snapshot: the photo, where it was taken, and the share and save
 * actions the platform offers.
 */
@Composable
internal fun SnapshotSheet(state: SnapshotterDemoState) {
  val scope = rememberCoroutineScope()
  // The Android result launcher must live as long as the action, including while the sheet is
  // closed.
  val sharer = rememberSnapshotSharer()
  var runningAction by remember { mutableStateOf<SnapshotAction?>(null) }
  var actionMessage by remember { mutableStateOf<String?>(null) }
  var actionFailed by remember { mutableStateOf(false) }
  val shot = state.captured
  LaunchedEffect(shot) { actionMessage = null }
  if (shot == null || !state.sheetOpen) return

  fun run(action: SnapshotAction) {
    if (runningAction != null) return
    runningAction = action
    actionMessage = null
    scope.launch {
      try {
        val result =
          when (action) {
            SnapshotAction.Share -> sharer.share(shot.image, shot.fileName)
            SnapshotAction.Save -> sharer.save(shot.image, shot.fileName)
          }
        if (state.captured === shot) {
          actionFailed = result is SnapshotActionResult.Failed
          actionMessage =
            when (result) {
              is SnapshotActionResult.Completed ->
                when (action) {
                  SnapshotAction.Share -> result.detail ?: "Shared"
                  SnapshotAction.Save -> result.detail?.let { "Saved to $it" } ?: "Saved"
                }
              SnapshotActionResult.Cancelled -> null
              SnapshotActionResult.Failed ->
                "Could not ${if (action == SnapshotAction.Share) "share" else "save"} the snapshot"
            }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (state.captured === shot) {
          actionFailed = true
          actionMessage = error.message ?: "The snapshot action failed"
        }
      } finally {
        runningAction = null
      }
    }
  }

  ModalBottomSheet(onDismissRequest = { state.sheetOpen = false }) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp)
          .padding(bottom = 40.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "Snapshot",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { state.sheetOpen = false }) {
          Icon(vectorResource(Res.drawable.close_24px), contentDescription = "Close")
        }
      }
      Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
        Image(
          bitmap = shot.image,
          contentDescription = "Captured map snapshot",
          contentScale = ContentScale.Fit,
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(max = 480.dp)
              .aspectRatio(shot.request.width.toFloat() / shot.request.height),
        )
      }
      Text(
        text = snapshotMetadata(shot),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (sharer.canShare) {
          ActionButton(
            label = "Share",
            drawableIcon = Res.drawable.share_24px,
            running = runningAction == SnapshotAction.Share,
            enabled = runningAction == null,
            tonal = false,
            onClick = { run(SnapshotAction.Share) },
          )
        }
        if (sharer.canSave) {
          ActionButton(
            label = "Save",
            drawableIcon = Res.drawable.download_24px,
            running = runningAction == SnapshotAction.Save,
            enabled = runningAction == null,
            tonal = true,
            onClick = { run(SnapshotAction.Save) },
          )
        }
      }
      AnimatedVisibility(actionMessage != null) {
        Text(
          text = actionMessage.orEmpty(),
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (actionFailed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ActionButton(
  label: String,
  drawableIcon: DrawableResource,
  running: Boolean,
  enabled: Boolean,
  tonal: Boolean,
  onClick: () -> Unit,
) {
  val content: @Composable () -> Unit = {
    if (running) {
      CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
      Icon(vectorResource(drawableIcon), contentDescription = null, modifier = Modifier.size(18.dp))
    }
    Text(label, modifier = Modifier.padding(start = 8.dp))
  }
  if (tonal) {
    FilledTonalButton(onClick = onClick, enabled = enabled) { content() }
  } else {
    Button(onClick = onClick, enabled = enabled) { content() }
  }
}

private fun snapshotMetadata(shot: CapturedSnapshot): String {
  val camera = shot.request.cameraPosition
  return "${shot.image.width} × ${shot.image.height} px · " +
    "${shot.request.density.toDouble().formatTrimmed(1)}× density · " +
    "zoom ${camera.zoom.formatTrimmed(1)} · " +
    "${camera.target.latitude.formatTrimmed(4)}, ${camera.target.longitude.formatTrimmed(4)}"
}

private fun Double.formatTrimmed(decimals: Int): String {
  var factor = 1.0
  repeat(decimals) { factor *= 10 }
  val rounded = kotlin.math.round(this * factor) / factor
  return rounded.toString()
}
