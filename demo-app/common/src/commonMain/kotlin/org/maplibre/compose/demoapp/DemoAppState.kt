package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState

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
}

@Composable
fun rememberDemoAppState(): DemoAppState {
  val cameraState = rememberCameraState()
  val styleState = rememberStyleState()
  val settings = rememberDemoSettings()
  val frameRateState = remember { FrameRateState() }
  return remember { DemoAppState(cameraState, styleState, settings, frameRateState) }
}
