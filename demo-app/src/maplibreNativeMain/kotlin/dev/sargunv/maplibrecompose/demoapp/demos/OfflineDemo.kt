package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.layer.FillLayer
import dev.sargunv.maplibrecompose.compose.offline.DownloadProgress
import dev.sargunv.maplibrecompose.compose.offline.DownloadStatus
import dev.sargunv.maplibrecompose.compose.offline.OfflineTilePack
import dev.sargunv.maplibrecompose.compose.offline.OfflineTilesManager
import dev.sargunv.maplibrecompose.compose.offline.TilePackDefinition
import dev.sargunv.maplibrecompose.compose.offline.rememberOfflineTilesManager
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.core.source.Source
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import dev.sargunv.maplibrecompose.demoapp.MINIMAL_STYLE
import dev.sargunv.maplibrecompose.expressions.dsl.asString
import dev.sargunv.maplibrecompose.expressions.dsl.case
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.feature
import dev.sargunv.maplibrecompose.expressions.dsl.switch
import io.github.dellisd.spatialk.geojson.Position
import io.github.dellisd.spatialk.geojson.dsl.featureCollection
import io.github.dellisd.spatialk.geojson.dsl.polygon
import kotlinx.coroutines.launch

private val CDMX = Position(latitude = 19.4326, longitude = -99.1332)
private const val MIN_ZOOM_TO_SAVE = 10.0

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
    val offlineManager = rememberOfflineTilesManager()
    val scaffoldState = rememberBottomSheetScaffoldState()

    DemoScaffold(this, navigateUp) {
      BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContent = {
          Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            OfflinePackControls(offlineManager, cameraState)
          }
        },
      ) {
        MaplibreMap(
          styleUri = MINIMAL_STYLE,
          cameraState = cameraState,
          styleState = styleState,
          ornamentSettings =
            DemoOrnamentSettings(
              padding = PaddingValues(bottom = BottomSheetDefaults.SheetPeekHeight)
            ),
        ) {
          val source = rememberOfflineRegionsSource(offlineManager)

          FillLayer(
            id = "downloaded-regions",
            source = source,
            opacity = const(0.5f),
            color =
              switch(
                feature.get("state").asString(),
                case(label = "Complete", output = const(Color.Green)),
                case(label = "Downloading", output = const(Color.Blue)),
                case(label = "Paused", output = const(Color.Yellow)),
                fallback = const(Color.Red),
              ),
          )
        }

        DemoMapControls(
          cameraState,
          styleState,
          padding =
            PaddingValues(
              start = 8.dp,
              end = 8.dp,
              top = 8.dp,
              bottom = 8.dp + BottomSheetDefaults.SheetPeekHeight,
            ),
        ) {
          AnimatedVisibility(
            visible = cameraState.position.zoom < MIN_ZOOM_TO_SAVE,
            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
            enter = fadeIn() + expandIn(expandFrom = Alignment.TopCenter),
            exit = fadeOut() + shrinkOut(shrinkTowards = Alignment.TopCenter),
          ) {
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(24.dp),
            ) {
              Text(modifier = Modifier.padding(12.dp), text = "Too far; zoom in")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun rememberOfflineRegionsSource(offlineManager: OfflineTilesManager): Source {
  return rememberGeoJsonSource(
    id = "downloaded-regions",
    data =
      featureCollection {
        offlineManager.regions.forEach { pack ->
          val def = pack.definition
          feature(
            geometry =
              when (def) {
                is TilePackDefinition.TilePyramid -> {
                  val bounds = def.bounds
                  polygon {
                    ring {
                      +bounds.southwest
                      point(
                        longitude = bounds.southwest.longitude,
                        latitude = bounds.northeast.latitude,
                      )
                      +bounds.northeast
                      point(
                        longitude = bounds.northeast.longitude,
                        latitude = bounds.southwest.latitude,
                      )
                      +bounds.southwest
                    }
                  }
                }
                is TilePackDefinition.Shape -> def.shape
              }
          ) {
            put("name", pack.metadata?.decodeToString().orEmpty())
            val progress = pack.progress
            put(
              "state",
              when (progress) {
                is DownloadProgress.Healthy -> progress.downloadStatus.name
                else -> "Unhealthy"
              },
            )
          }
        }
      },
  )
}

@Composable
private fun OfflinePackControls(
  offlineTilesManager: OfflineTilesManager,
  cameraState: CameraState,
) {
  var inputValue by remember { mutableStateOf("") }
  val coroutineScope = rememberCoroutineScope()
  val canSave = cameraState.position.zoom >= MIN_ZOOM_TO_SAVE

  Column(modifier = Modifier.padding(16.dp)) {
    OutlinedTextField(
      value = inputValue,
      onValueChange = { inputValue = it },
      label = { Text("Region Name") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Button(
        onClick = {
          coroutineScope.launch {
            offlineTilesManager
              .create(
                definition =
                  TilePackDefinition.TilePyramid(
                    styleUrl = MINIMAL_STYLE,
                    bounds = cameraState.awaitProjection().queryVisibleBoundingBox(),
                  ),
                metadata = inputValue.encodeToByteArray(),
              )
              .resume()
            inputValue = ""
          }
        },
        enabled = inputValue.isNotBlank() && canSave,
      ) {
        Text("Save Region")
      }
    }
  }

  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    Text(
      text = "Saved Regions",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(vertical = 8.dp),
    )

    if (offlineTilesManager.regions.isEmpty()) {
      Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
          text = "No regions saved yet",
          modifier = Modifier.padding(16.dp),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      offlineTilesManager.regions.forEach { pack ->
        PackListItem(
          pack,
          onDelete = { coroutineScope.launch { offlineTilesManager.delete(pack) } },
          onLocate = {
            coroutineScope.launch {
              val def = pack.definition
              if (def is TilePackDefinition.TilePyramid) {
                cameraState.animateTo(def.bounds)
              } else if (def is TilePackDefinition.Shape) {
                def.shape.bbox?.let { cameraState.animateTo(it) }
              }
            }
          },
        )
      }
    }
  }
}

@Composable
private fun PackListItem(pack: OfflineTilePack, onDelete: () -> Unit, onLocate: () -> Unit) {
  val packName =
    remember(pack.metadata) {
      pack.metadata?.decodeToString().orEmpty().ifBlank { "Unnamed Region" }
    }
  val progress = pack.progress

  ListItem(
    leadingContent = {},
    headlineContent = { Text(packName) },
    supportingContent = {
      when (progress) {
        is DownloadProgress.Unknown -> Text("Status: Unknown")
        is DownloadProgress.Healthy -> {
          if (progress.downloadStatus == DownloadStatus.Downloading)
            LinearProgressIndicator(
              progress = {
                if (progress.requiredResourceCount == 0L) 0f
                else progress.completedResourceCount.toFloat() / progress.requiredResourceCount
              },
              modifier = Modifier.fillMaxWidth(),
            )
          else Text("Status: ${progress.downloadStatus.name}")
        }
        is DownloadProgress.Error -> Text("Status: Error - ${progress.message}")
        is DownloadProgress.TileLimitExceeded ->
          Text("Status: Tile Limit Exceeded (${progress.limit})")
      }
    },
    trailingContent = {
      Row {
        if (progress is DownloadProgress.Healthy) {
          if (progress.downloadStatus == DownloadStatus.Paused)
            Button(onClick = { pack.resume() }) { Text("Resume") }
          else if (progress.downloadStatus == DownloadStatus.Downloading)
            Button(onClick = { pack.suspend() }) { Text("Pause") }
        }
        Button(onClick = onLocate, modifier = Modifier.padding(start = 8.dp)) { Text("Locate") }
        Button(onClick = onDelete, modifier = Modifier.padding(start = 8.dp)) { Text("Delete") }
      }
    },
  )
}
