package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.StyleState
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState

class DemoState(
  val nav: NavHostController,
  val cameraState: CameraState,
  val styleState: StyleState,
) {
  var selectedStyle by mutableStateOf<DemoStyle>(Protomaps.Light)
}

@Composable
fun rememberDemoState(): DemoState {
  return DemoState(
    nav = rememberNavController(),
    cameraState = rememberCameraState(),
    styleState = rememberStyleState(),
  )
}
