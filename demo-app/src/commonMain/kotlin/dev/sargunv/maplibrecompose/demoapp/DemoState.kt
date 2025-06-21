package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination
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

  private val navDestinationState = mutableStateOf<NavDestination?>(null)

  val navDestination: NavDestination?
    get() = navDestinationState.value

  init {
    nav.addOnDestinationChangedListener { _, destination, _ ->
      navDestinationState.value = destination
    }
  }
}

@Composable
fun rememberDemoState(): DemoState {
  return DemoState(
    nav = rememberNavController(),
    cameraState = rememberCameraState(),
    styleState = rememberStyleState(),
  )
}
