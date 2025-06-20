package dev.sargunv.maplibrecompose.demoapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.StyleState
import dev.sargunv.maplibrecompose.core.MapOptions
import dev.sargunv.maplibrecompose.demoapp.StyleInfo
import dev.sargunv.maplibrecompose.demoapp.util.Platform
import dev.sargunv.maplibrecompose.demoapp.util.PlatformFeature
import dev.sargunv.maplibrecompose.demoapp.util.rememberOrnamentOptions

@Composable
fun DemoMap(
  padding: PaddingValues,
  styleState: StyleState,
  cameraState: CameraState,
  chosenStyle: StyleInfo,
) {
  Box(Modifier.background(MaterialTheme.colorScheme.background)) {
    MaplibreMap(
      styleState = styleState,
      cameraState = cameraState,
      baseStyle = chosenStyle.base,
      options = MapOptions(ornamentOptions = rememberOrnamentOptions(padding)),
    ) {}

    if (PlatformFeature.InteropBlending in Platform.supportedFeatures) {
      MapOverlay(padding, cameraState, styleState)
    }
  }
}
