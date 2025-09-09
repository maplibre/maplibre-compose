package org.maplibre.compose.demoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.demoapp.util.Platform
import org.maplibre.compose.demoapp.util.PlatformFeature
import org.maplibre.compose.demoapp.util.rememberOrnamentOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.material3.DisappearingCompassButton
import org.maplibre.compose.material3.DisappearingScaleBar
import org.maplibre.compose.material3.ExpandingAttributionButton
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.util.ClickResult

@Composable
fun DemoMap(state: DemoState, padding: PaddingValues = PaddingValues()) {
  Box(Modifier.background(MaterialTheme.colorScheme.background)) {
    MaplibreMap(
      styleState = state.styleState,
      cameraState = state.cameraState,
      baseStyle = state.selectedStyle.base,
      onMapClick = { position, offset ->
        state.mapClickEvents.add(MapClickEvent(position, offset))
        ClickResult.Pass
      },
      options =
        MapOptions(
          ornamentOptions = rememberOrnamentOptions(padding),
          renderOptions = state.renderOptions,
          gestureOptions = state.gestureOptions,
        ),
    ) {
      if (PlatformFeature.LayerStyling in Platform.supportedFeatures) {
        state.demos
          .filter { state.shouldRenderMapContent(it) }
          .forEach { it.MapContent(state = state, isOpen = state.isDemoOpen(it)) }
      }
    }

    MapOverlay(padding, state.cameraState, state.styleState)
  }
}

@Composable
private fun MapOverlay(padding: PaddingValues, cameraState: CameraState, styleState: StyleState) {
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
