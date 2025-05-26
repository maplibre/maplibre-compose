package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.core.SnapshotException
import dev.sargunv.maplibrecompose.demoapp.DEFAULT_STYLE
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object SnapshotterDemo : Demo {
  override val name = "Snapshotter"
  override val description = "Take a snapshot of the map"

  @Composable
  override fun Component(navigateUp: () -> Unit) {
    val cameraState = rememberCameraState()
    val styleState = rememberStyleState()
    val isLoading = remember { mutableStateOf(false) }
    val snapshot = remember { mutableStateOf<ImageBitmap?>(null) }
    val snapshotError = remember { mutableStateOf<String?>(null) }
    val lifeCycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifeCycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) {
          isLoading.value = false
        }
      }
      lifeCycleOwner.lifecycle.addObserver(observer)
      onDispose { lifeCycleOwner.lifecycle.removeObserver(observer) }
    }

    DemoScaffold(this, navigateUp) {
      Column {
        Box(modifier = Modifier.weight(1f)) {
          MaplibreMap(
            styleUri = DEFAULT_STYLE,
            cameraState = cameraState,
            styleState = styleState,
            ornamentSettings = DemoOrnamentSettings(),
          )
          DemoMapControls(cameraState, styleState)

          if (isLoading.value) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
          }
        }

        SnapshotterControls(cameraState, isLoading, snapshot, snapshotError)

        snapshot.value?.let {
          SnapshotDialog(snapshot = it, onDismissRequest = { snapshot.value = null })
        }

        snapshotError.value?.let {
          AlertDialog(
            onDismissRequest = { snapshotError.value = null },
            title = { Text(text = "Error during snapshot generation") },
            text = { Text(text = it) },
            confirmButton = { TextButton(onClick = { snapshotError.value = null }) { Text("OK") } },
          )
        }
      }
    }
  }

  @Composable
  private fun SnapshotterControls(
    cameraState: CameraState,
    isLoading: MutableState<Boolean>,
    snapshot: MutableState<ImageBitmap?>,
    snapshotError: MutableState<String?>,
  ) {
    val scope = rememberCoroutineScope()
    var snapshotJob by remember { mutableStateOf<Job?>(null) }

    Row(
      modifier = Modifier.padding(16.dp).fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      Button(
        onClick = {
          snapshotJob =
            scope.launch {
              isLoading.value = true
              try {
                val response =
                  cameraState.snapshot(
                    width = 512,
                    height = 512,
                    cameraPosition = cameraState.position,
                  )

                snapshot.value = response
              } catch (error: SnapshotException) {
                snapshotError.value = error.message
              } catch (error: CancellationException) {
                println("Snapshot generation cancelled")
              }

              isLoading.value = false
              snapshotJob = null
            }
        }
      ) {
        Text("Take snapshot")
      }
      Button(enabled = snapshotJob != null, onClick = { snapshotJob?.cancel() }) {
        Text("Cancel snapshot")
      }
    }
  }

  @Composable
  fun SnapshotDialog(snapshot: ImageBitmap, onDismissRequest: () -> Unit) {
    Dialog(onDismissRequest = { onDismissRequest() }) {
      Card(modifier = Modifier.padding(16.dp), shape = RoundedCornerShape(16.dp)) {
        Image(
          modifier = Modifier.padding(16.dp),
          bitmap = snapshot,
          contentDescription = "Snapshot",
        )
      }
    }
  }
}
