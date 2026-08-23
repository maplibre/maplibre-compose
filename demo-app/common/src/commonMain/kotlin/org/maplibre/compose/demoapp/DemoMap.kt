package org.maplibre.compose.demoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.filter_center_focus_24px
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.material3.Material3
import org.maplibre.compose.material3.PointerPinButton
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include

/** How long the camera takes to fly to a newly selected demo. */
val DemoFlightDuration = 2.seconds

/** Padding between fitted bounds and the edge of the map viewport. */
val DemoBoundsPadding = PaddingValues(48.dp)

internal suspend fun CameraState.flyTo(destination: DemoDestination) {
  when (destination) {
    is DemoDestination.ExactCamera ->
      animateTo(finalPosition = destination.position, duration = DemoFlightDuration)
    is DemoDestination.FitBounds ->
      animateTo(
        boundingBox = destination.bounds,
        padding = DemoBoundsPadding,
        duration = DemoFlightDuration,
      )
    DemoDestination.None -> Unit
  }
}

/** The shared map, the selected demo's overlay, the pointer pin, and the diagnostic overlays. */
@Composable
fun DemoMap(state: DemoAppState, viewportInsets: MapViewportInsets) {
  Box(Modifier.fillMaxSize()) {
    MaplibreMap(
      baseStyle = state.selectedStyle.base,
      cameraState = state.cameraState,
      cameraPadding = viewportInsets.asPaddingValues(),
      styleState = state.styleState,
      options =
        MapOptions(
          renderOptions = state.settings.renderOptions,
          gestureOptions = state.settings.gestureOptions,
          tileLodOptions = state.settings.tileLodOptions,
        ),
      onFrame = { state.frameRateState.record() },
      contentWindowInsets = viewportInsets.asWindowInsets(),
      overlay =
        MapOverlay {
          include(
            if (state.settings.useMaterial3Controls) MapOverlay.Material3 else MapOverlay.Default
          )
          state.selectedDemo?.let { demo -> key(demo) { with(demo) { Overlay(state) } } }
        },
    ) {
      // Keyed: without it, layers and sources are identified by position, so switching demos
      // would dispose and recreate unrelated content.
      allDemos.forEach { demo ->
        key(demo) {
          if (demo == state.selectedDemo) {
            demo.MapContent(state.cameraState)
          }
        }
      }
    }

    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().padding(viewportInsets.asPaddingValues())) {
      state.selectedDemo?.let { demo ->
        demo.pointerPin?.let { pointerPin ->
          // The pin fills this box to place itself; sizing the pin itself is up to its content.
          PointerPinButton(
            cameraState = state.cameraState,
            targetPosition = pointerPin.target,
            onClick = { scope.launch { state.cameraState.flyTo(pointerPin.destination) } },
          ) {
            Icon(
              vectorResource(Res.drawable.filter_center_focus_24px),
              contentDescription = "Fly back to ${demo.name}",
            )
          }
        }
      }

      DiagnosticOverlays(state = state, modifier = Modifier.align(Alignment.TopCenter))
    }
  }
}

@Composable
private fun DiagnosticOverlays(state: DemoAppState, modifier: Modifier = Modifier) {
  if (state.settings.showFpsOverlay) {
    LaunchedEffect(state.frameRateState) { state.frameRateState.track() }
  }
  Column(
    modifier =
      modifier
        .padding(8.dp)
        .background(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
          shape = RoundedCornerShape(8.dp),
        )
        .padding(horizontal = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (state.settings.showFpsOverlay) {
      Text(
        text = "${state.frameRateState.framesPerSecond.roundToInt()} fps",
        style = MaterialTheme.typography.labelMedium,
      )
    }
    if (state.settings.showCameraOverlay) {
      val position = state.cameraState.position
      Text(
        text =
          "lat ${position.target.latitude.format(4)} " +
            "lng ${position.target.longitude.format(4)} " +
            "zoom ${position.zoom.format(1)} " +
            "bearing ${position.bearing.format(0)} " +
            "tilt ${position.tilt.format(0)}",
        style = MaterialTheme.typography.labelMedium,
      )
    }
  }
}

private fun Double.format(decimals: Int): String {
  var factor = 1.0
  repeat(decimals) { factor *= 10 }
  val rounded = (this * factor).roundToInt() / factor
  return if (decimals == 0) rounded.roundToInt().toString() else rounded.toString()
}
