package org.maplibre.compose.demoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.demoapp.util.Platform
import org.maplibre.compose.demoapp.util.PlatformFeature
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.material3.DisappearingCompassButton
import org.maplibre.compose.material3.DisappearingScaleBar
import org.maplibre.compose.material3.ExpandingAttributionButton
import org.maplibre.compose.style.StyleState
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
fun DemoMap(state: DemoState, padding: PaddingValues = PaddingValues()) {
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
        val ornamentOptions =
          if (state.ornamentOptionsState.isMaterial3ControlsEnabled) OrnamentOptions.OnlyLogo
          else state.ornamentOptions

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
              ornamentOptions =
                Platform.padOrnaments(ornamentOptions, calculateOrnamentPadding(padding)),
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
      }
    }

    if (state.ornamentOptionsState.isMaterial3ControlsEnabled) {
      MapOverlay(padding, state.cameraState, state.styleState)
    }
  }
}

@Composable
private fun calculateOrnamentPadding(mapPadding: PaddingValues): PaddingValues {
  fun formula(safeDrawing: Dp, map: Dp): Dp = max(safeDrawing, map)
  val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
  val dir = LocalLayoutDirection.current
  return PaddingValues.Absolute(
    left = formula(safeDrawing.calculateLeftPadding(dir), mapPadding.calculateLeftPadding(dir)),
    right = formula(safeDrawing.calculateRightPadding(dir), mapPadding.calculateRightPadding(dir)),
    top = formula(safeDrawing.calculateTopPadding(), mapPadding.calculateTopPadding()),
    bottom = formula(safeDrawing.calculateBottomPadding(), mapPadding.calculateBottomPadding()),
  )
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
