package dev.sargunv.maplibrecompose.demoapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.StyleState
import dev.sargunv.maplibrecompose.material3.controls.DisappearingCompassButton
import dev.sargunv.maplibrecompose.material3.controls.DisappearingScaleBar
import dev.sargunv.maplibrecompose.material3.controls.ExpandingAttributionButton

@Composable
fun MapOverlay(padding: PaddingValues, cameraState: CameraState, styleState: StyleState) {
  Box(
    Modifier.padding(padding)
      .consumeWindowInsets(padding)
      .safeDrawingPadding()
      .padding(12.dp)
      .fillMaxSize()
  ) {
    DisappearingScaleBar(
      metersPerDp = cameraState.metersPerDpAtTarget,
      zoom = cameraState.position.zoom,
      modifier = Modifier.align(Alignment.TopStart),
    )

    DisappearingCompassButton(
      cameraState = cameraState,
      modifier = Modifier.align(Alignment.TopEnd),
    )

    ExpandingAttributionButton(
      cameraState,
      styleState,
      modifier = Modifier.align(Alignment.BottomEnd),
    )
  }
}
