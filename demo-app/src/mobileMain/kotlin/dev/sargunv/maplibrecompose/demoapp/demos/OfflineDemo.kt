package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.ClickResult
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.layer.FillLayer
import dev.sargunv.maplibrecompose.compose.offline.OfflineManager
import dev.sargunv.maplibrecompose.compose.offline.OfflinePack
import dev.sargunv.maplibrecompose.compose.offline.OfflinePackDefinition
import dev.sargunv.maplibrecompose.compose.offline.rememberOfflineManager
import dev.sargunv.maplibrecompose.compose.offline.rememberOfflinePacksSource
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.demoapp.DEFAULT_STYLE
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import dev.sargunv.maplibrecompose.demoapp.MINIMAL_STYLE
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import dev.sargunv.maplibrecompose.demoapp.generated.download
import dev.sargunv.maplibrecompose.demoapp.generated.error_filled
import dev.sargunv.maplibrecompose.expressions.dsl.asString
import dev.sargunv.maplibrecompose.expressions.dsl.case
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.feature
import dev.sargunv.maplibrecompose.expressions.dsl.switch
import dev.sargunv.maplibrecompose.material3.offline.OfflinePackListItem
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Position
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource

private val CDMX = Position(latitude = 19.4326, longitude = -99.1332)

object OfflineDemo : Demo {
  override val name: String
    get() = "Offline regions"

  override val description: String
    get() = "Save regions of the map to device storage."

  @Composable
  override fun Component(navigateUp: () -> Unit) {
    val cameraState =
      rememberCameraState(firstPosition = CameraPosition(target = CDMX, zoom = 12.0))
    val styleState = rememberStyleState()
    val offlineManager = rememberOfflineManager()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val sheetPeekHeight = BottomSheetDefaults.SheetPeekHeight + 72.dp
    val keyboard = LocalSoftwareKeyboardController.current

    DemoScaffold(this, navigateUp) {
      BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
        sheetContent = { OfflinePackControls(offlineManager, cameraState) },
      ) {
        MaplibreMap(
          styleUri = MINIMAL_STYLE,
          cameraState = cameraState,
          styleState = styleState,
          ornamentSettings =
            DemoOrnamentSettings(padding = PaddingValues(bottom = sheetPeekHeight)),
          onMapClick = { _, _ ->
            keyboard?.hide()
            ClickResult.Pass
          },
        ) {
          OfflinePacksLayers(offlineManager)
        }

        DemoMapControls(
          cameraState = cameraState,
          styleState = styleState,
          padding =
            PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + sheetPeekHeight),
        )
      }
    }
  }
}

@Composable
private fun OfflinePacksLayers(offlineManager: OfflineManager) {
  FillLayer(
    id = "offline-packs",
    source = rememberOfflinePacksSource("offline-packs", offlineManager.packs),
    opacity = const(0.5f),
    color =
      switch(
        feature.get("status").asString(),
        case(label = "Complete", output = const(Color.Green)),
        case(label = "Downloading", output = const(Color.Blue)),
        case(label = "Paused", output = const(Color.Yellow)),
        fallback = const(Color.Red),
      ),
  )
}

@Composable
private fun OfflinePackControls(offlineManager: OfflineManager, cameraState: CameraState) {
  val coroutineScope = rememberCoroutineScope()

  LazyColumn(modifier = Modifier.padding(bottom = 16.dp, start = 16.dp, end = 16.dp)) {
    item { DownloadForm(cameraState) }

    item {
      Text(
        text = "Offline packs",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp),
      )
    }

    if (offlineManager.packs.isEmpty()) {
      item {
        Card(modifier = Modifier.fillMaxWidth().animateItem()) {
          Text(
            text = "No packs downloaded yet",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    } else {
      fun locatePack(pack: OfflinePack) {
        coroutineScope.launch { cameraState.animateToOfflinePack(pack.definition) }
      }

      items(offlineManager.packs.toList(), key = { it.hashCode() }) { pack ->
        OfflinePackListItem(
          pack = pack,
          modifier = Modifier.animateItem().clickable { locatePack(pack) },
        ) {
          Text(pack.metadata?.decodeToString().orEmpty().ifBlank { "Unnamed Region" })
        }
      }
    }
  }
}

@Composable
private fun DownloadForm(cameraState: CameraState) {
  var inputValue by remember { mutableStateOf("Example") }
  val offlineManager = rememberOfflineManager()
  val coroutineScope = rememberCoroutineScope()
  val zoomedInEnough = cameraState.position.zoom >= 8.0
  val keyboard = LocalSoftwareKeyboardController.current

  fun downloadPack() {
    keyboard?.hide()
    if (zoomedInEnough)
      coroutineScope.launch {
        val pack =
          offlineManager.createNamed(
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
        else Icon(vectorResource(Res.drawable.error_filled), contentDescription = "Error")
      }
    },
  )
}

@Composable
private fun DownloadButton(enabled: Boolean, onClick: () -> Unit) {
  IconButton(enabled = enabled, onClick = onClick) {
    Icon(
      vectorResource(Res.drawable.download),
      contentDescription = "Download",
      tint = MaterialTheme.colorScheme.primary,
    )
  }
}

private suspend fun OfflineManager.createNamed(name: String, bounds: BoundingBox): OfflinePack {
  return create(
    OfflinePackDefinition.TilePyramid(styleUrl = DEFAULT_STYLE, bounds = bounds),
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
