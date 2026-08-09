package org.maplibre.compose.demoapp.demos

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.Subheading
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.download_24px
import org.maplibre.compose.demoapp.generated.error_24px
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.material3.OfflinePackListItem
import org.maplibre.compose.offline.OfflineManager
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.rememberOfflineManager
import org.maplibre.compose.offline.rememberOfflinePacksSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox

object OfflineManagerDemo : Demo {
  override val name = "Manage offline tiles"

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    if (!isOpen) return
    val offlineManager = rememberOfflineManager()
    FillLayer(
      id = "offline-packs",
      source = rememberOfflinePacksSource(offlineManager.packs),
      opacity = const(0.5f),
      color =
        switch(
          feature["status"].asString(),
          case(label = "Complete", output = const(Color.Green)),
          case(label = "Downloading", output = const(Color.Blue)),
          case(label = "Paused", output = const(Color.Yellow)),
          fallback = const(Color.Red),
        ),
    )
  }

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    val offlineManager = rememberOfflineManager()
    val coroutineScope = rememberCoroutineScope()

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
        AnimatedContent(zoomedInEnough) { zoomedInEnough ->
          if (zoomedInEnough) DownloadButton(enabled = zoomedInEnough, onClick = ::downloadPack)
          else Icon(vectorResource(Res.drawable.error_24px), contentDescription = "Error")
        }
      },
    )
  }

  @Composable
  private fun DownloadButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(enabled = enabled, onClick = onClick) {
      Icon(
        vectorResource(Res.drawable.download_24px),
        contentDescription = "Download",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }

  private suspend fun OfflineManager.createNamed(
    style: DemoStyle,
    name: String,
    bounds: BoundingBox,
  ): OfflinePack {
    val base = style.base
    // TODO don't crash the app
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
}
