package org.maplibre.compose.demoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.material3.Material3
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.util.ClickResult

private fun getMapAlignment(position: MapPosition): Alignment {
  return when (position) {
    MapPosition.TopLeft -> Alignment.TopStart
    MapPosition.TopCenter -> Alignment.TopCenter
    MapPosition.TopRight -> Alignment.TopEnd
    MapPosition.CenterLeft -> Alignment.CenterStart
    MapPosition.Center -> Alignment.Center
    MapPosition.CenterRight -> Alignment.CenterEnd
    MapPosition.BottomLeft -> Alignment.BottomStart
    MapPosition.BottomCenter -> Alignment.BottomCenter
    MapPosition.BottomRight -> Alignment.BottomEnd
  }
}

@Composable
fun DemoMap(state: DemoState, sheetInsets: WindowInsets = WindowInsets(0, 0, 0, 0)) {
  Box(Modifier.background(MaterialTheme.colorScheme.background).fillMaxSize()) {
    Box(
      modifier =
        Modifier.let {
            when (state.mapManipulationState.size) {
              MapSize.Full -> it.fillMaxSize()
              MapSize.Half -> it.fillMaxSize(0.5f)
              MapSize.Fixed -> it.size(200.dp)
            }
          }
          .align(getMapAlignment(state.mapManipulationState.position))
    ) {
      if (state.mapManipulationState.isVisible) {
        MaplibreMap(
          styleState = state.styleState,
          cameraState = state.cameraState,
          baseStyle = state.selectedStyle.base,
          onMapClick = { position, offset ->
            state.mapClickEvents.add(MapClickEvent(position, offset))
            ClickResult.Pass
          },
          onFrame = { state.frameRateState.record() },
          options =
            MapOptions(
              renderOptions = state.renderOptions,
              gestureOptions = state.gestureOptions,
            ),
          contentWindowInsets = WindowInsets.safeDrawing.union(sheetInsets),
          overlay =
            when (state.mapControlsState.controls) {
              MapControls.Foundation -> MapOverlay.Default
              MapControls.Material3 -> MapOverlay.Material3
              MapControls.None -> MapOverlay.None
            },
        ) {
          // Keyed: without it, layers and sources are identified by position, so opening one demo
          // disposes and recreates every demo after it.
          state.demos
            .filter { state.shouldRenderMapContent(it) }
            .forEach { key(it) { it.MapContent(state = state, isOpen = state.isDemoOpen(it)) } }
        }

        state.demos
          .filter { state.isDemoOpen(it) }
          .forEach { key(it) { it.MapOverlayContent(state = state, isOpen = true) } }
      }
    }
  }
}
