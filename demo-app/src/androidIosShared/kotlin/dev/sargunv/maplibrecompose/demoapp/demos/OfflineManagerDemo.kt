package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.offline.OfflineManager
import dev.sargunv.maplibrecompose.compose.offline.OfflinePack
import dev.sargunv.maplibrecompose.compose.offline.OfflinePackDefinition
import dev.sargunv.maplibrecompose.compose.offline.rememberOfflineManager
import dev.sargunv.maplibrecompose.core.BaseStyle
import dev.sargunv.maplibrecompose.demoapp.DemoState
import dev.sargunv.maplibrecompose.demoapp.DemoStyle
import dev.sargunv.maplibrecompose.demoapp.design.CardColumn
import dev.sargunv.maplibrecompose.demoapp.design.CloseButton
import dev.sargunv.maplibrecompose.demoapp.design.Heading
import dev.sargunv.maplibrecompose.demoapp.design.PageColumn
import dev.sargunv.maplibrecompose.demoapp.design.Subheading
import dev.sargunv.maplibrecompose.material3.offline.OfflinePackListItem
import io.github.dellisd.spatialk.geojson.BoundingBox
import kotlinx.coroutines.launch

object OfflineManagerDemo : Demo {
  override val name = "Manage offline tiles"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    val offlineManager = rememberOfflineManager()
    val coroutineScope = rememberCoroutineScope()

    PageColumn(modifier = modifier) {
      Heading(
        text = "Offline Manager",
        trailingContent = { CloseButton { state.nav.popBackStack() } },
      )

      DownloadForm(state.selectedStyle, state.cameraState, offlineManager)

      Subheading("Offline packs")

      CardColumn {
        if (offlineManager.packs.isEmpty()) {
          Text(
            text = "No packs downloaded yet",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
          )
        } else {
          fun locatePack(pack: OfflinePack) {
            coroutineScope.launch { state.cameraState.animateToOfflinePack(pack.definition) }
          }

          offlineManager.packs.toList().forEach { pack ->
            key(pack.hashCode()) {
              OfflinePackListItem(pack = pack, modifier = Modifier.clickable { locatePack(pack) }) {
                Text(pack.metadata?.decodeToString().orEmpty().ifBlank { "Unnamed Region" })
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DownloadForm(
  style: DemoStyle,
  cameraState: CameraState,
  offlineManager: OfflineManager,
) {
  var inputValue by remember { mutableStateOf("Example") }
  val coroutineScope = rememberCoroutineScope()
  val zoomedInEnough = cameraState.position.zoom >= 8.0
  val keyboard = LocalSoftwareKeyboardController.current

  fun downloadPack() {
    keyboard?.hide()
    if (zoomedInEnough)
      coroutineScope.launch {
        val pack =
          offlineManager.createNamed(
            style = style,
            name = inputValue,
            bounds = cameraState.awaitProjection().queryVisibleBoundingBox(),
          )
        offlineManager.resume(pack)
        inputValue = ""
      }
  }

  OutlinedTextField(
    value = inputValue,
    onValueChange = { inputValue = it },
    label = { Text("Pack name") },
    modifier = Modifier.fillMaxWidth(),
    isError = !zoomedInEnough,
    supportingText = { AnimatedVisibility(!zoomedInEnough) { Text("Too far; zoom in") } },
    singleLine = true,
    keyboardActions = KeyboardActions(onDone = { downloadPack() }),
    trailingIcon = {
      if (zoomedInEnough) {
        Button(
          onClick = ::downloadPack,
          enabled = zoomedInEnough,
          modifier = Modifier.padding(8.dp),
        ) {
          Text("Download")
        }
      }
    },
  )
}

private suspend fun OfflineManager.createNamed(
  style: DemoStyle,
  name: String,
  bounds: BoundingBox,
): OfflinePack {
  val base = style.base
  if (base !is BaseStyle.Uri) error("Style must be a URI style for offline packs")
  return create(
    OfflinePackDefinition.TilePyramid(styleUrl = base.uri, bounds = bounds),
    name.encodeToByteArray(),
  )
}

private suspend fun CameraState.animateToOfflinePack(definition: OfflinePackDefinition) {
  val targetBounds =
    when (definition) {
      is OfflinePackDefinition.TilePyramid -> definition.bounds
      is OfflinePackDefinition.Shape -> definition.shape.bbox
    }
  targetBounds?.let { animateTo(it, padding = PaddingValues(64.dp)) }
}
