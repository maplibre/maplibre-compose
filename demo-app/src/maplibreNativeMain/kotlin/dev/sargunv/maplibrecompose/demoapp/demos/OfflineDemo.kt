package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.offline.DownloadState
import dev.sargunv.maplibrecompose.compose.offline.OfflineRegion
import dev.sargunv.maplibrecompose.compose.offline.OfflineRegionDefinition
import dev.sargunv.maplibrecompose.compose.offline.OfflineRegionStatus
import dev.sargunv.maplibrecompose.compose.offline.rememberOfflineRegionManager
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.demoapp.DEFAULT_STYLE
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import kotlinx.coroutines.launch

object OfflineDemo : Demo {
  override val name: String
    get() = "Offline regions"

  override val description: String
    get() = "Save regions of the map to device storage."

  @Composable
  override fun Component(navigateUp: () -> Unit) {
    DemoScaffold(this, navigateUp) {
      Column {
        val cameraState = rememberCameraState(firstPosition = CameraPosition())
        val styleState = rememberStyleState()

        Box(modifier = Modifier.weight(0.5f)) {
          MaplibreMap(
            styleUri = DEFAULT_STYLE,
            cameraState = cameraState,
            styleState = styleState,
            ornamentSettings = DemoOrnamentSettings(),
          )
          DemoMapControls(cameraState, styleState)
        }

        Column(modifier = Modifier.weight(0.5f)) {
          val coroutineScope = rememberCoroutineScope()
          val offlineManager = rememberOfflineRegionManager()
          val density = LocalDensity.current

          var offlineRegions by remember { mutableStateOf(emptyList<OfflineRegion>()) }
          LaunchedEffect(offlineManager) { offlineRegions = offlineManager.listOfflineRegions() }

          Button(
            onClick = {
              coroutineScope.launch {
                val region =
                  offlineManager.createOfflineRegion(
                    OfflineRegionDefinition.TilePyramid(
                      styleUrl = DEFAULT_STYLE,
                      bounds = cameraState.awaitProjection().queryVisibleBoundingBox(),
                      pixelRatio = density.density,
                    )
                  )
                region.setDownloadState(DownloadState.Active)
                offlineRegions = offlineManager.listOfflineRegions()
              }
            },
            enabled = cameraState.position.zoom >= 10.0,
          ) {
            Text("Save region")
          }

          Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            offlineRegions.forEachIndexed { i, region ->
              ListItem(
                headlineContent = { Text("Region ${region.id}") },
                supportingContent = {
                  val status = region.status
                  Text(
                    when (status) {
                      is OfflineRegionStatus.Normal ->
                        "${status.completedResourceCount} downloaded of ${status.requiredResourceCount} required"
                      is OfflineRegionStatus.Error -> "Error: " + status.message
                      is OfflineRegionStatus.TileLimitExceeded ->
                        "Tile limit exceeded: " + status.limit
                      null -> "Loading..."
                    }
                  )
                },
                trailingContent = {
                  Button(
                    onClick = {
                      coroutineScope.launch {
                        region.delete()
                        offlineRegions = offlineManager.listOfflineRegions()
                      }
                    }
                  ) {
                    Text("Delete")
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}
