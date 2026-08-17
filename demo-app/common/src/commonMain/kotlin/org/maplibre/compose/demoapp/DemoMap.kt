package org.maplibre.compose.demoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
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

/** How long the camera takes to fly to a newly selected demo. */
val DemoFlightDuration = 2.seconds

/** Padding between a demo's region and the edge of the map viewport. */
val DemoRegionPadding = PaddingValues(48.dp)

private suspend fun CameraState.flyToDemo(demo: Demo) {
  val camera = demo.camera
  if (camera != null) {
    animateTo(finalPosition = camera, duration = DemoFlightDuration)
  } else {
    animateTo(boundingBox = demo.region, padding = DemoRegionPadding, duration = DemoFlightDuration)
  }
}

/** The shared map, the pointer pin back to the selected demo, and the diagnostic overlays. */
@Composable
fun DemoMap(state: DemoAppState, sheetInsets: WindowInsets = WindowInsets(0)) {
  val insets = WindowInsets.safeDrawing.union(sheetInsets)

  LaunchedEffect(state.selectedDemo) {
    val demo = state.selectedDemo ?: return@LaunchedEffect
    demo.preferredStyle?.let { state.selectedStyle = it }
    state.cameraState.flyToDemo(demo)
  }

  Box(Modifier.fillMaxSize()) {
    MaplibreMap(
      baseStyle = state.selectedStyle.base,
      cameraState = state.cameraState,
      styleState = state.styleState,
      options =
        MapOptions(
          renderOptions = state.settings.renderOptions,
          gestureOptions = state.settings.gestureOptions,
        ),
      onFrame = { state.frameRateState.record() },
      contentWindowInsets = insets,
      overlay =
        if (state.settings.useMaterial3Controls) MapOverlay.Material3 else MapOverlay.Default,
    ) {
      // Keyed: without it, layers and sources are identified by position, so switching demos
      // would dispose and recreate unrelated content.
      allDemos.forEach { demo ->
        key(demo) {
          if (demo == state.selectedDemo) {
            demo.MapContent()
          }
        }
      }
    }

    val scope = rememberCoroutineScope()
    state.selectedDemo?.let { demo ->
      // The pin fills this box to place itself; sizing the pin itself is up to its content.
      Box(Modifier.fillMaxSize().padding(insets.asPaddingValues())) {
        PointerPinButton(
          cameraState = state.cameraState,
          targetPosition = demo.center,
          onClick = { scope.launch { state.cameraState.flyToDemo(demo) } },
        ) {
          Icon(
            vectorResource(Res.drawable.filter_center_focus_24px),
            contentDescription = "Fly back to ${demo.name}",
          )
        }
      }
    }

    DiagnosticOverlays(
      state = state,
      modifier = Modifier.align(Alignment.TopCenter).padding(insets.asPaddingValues()),
    )
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
