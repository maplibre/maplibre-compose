package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.demoapp.benchmark.BenchmarkScenario
import org.maplibre.compose.demoapp.benchmark.BenchmarkUiState
import org.maplibre.compose.demoapp.benchmark.allBenchmarkScenarios
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState
import org.maplibre.spatialk.geojson.Position

/** New York City at a metro-area zoom, so every demo's fly-in has somewhere to go. */
private val StartPosition =
  CameraPosition(target = Position(longitude = -74.006, latitude = 40.7128), zoom = 9.5)

/** Whether the shell is showing the shared demo map or the isolated benchmark map. */
enum class DemoShell {
  Demos,
  Benchmarks,
}

/** The state the shell owns: the shared map, the selection, and the settings. */
@Stable
class DemoAppState(
  val cameraState: CameraState,
  val styleState: StyleState,
  val settings: DemoSettings,
  val frameRateState: FrameRateState,
) {
  var selectedDemo by mutableStateOf<Demo?>(null)
  var selectedStyle by mutableStateOf(DemoStyle.Default)
  var shell by mutableStateOf(DemoShell.Demos)
  var selectedScenario by mutableStateOf<BenchmarkScenario>(allBenchmarkScenarios.first())
  val benchmark = BenchmarkUiState()
}

@Composable
fun rememberDemoAppState(): DemoAppState {
  val cameraState = rememberCameraState(firstPosition = StartPosition)
  val styleState = rememberStyleState()
  val settings = rememberDemoSettings()
  val frameRateState = remember { FrameRateState() }
  return remember { DemoAppState(cameraState, styleState, settings, frameRateState) }
}
